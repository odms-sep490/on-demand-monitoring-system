package com.ondemandmonitoring.notification.dto;

import java.time.Instant;

public record NotificationItemResponse(
        String id,
        NotificationType type,
        String entityId,
        String relatedEntityId,
        String reference,
        Instant createdAt,
        String actorName,
        java.math.BigDecimal amount) {

    public NotificationItemResponse(String id, NotificationType type, String entityId, String relatedEntityId, String reference, Instant createdAt, String actorName) {
        this(id, type, entityId, relatedEntityId, reference, createdAt, actorName, null);
    }

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
