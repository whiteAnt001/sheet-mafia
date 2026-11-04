package org.whiteant.sheetmafia.game;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class Player {

    private final String pid;      // 비밀 식별자 (본인만 앎)
    private final String id;       // 공개 식별자 (투표/지정 대상)
    private final String nickname;
    private Role role;
    private boolean alive = true;
    private boolean connected = true;

    public Player(String pid, String nickname) {
        this.pid = pid;
        this.nickname = nickname;
        this.id = UUID.randomUUID().toString().substring(0, 8);
    }
}
