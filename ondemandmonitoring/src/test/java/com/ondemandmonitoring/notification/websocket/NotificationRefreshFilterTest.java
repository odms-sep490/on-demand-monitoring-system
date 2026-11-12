package com.ondemandmonitoring.notification.websocket;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class NotificationRefreshFilterTest {

    private static final String DESTINATION = "/topic/notifications/refresh";

    @Test
    void publishesAfterASuccessfulApiMutation() throws Exception {
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        NotificationRefreshFilter filter = new NotificationRefreshFilter(messaging);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/orders");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(201));

        verify(messaging).convertAndSend(eq(DESTINATION), any(NotificationRefreshSignal.class));
    }

    @Test
    void skipsReadsAndFailedWrites() throws Exception {
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        NotificationRefreshFilter filter = new NotificationRefreshFilter(messaging);
        MockHttpServletRequest readRequest = new MockHttpServletRequest("GET", "/api/notifications");
        filter.doFilter(readRequest, new MockHttpServletResponse(), (req, res) -> {});

        MockHttpServletRequest failedWrite = new MockHttpServletRequest("POST", "/api/orders");
        filter.doFilter(failedWrite, new MockHttpServletResponse(),
                (req, res) -> ((MockHttpServletResponse) res).setStatus(500));

        verifyNoInteractions(messaging);
    }
}
