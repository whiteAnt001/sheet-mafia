package org.whiteant.sheetmafia.game;

import org.whiteant.sheetmafia.dto.ServerMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
@Service
public class GameService {

    private final SimpMessagingTemplate messaging;
    private final TaskScheduler scheduler;
    private final int nightSeconds;
    private final int discussSeconds;
    private final int voteSeconds;
    private final int defenseSeconds;
    private final int finalVoteSeconds;
    private final int minPlayers;
    private final int maxPlayers;

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final Map<String, SessionRef> sessions = new ConcurrentHashMap<>();

    private record SessionRef(String roomId, String pid) {
    }

    public GameService(SimpMessagingTemplate messaging,
                       @Qualifier("gameScheduler") TaskScheduler scheduler,
                       @Value("${game.night-seconds:60}") int nightSeconds,
                       @Value("${game.discuss-seconds:180}") int discussSeconds,
                       @Value("${game.vote-seconds:60}") int voteSeconds,
                       @Value("${game.defense-seconds:30}") int defenseSeconds,
                       @Value("${game.final-vote-seconds:20}") int finalVoteSeconds,
                       @Value("${game.min-players:4}") int minPlayers,
                       @Value("${game.max-players:8}") int maxPlayers) {
        this.messaging = messaging;
        this.scheduler = scheduler;
        this.nightSeconds = nightSeconds;
        this.discussSeconds = discussSeconds;
        this.voteSeconds = voteSeconds;
        this.defenseSeconds = defenseSeconds;
        this.finalVoteSeconds = finalVoteSeconds;
        this.minPlayers = minPlayers;
        this.maxPlayers = maxPlayers;
    }

    // 클라이언트 요청

    public void join(String roomId, String pid, String sessionId, String rawNickname) {
        validateRoomId(roomId);
        String nickname = rawNickname == null ? "" : rawNickname.strip();
        if (nickname.isEmpty() || nickname.length() > 12) {
            throw new GameException("이름은 1~12자로 입력해주세요.");
        }

        Room room = rooms.computeIfAbsent(roomId, Room::new);
        synchronized (room) {
            Player player = room.getPlayers().get(pid);
            if (player != null) {
                if (!player.isConnected()) {
                    player.setConnected(true);
                    system(room, player.getNickname() + "님이 다시 접속했습니다.");
                }
            } else {
                if (isInGame(room)) {
                    throw new GameException("이미 진행 중인 게임입니다.");
                }
                if (room.getPlayers().size() >= maxPlayers) {
                    throw new GameException("인원이 가득 찼습니다. (최대 " + maxPlayers + "명)");
                }
                boolean duplicated = room.getPlayers().values().stream()
                        .anyMatch(p -> p.getNickname().equals(nickname));
                if (duplicated) {
                    throw new GameException("이미 사용 중인 이름입니다.");
                }
                player = new Player(pid, nickname);
                room.getPlayers().put(pid, player);
                system(room, nickname + "님이 입장했습니다.");
            }

            if (room.getHostPid() == null || !room.getPlayers().containsKey(room.getHostPid())) {
                room.setHostPid(pid);
            }
            sessions.put(sessionId, new SessionRef(roomId, pid));
            sendSync(room, player);
            broadcastState(room);
        }
    }

    public void start(String roomId, String pid) {
        Room room = getRoom(roomId);
        synchronized (room) {
            if (!pid.equals(room.getHostPid())) {
                throw new GameException("방장만 시작할 수 있습니다.");
            }
            if (isInGame(room)) {
                throw new GameException("이미 진행 중인 게임입니다.");
            }
            room.getPlayers().values().removeIf(p -> !p.isConnected());
            if (room.getPlayers().size() < minPlayers) {
                throw new GameException("최소 " + minPlayers + "명이 필요합니다.");
            }

            assignRoles(room);
            room.setDay(1);
            room.setWinner(null);
            room.getVotes().clear();
            room.getFinalVotes().clear();
            room.setAccusedId(null);
            system(room, "게임이 시작되었습니다. 자신의 직업을 확인하세요.");
            room.getPlayers().values().forEach(p -> sendSync(room, p));
            startNight(room);
        }
    }

    public void chat(String roomId, String pid, String content) {
        Room room = getRoom(roomId);
        synchronized (room) {
            Player player = getPlayer(room, pid);
            String text = cleanText(content);
            if (isInGame(room) && !player.isAlive()) {
                throw new GameException("죽은 플레이어는 사망자 탭에서만 채팅할 수 있습니다.");
            }
            if (room.getPhase() == Phase.NIGHT) {
                throw new GameException("밤에는 채팅할 수 없습니다.");
            }
            if (room.getPhase() == Phase.DEFENSE && !player.getId().equals(room.getAccusedId())) {
                throw new GameException("최후의 변론 중에는 지목된 사람만 발언할 수 있습니다.");
            }
            broadcast(room, "CHAT", Map.of(
                    "from", player.getNickname(),
                    "text", text,
                    "at", System.currentTimeMillis()));
        }
    }

