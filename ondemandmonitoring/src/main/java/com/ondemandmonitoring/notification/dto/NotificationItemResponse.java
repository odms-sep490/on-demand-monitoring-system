package com.ondemandmonitoring.notification.dto;

import java.time.Instant;

public record NotificationItemResponse(
        String id,
        NotificationType type,
        String entityId,
        String relatedEntityId,
        String reference,
        Instant createdAt,
        String actorName) {

    public NotificationItemResponse(
            String id,
            NotificationType type,
            String entityId,
            String relatedEntityId,
            String reference,
            Instant createdAt) {
        this(id, type, entityId, relatedEntityId, reference, createdAt, null);
    }
}
