package com.ondemandmonitoring.notification.dto;

import java.time.Instant;

public record NotificationItemResponse(
        String id,
        NotificationType type,
        String entityId,
        String relatedEntityId,
        String reference,
        Instant createdAt) {
}