    public void mafiaChat(String roomId, String pid, String content) {
        Room room = getRoom(roomId);
        synchronized (room) {
            Player player = getPlayer(room, pid);
            String text = cleanText(content);
            if (player.getRole() != Role.MAFIA || !isInGame(room)) {
                throw new GameException("마피아만 사용할 수 있는 채팅입니다.");
            }
            if (!player.isAlive()) {
                throw new GameException("죽은 플레이어는 사망자 탭에서만 채팅할 수 있습니다.");
            }
            sendToMafia(room, player.getNickname(), text, false);
        }
    }

    public void deadChat(String roomId, String pid, String content) {
        Room room = getRoom(roomId);
        synchronized (room) {
            Player player = getPlayer(room, pid);
            String text = cleanText(content);
            if (player.isAlive() || player.getRole() == null || room.getPhase() == Phase.WAITING) {
                throw new GameException("사망자만 사용할 수 있는 채팅입니다.");
            }
            sendToDead(room, player.getNickname(), text, false);
        }
    }

    public void nightAction(String roomId, String pid, String targetId) {
        Room room = getRoom(roomId);
        synchronized (room) {
            if (room.getPhase() != Phase.NIGHT) {
                throw new GameException("지금은 밤이 아닙니다.");
            }
            Player player = getPlayer(room, pid);
            if (!player.isAlive()) {
                throw new GameException("죽은 플레이어는 능력을 사용할 수 없습니다.");
            }
            Player target = room.findById(targetId)
                    .filter(Player::isAlive)
                    .orElseThrow(() -> new GameException("대상을 찾을 수 없습니다."));

            switch (player.getRole()) {
                case MAFIA -> {
                    if (target.getRole() == Role.MAFIA) {
                        throw new GameException("같은 마피아는 지목할 수 없습니다.");
                    }
                    room.getMafiaTargets().put(pid, target.getId());
                    sendToMafia(room, "시스템",
                            player.getNickname() + "님이 " + target.getNickname() + "님을 지목했습니다.", true);
                }
                case DOCTOR -> {
                    room.setDoctorTarget(target.getId());
                    info(player, target.getNickname() + "님을 치료합니다.");
                }
                case POLICE -> {
                    if (target == player) {
                        throw new GameException("자기 자신은 조사할 수 없습니다.");
                    }
                    room.setPoliceTarget(target.getId());
                    info(player, target.getNickname() + "님을 조사합니다.");
                }
                default -> throw new GameException("시민은 밤에 사용할 능력이 없습니다.");
            }

            if (nightActionsDone(room)) {
                endNight(room);
            }
        }
    }

    public void vote(String roomId, String pid, String targetId) {
        Room room = getRoom(roomId);
        synchronized (room) {
            if (room.getPhase() != Phase.DAY_VOTE) {
                throw new GameException("지금은 투표 시간이 아닙니다.");
            }
            Player player = getPlayer(room, pid);
            if (!player.isAlive()) {
                throw new GameException("죽은 플레이어는 투표할 수 없습니다.");
            }
            Player target = room.findById(targetId)
                    .filter(Player::isAlive)
                    .orElseThrow(() -> new GameException("대상을 찾을 수 없습니다."));
            if (target == player) {
                throw new GameException("자기 자신에게는 투표할 수 없습니다.");
            }

            room.getVotes().put(pid, target.getId());
            if (room.getVotes().size() >= room.alivePlayers().size()) {
                endVote(room);
            } else {
                broadcastState(room);
            }
        }
    }

    public void finalVote(String roomId, String pid, Boolean agree) {
        Room room = getRoom(roomId);
        synchronized (room) {
            if (room.getPhase() != Phase.FINAL_VOTE) {
                throw new GameException("지금은 찬반 투표 시간이 아닙니다.");
            }
            if (agree == null) {
                throw new GameException("찬성 또는 반대를 선택해주세요.");
            }
            Player player = getPlayer(room, pid);
            if (!player.isAlive()) {
                throw new GameException("죽은 플레이어는 투표할 수 없습니다.");
            }
            if (player.getId().equals(room.getAccusedId())) {
                throw new GameException("지목된 사람은 찬반 투표에 참여할 수 없습니다.");
            }

            room.getFinalVotes().put(pid, agree);
            if (room.getFinalVotes().size() >= room.alivePlayers().size() - 1) {
                endFinalVote(room);
            } else {
                broadcastState(room);
            }
        }
    }

