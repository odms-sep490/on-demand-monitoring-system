package com.ondemandmonitoring.notification.websocket;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Publishes a lightweight refresh signal after a successful authenticated API
 * mutation. The signal contains no order, mission, or user data; each client
 * reloads the feed scoped to its own account and role.
 */
@Component
public class NotificationRefreshFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(NotificationRefreshFilter.class);
    private static final String DESTINATION = "/topic/notifications/refresh";

    private final SimpMessagingTemplate messagingTemplate;

    public NotificationRefreshFilter(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        filterChain.doFilter(request, response);

        if (!isSuccessfulMutation(request, response)) {
            return;
        }

        try {
            messagingTemplate.convertAndSend(
                    DESTINATION,
                    new NotificationRefreshSignal(Instant.now()));
        } catch (RuntimeException exception) {
            // A websocket outage must never turn a successful API write into a failure.
            log.debug("Could not publish notification refresh signal", exception);
        }
    }

    private boolean isSuccessfulMutation(HttpServletRequest request, HttpServletResponse response) {
        String method = request.getMethod();
        if (!("POST".equals(method) || "PUT".equals(method)
                || "PATCH".equals(method) || "DELETE".equals(method))) {
            return false;
        }

        String path = request.getRequestURI();
        if (path == null || !path.startsWith("/api/")) {
            return false;
        }
        // Auth, notification-read, and chat changes do not affect the work feed.
        if (path.startsWith("/api/auth/")
                || path.startsWith("/api/notifications")
                || path.startsWith("/api/chat/")) {
            return false;
        }
        return response.getStatus() >= 200 && response.getStatus() < 300;
    }

}
