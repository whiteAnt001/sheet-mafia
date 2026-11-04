package org.whiteant.sheetmafia.game;


import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Component
@RequiredArgsConstructor
public class WebSocketEventListener {

    private final GameService gameService;

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        gameService.disconnect(event.getSessionId());
    }
}
