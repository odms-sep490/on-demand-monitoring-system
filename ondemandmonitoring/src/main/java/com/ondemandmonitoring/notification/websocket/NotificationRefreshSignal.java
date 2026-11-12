package com.ondemandmonitoring.notification.websocket;

import java.time.Instant;

/** Credential-free signal telling clients to reload their own scoped feed. */
public record NotificationRefreshSignal(Instant changedAt) {
}
