package org.whiteant.sheetmafia.config;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;

import java.util.UUID;

public class PidChannelInterceptor implements ChannelInterceptor {

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if(accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            String pid = accessor.getFirstNativeHeader("pid");
            if(pid == null || !pid.matches("[a-zA-Z0-9-]{8,64}")) {
                pid = UUID.randomUUID().toString();
            }
            accessor.setUser(new StompPrincipal(pid));
        }

        return message;
    }
}