    public void bossAlert(String roomId, String pid) {
        Room room = getRoom(roomId);
        synchronized (room) {
            Player player = getPlayer(room, pid);
            broadcast(room, "BOSS_ALERT", Map.of("from", player.getNickname()));
        }
    }

    public void disconnect(String sessionId) {
        SessionRef ref = sessions.remove(sessionId);
        if (ref == null) {
            return;
        }
        Room room = rooms.get(ref.roomId());
        if (room == null) {
            return;
        }
        synchronized (room) {
            Player player = room.getPlayers().get(ref.pid());
            if (player == null) {
                return;
            }
            // 같은 플레이어가 다른 세션(새 탭, 재접속)으로 살아있으면 무시
            if (sessions.containsValue(ref)) {
                return;
            }

            if (isInGame(room)) {
                player.setConnected(false);
                system(room, player.getNickname() + "님의 연결이 끊겼습니다.");
            } else {
                room.getPlayers().remove(player.getPid());
                system(room, player.getNickname() + "님이 퇴장했습니다.");
            }

            if (player.getPid().equals(room.getHostPid())) {
                room.setHostPid(room.getPlayers().values().stream()
                        .filter(Player::isConnected)
                        .map(Player::getPid)
                        .findFirst()
                        .orElse(null));
            }

            boolean anyoneLeft = room.getPlayers().values().stream().anyMatch(Player::isConnected);
            if (!anyoneLeft) {
                cancelTimer(room);
                rooms.remove(room.getId());
                return;
            }
            broadcastState(room);
        }
    }

    // 라운드 진행

    private void startNight(Room room) {
        room.getMafiaTargets().clear();
        room.setDoctorTarget(null);
        room.setPoliceTarget(null);
        enterPhase(room, Phase.NIGHT, nightSeconds, this::endNight);
        system(room, room.getDay() + "번째 밤이 되었습니다. 마피아, 경찰, 의사는 능력을 사용하세요.");
    }

    private void endNight(Room room) {
        String targetId = pickMafiaTarget(room);
        Player victim = room.findById(targetId).orElse(null);

        if (victim != null && victim.isAlive() && !targetId.equals(room.getDoctorTarget())) {
            kill(room, victim);
            system(room, "밤사이 마피아의 공격으로 " + victim.getNickname() + "님이 사망했습니다.");
        } else {
            system(room, "밤사이 아무도 죽지 않았습니다.");
        }

        room.playersByRole(Role.POLICE).stream()
                .filter(Player::isAlive)
                .findFirst()
                .ifPresent(police -> room.findById(room.getPoliceTarget()).ifPresent(t ->
                        info(police, t.getNickname() + "님은 "
                                + (t.getRole() == Role.MAFIA ? "마피아입니다." : "마피아가 아닙니다."))));

        if (checkWin(room)) {
            return;
        }
        enterPhase(room, Phase.DAY_DISCUSS, discussSeconds, this::startVote);
        system(room, room.getDay() + "번째 낮이 밝았습니다. 자유롭게 토론하세요.");
    }

    private void startVote(Room room) {
        room.getVotes().clear();
        enterPhase(room, Phase.DAY_VOTE, voteSeconds, this::endVote);
        system(room, "투표 시간입니다. 처형할 사람을 지목하세요.");
    }

    private void endVote(Room room) {
        Map<String, Long> tally = tally(room.getVotes());
        String top = null;
        long max = 0;
        boolean tie = false;
        for (Map.Entry<String, Long> e : tally.entrySet()) {
            if (e.getValue() > max) {
                max = e.getValue();
                top = e.getKey();
                tie = false;
            } else if (e.getValue() == max) {
                tie = true;
            }
        }

        Player accused = (top == null || tie) ? null : room.findById(top).filter(Player::isAlive).orElse(null);
        if (accused == null) {
            system(room, "동표이거나 투표가 없어 아무도 지목되지 않았습니다.");
            nextNight(room);
            return;
        }
        startDefense(room, accused);
    }

    private void startDefense(Room room, Player accused) {
        room.setAccusedId(accused.getId());
        room.getFinalVotes().clear();
        enterPhase(room, Phase.DEFENSE, defenseSeconds, this::startFinalVote);
        system(room, "투표 결과 " + accused.getNickname() + "님이 지목되었습니다. 최후의 변론을 시작합니다.");
    }

