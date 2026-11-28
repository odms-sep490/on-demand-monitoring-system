package com.ondemandmonitoring.chat.config;

import com.ondemandmonitoring.chat.repositories.ChatParticipantRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    private final JwtDecoder jwtDecoder;
    private final ChatParticipantRepository chatParticipantRepository;

    public WebSocketAuthInterceptor(
            @Qualifier("cognitoAccessTokenDecoder")
            JwtDecoder jwtDecoder,
            ChatParticipantRepository chatParticipantRepository
    ) {
        this.jwtDecoder = jwtDecoder;
        this.chatParticipantRepository = chatParticipantRepository;
    }

    @Override
    public Message<?> preSend(
            Message<?> message,
            MessageChannel channel
    ) {

        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || !accessor.isMutable()) accessor = StompHeaderAccessor.wrap(message);

        StompCommand command = accessor.getCommand();

        // ==========================
        // CONNECT
        // ==========================

        if (StompCommand.CONNECT.equals(command)) {

            String authorization =
                    accessor.getFirstNativeHeader("Authorization");

            if (authorization == null) {
                authorization =
                        accessor.getFirstNativeHeader("authorization");
            }

            if (authorization == null
                    || !authorization.startsWith("Bearer ")) {

                throw new IllegalArgumentException(
                        "Missing WebSocket access token"
                );
            }

            String token = authorization.substring(7);

            Jwt jwt = jwtDecoder.decode(token);

            String userId = jwt.getSubject();

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            userId,
                            null,
                            List.of()
                    );

            accessor.setUser(authentication);
        }

        // ==========================
        // SUBSCRIBE / SEND
        // ==========================

        if (StompCommand.SUBSCRIBE.equals(command)
                || StompCommand.SEND.equals(command)) {

            if (accessor.getUser() == null) {
                throw new IllegalArgumentException(
                        "Unauthenticated WebSocket connection"
                );
            }

            String destination = accessor.getDestination();

            // Clients may only subscribe to their own logical user queues. In particular,
            // reject physical /queue/*-userSESSION subscriptions and all broker SENDs.
            boolean userQueue = StompCommand.SUBSCRIBE.equals(command) && (
                    "/user/queue/mission-events".equals(destination)
                    || "/user/queue/order-events".equals(destination)
                    || "/topic/notifications/refresh".equals(destination));
            boolean chatDestination = destination != null && (StompCommand.SUBSCRIBE.equals(command)
                    ? destination.matches("/topic/chat/[^/*#]+(?:/(?:typing|read))?")
                    : destination.matches("/app/chat/[^/*#]+/(?:send|typing|read)"));
            if (!userQueue && !chatDestination)
                throw new IllegalArgumentException("WebSocket destination is not allowed");

            String roomId = extractRoomId(destination);

            if (roomId != null) {

                String userId = accessor.getUser().getName();

                boolean participant =
                        chatParticipantRepository
                                .existsByChatRoomIdAndUserId(
                                        roomId,
                                        userId
                                );

                if (!participant) {
                    throw new IllegalArgumentException(
                            "You are not allowed to access this chat room"
                    );
                }
            }
        }

        return MessageBuilder.createMessage(message.getPayload(), accessor.getMessageHeaders());
    }

    private String extractRoomId(String destination) {

        if (destination == null) {
            return null;
        }

        String topicPrefix = "/topic/chat/";

        if (destination.startsWith(topicPrefix)) {

            String path =
                    destination.substring(topicPrefix.length());

            return path.split("/")[0];
        }

        String appPrefix = "/app/chat/";

        if (destination.startsWith(appPrefix)) {

            String path =
                    destination.substring(appPrefix.length());

            return path.split("/")[0];
        }

        return null;
    }
}
