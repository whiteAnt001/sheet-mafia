package org.whiteant.sheetmafia.controller;

import org.whiteant.sheetmafia.dto.ClientMessage;
import org.whiteant.sheetmafia.dto.ServerMessage;
import org.whiteant.sheetmafia.game.GameException;
import org.whiteant.sheetmafia.game.GameService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.Map;

@Controller
@RequiredArgsConstructor
public class GameController {

    private final GameService gameService;

    @MessageMapping("/room/{roomId}/join")
    public void join(@DestinationVariable String roomId, @Payload ClientMessage msg,
                     Principal principal, SimpMessageHeaderAccessor accessor) {
        gameService.join(roomId, principal.getName(), accessor.getSessionId(), msg.nickname());
    }

    @MessageMapping("/room/{roomId}/start")
    public void start(@DestinationVariable String roomId, Principal principal) {
        gameService.start(roomId, principal.getName());
    }

    @MessageMapping("/room/{roomId}/chat")
    public void chat(@DestinationVariable String roomId, @Payload ClientMessage msg, Principal principal) {
        gameService.chat(roomId, principal.getName(), msg.content());
    }

    @MessageMapping("/room/{roomId}/mafia-chat")
    public void mafiaChat(@DestinationVariable String roomId, @Payload ClientMessage msg, Principal principal) {
        gameService.mafiaChat(roomId, principal.getName(), msg.content());
    }

    @MessageMapping("/room/{roomId}/night")
    public void night(@DestinationVariable String roomId, @Payload ClientMessage msg, Principal principal) {
        gameService.nightAction(roomId, principal.getName(), msg.target());
    }

    @MessageMapping("/room/{roomId}/vote")
    public void vote(@DestinationVariable String roomId, @Payload ClientMessage msg, Principal principal) {
        gameService.vote(roomId, principal.getName(), msg.target());
    }

    @MessageMapping("/room/{roomId}/boss")
    public void boss(@DestinationVariable String roomId, Principal principal) {
        gameService.bossAlert(roomId, principal.getName());
    }

    @MessageExceptionHandler(GameException.class)
    @SendToUser(destinations = "/queue/private", broadcast = false)
    public ServerMessage handleGameException(GameException e) {
        return new ServerMessage("ERROR", Map.of("text", e.getMessage()));
    }
}