    private void startFinalVote(Room room) {
        Player accused = room.findById(room.getAccusedId()).filter(Player::isAlive).orElse(null);
        if (accused == null) {
            room.setAccusedId(null);
            nextNight(room);
            return;
        }
        room.getFinalVotes().clear();
        enterPhase(room, Phase.FINAL_VOTE, finalVoteSeconds, this::endFinalVote);
        system(room, accused.getNickname() + "님의 처형에 찬성 또는 반대를 선택하세요.");
    }

    private void endFinalVote(Room room) {
        long yes = room.getFinalVotes().values().stream().filter(Boolean::booleanValue).count();
        long no = room.getFinalVotes().size() - yes;
        Player accused = room.findById(room.getAccusedId()).orElse(null);
        room.setAccusedId(null);
        room.getFinalVotes().clear();

        if (accused != null && accused.isAlive()) {
            String result = "찬성 " + yes + "표, 반대 " + no + "표로 ";
            if (yes > no) {
                kill(room, accused);
                system(room, result + accused.getNickname() + "님이 처형되었습니다.");
            } else {
                system(room, result + accused.getNickname() + "님은 처형을 면했습니다.");
            }
        }

        if (checkWin(room)) {
            return;
        }
        nextNight(room);
    }

    private void nextNight(Room room) {
        room.setDay(room.getDay() + 1);
        startNight(room);
    }

    private void kill(Room room, Player player) {
        player.setAlive(false);
        sendToDead(room, "시스템", player.getNickname() + "님이 사망자 채팅에 합류했습니다.", true);
    }

    private boolean checkWin(Room room) {
        List<Player> alive = room.alivePlayers();
        long mafia = alive.stream().filter(p -> p.getRole() == Role.MAFIA).count();
        long others = alive.size() - mafia;

        Role winner = null;
        if (mafia == 0) {
            winner = Role.CITIZEN;
        } else if (mafia >= others) {
            winner = Role.MAFIA;
        }
        if (winner == null) {
            return false;
        }

        cancelTimer(room);
        room.setWinner(winner);
        room.setPhase(Phase.END);
        room.setPhaseSeq(room.getPhaseSeq() + 1);
        room.setPhaseEndsAt(0);
        room.setAccusedId(null);
        system(room, winner == Role.MAFIA
                ? "마피아 팀이 승리했습니다."
                : "마피아를 모두 찾아냈습니다. 시민 팀 승리!");
        broadcastState(room);
        return true;
    }

    private void enterPhase(Room room, Phase phase, int seconds, Consumer<Room> onTimeout) {
        cancelTimer(room);
        int seq = room.getPhaseSeq() + 1;
        long endsAt = System.currentTimeMillis() + seconds * 1000L;

        room.setPhase(phase);
        room.setPhaseSeq(seq);
        room.setPhaseEndsAt(endsAt);
        room.setTimer(scheduler.schedule(() -> onTimer(room, seq, onTimeout), Instant.ofEpochMilli(endsAt)));
        broadcastState(room);
    }

    private void onTimer(Room room, int seq, Consumer<Room> action) {
        synchronized (room) {
            if (room.getPhaseSeq() != seq) {
                return; // 이미 다음 페이즈로 넘어감
            }
            try {
                action.accept(room);
            } catch (Exception e) {
                log.error("phase transition failed. room={}", room.getId(), e);
            }
        }
    }

    private void cancelTimer(Room room) {
        if (room.getTimer() != null) {
            room.getTimer().cancel(false);
            room.setTimer(null);
        }
    }

    // 내부로직

    private void assignRoles(Room room) {
        List<Player> list = new ArrayList<>(room.getPlayers().values());
        Collections.shuffle(list);
        int mafiaCount = list.size() >= 7 ? 2 : 1;

        for (int i = 0; i < list.size(); i++) {
            Player p = list.get(i);
            p.setAlive(true);
            if (i < mafiaCount) {
                p.setRole(Role.MAFIA);
            } else if (i == mafiaCount) {
                p.setRole(Role.POLICE);
            } else if (i == mafiaCount + 1) {
                p.setRole(Role.DOCTOR);
            } else {
                p.setRole(Role.CITIZEN);
            }
        }
    }

    private boolean nightActionsDone(Room room) {
        for (Player p : room.alivePlayers()) {
            boolean done = switch (p.getRole()) {
                case MAFIA -> room.getMafiaTargets().containsKey(p.getPid());
                case DOCTOR -> room.getDoctorTarget() != null;
                case POLICE -> room.getPoliceTarget() != null;
                default -> true;
            };
            if (!done) {
                return false;
            }
        }
        return true;
    }

