package com.ondemandmonitoring.mission.workspace;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.ondemandmonitoring.chat.config.WebSocketAuthInterceptor;
import com.ondemandmonitoring.chat.repositories.ChatParticipantRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.*;

class MissionWorkspaceStompTest {
    final JwtDecoder decoder = mock(JwtDecoder.class);
    final ChatParticipantRepository participants = mock(ChatParticipantRepository.class);
    final WebSocketAuthInterceptor interceptor = new WebSocketAuthInterceptor(decoder, participants);

    Message<byte[]> frame(StompCommand command, String destination, boolean authenticated) {
        var a = StompHeaderAccessor.create(command);
        if (destination != null) a.setDestination(destination);
        if (authenticated) a.setUser(new UsernamePasswordAuthenticationToken("subject", null, java.util.List.of()));
        return MessageBuilder.createMessage(new byte[0], a.getMessageHeaders());
    }

    @Test void connectRetainsVerifiedSubjectAndRejectsMissingOrBadToken() {
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.CONNECT, null, false), null)).isInstanceOf(IllegalArgumentException.class);
        var a = StompHeaderAccessor.create(StompCommand.CONNECT);
        a.setNativeHeader("Authorization", "Bearer valid");
        when(decoder.decode("valid")).thenReturn(Jwt.withTokenValue("valid").header("alg", "RS256")
                .subject("cognito-sub").expiresAt(Instant.now().plusSeconds(60)).build());
        var connected = interceptor.preSend(MessageBuilder.createMessage(new byte[0], a.getMessageHeaders()), null);
        assertThat(StompHeaderAccessor.wrap(connected).getUser().getName()).isEqualTo("cognito-sub");
        when(decoder.decode("valid")).thenThrow(new JwtException("invalid"));
        assertThatThrownBy(() -> interceptor.preSend(MessageBuilder.createMessage(new byte[0], a.getMessageHeaders()), null)).isInstanceOf(JwtException.class);
    }

    @Test void allowsOwnLogicalQueueOnlyAndPreventsBrokerSpoofingAndPhysicalQueueAccess() {
        assertThat(interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/user/queue/mission-events", true), null)).isNotNull();
        assertThat(interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/user/queue/order-events", true), null)).isNotNull();
        assertThat(interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/topic/notifications/refresh", true), null)).isNotNull();
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/user/queue/mission-events", false), null)).isInstanceOf(IllegalArgumentException.class);
        for (String destination : java.util.List.of("/queue/mission-events-userOTHER", "/user/victim/queue/mission-events", "/topic/mission/m", "/queue/mission-events", "/user/queue/*"))
            assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, destination, true), null)).isInstanceOf(IllegalArgumentException.class);
        for (String destination : java.util.List.of("/queue/mission-events", "/topic/chat/room", "/user/queue/mission-events", "/topic/notifications/refresh"))
            assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SEND, destination, true), null)).isInstanceOf(IllegalArgumentException.class);
        when(participants.existsByChatRoomIdAndUserId("room", "subject")).thenReturn(true);
        assertThat(interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/topic/chat/room/typing", true), null)).isNotNull();
        assertThat(interceptor.preSend(frame(StompCommand.SEND, "/app/chat/room/send", true), null)).isNotNull();
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/topic/chat/private", true), null)).isInstanceOf(IllegalArgumentException.class);
    }
}
