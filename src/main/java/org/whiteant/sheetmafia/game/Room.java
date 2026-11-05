package org.whiteant.sheetmafia.game;

import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;

@Getter
@Setter
public class Room {

    private final String id;
    private final Map<String, Player> players = new LinkedHashMap<>(); // pid -> player

    private String hostPid;
    private Phase phase = Phase.WAITING;
    private int day;
    private long phaseEndsAt;
    private int phaseSeq;
    private ScheduledFuture<?> timer;
    private Role winner;

    private final Map<String, String> votes = new HashMap<>();        // voterPid -> targetId
    private final Map<String, String> mafiaTargets = new HashMap<>(); // mafiaPid -> targetId
    private String doctorTarget;
    private String policeTarget;

    private String accusedId;                                          // 최후의 변론 대상 (공개 id)
    private final Map<String, Boolean> finalVotes = new HashMap<>();   // voterPid -> 찬성 여부

    public Room(String id) {
        this.id = id;
    }

    public Optional<Player> findById(String publicId) {
        if (publicId == null) {
            return Optional.empty();
        }
        return players.values().stream()
                .filter(p -> p.getId().equals(publicId))
                .findFirst();
    }

    public List<Player> alivePlayers() {
        return players.values().stream().filter(Player::isAlive).toList();
    }

    public List<Player> deadPlayers() {
        return players.values().stream().filter(p -> !p.isAlive()).toList();
    }

    public List<Player> playersByRole(Role role) {
        return players.values().stream().filter(p -> p.getRole() == role).toList();
    }
}