    private String pickMafiaTarget(Room room) {
        Map<String, Long> counts = tally(room.getMafiaTargets());
        if (counts.isEmpty()) {
            return null;
        }
        long max = Collections.max(counts.values());
        List<String> top = counts.entrySet().stream()
                .filter(e -> e.getValue() == max)
                .map(Map.Entry::getKey)
                .toList();
        return top.get(ThreadLocalRandom.current().nextInt(top.size()));
    }

    private Map<String, Long> tally(Map<String, String> choices) {
        return choices.values().stream()
                .collect(Collectors.groupingBy(id -> id, LinkedHashMap::new, Collectors.counting()));
    }

    private boolean isInGame(Room room) {
        return switch (room.getPhase()) {
            case NIGHT, DAY_DISCUSS, DAY_VOTE, DEFENSE, FINAL_VOTE -> true;
            default -> false;
        };
    }

    private Room getRoom(String roomId) {
        validateRoomId(roomId);
        Room room = rooms.get(roomId);
        if (room == null) {
            throw new GameException("방을 찾을 수 없습니다.");
        }
        return room;
    }

    private Player getPlayer(Room room, String pid) {
        Player player = room.getPlayers().get(pid);
        if (player == null) {
            throw new GameException("먼저 입장해주세요.");
        }
        return player;
    }

    private void validateRoomId(String roomId) {
        if (roomId == null || !roomId.matches("[a-zA-Z0-9-]{1,32}")) {
            throw new GameException("잘못된 방 주소입니다.");
        }
    }

    private String cleanText(String content) {
        String text = content == null ? "" : content.strip();
        if (text.isEmpty()) {
            throw new GameException("내용을 입력해주세요.");
        }
        return text.length() > 200 ? text.substring(0, 200) : text;
    }

    // 메세지 전송 로직

    private void broadcastState(Room room) {
        boolean reveal = room.getPhase() == Phase.END;
        boolean finalPhase = room.getPhase() == Phase.FINAL_VOTE;
        Player host = room.getPlayers().get(room.getHostPid());

        List<Map<String, Object>> players = new ArrayList<>();
        for (Player p : room.getPlayers().values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("nickname", p.getNickname());
            m.put("alive", p.isAlive());
            m.put("connected", p.isConnected());
            m.put("role", reveal ? p.getRole() : null);
            players.add(m);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("roomId", room.getId());
        data.put("phase", room.getPhase());
        data.put("day", room.getDay());
        data.put("phaseEndsAt", room.getPhaseEndsAt());
        data.put("hostId", host == null ? null : host.getId());
        data.put("players", players);
        data.put("voteCounts", room.getPhase() == Phase.DAY_VOTE ? tally(room.getVotes()) : Map.of());
        data.put("accusedId", room.getAccusedId());
        data.put("finalVoted", finalPhase ? room.getFinalVotes().size() : 0);
        data.put("finalEligible", finalPhase ? Math.max(0, room.alivePlayers().size() - 1) : 0);
        data.put("winner", room.getWinner());
        broadcast(room, "STATE", data);
    }

    private void sendSync(Room room, Player player) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("myId", player.getId());
        data.put("role", player.getRole());
        data.put("team", player.getRole() == Role.MAFIA
                ? room.playersByRole(Role.MAFIA).stream().map(Player::getId).toList()
                : List.of());
        sendPrivate(player, "SYNC", data);
    }

    private void sendToMafia(Room room, String from, String text, boolean system) {
        Map<String, Object> data = Map.of(
                "from", from,
                "text", text,
                "system", system,
                "at", System.currentTimeMillis());
        room.playersByRole(Role.MAFIA).forEach(m -> sendPrivate(m, "MAFIA_CHAT", data));
    }

    private void sendToDead(Room room, String from, String text, boolean system) {
        Map<String, Object> data = Map.of(
                "from", from,
                "text", text,
                "system", system,
                "at", System.currentTimeMillis());
        room.deadPlayers().forEach(p -> sendPrivate(p, "DEAD_CHAT", data));
    }

    private void system(Room room, String text) {
        broadcast(room, "SYSTEM", Map.of("text", text, "at", System.currentTimeMillis()));
    }

    private void info(Player player, String text) {
        sendPrivate(player, "INFO", Map.of("text", text, "at", System.currentTimeMillis()));
    }

    private void broadcast(Room room, String type, Object data) {
        messaging.convertAndSend("/topic/room/" + room.getId(), new ServerMessage(type, data));
    }

    private void sendPrivate(Player player, String type, Object data) {
        messaging.convertAndSendToUser(player.getPid(), "/queue/private", new ServerMessage(type, data));
    }
}