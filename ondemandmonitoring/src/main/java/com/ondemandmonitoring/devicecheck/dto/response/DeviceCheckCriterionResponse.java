package com.ondemandmonitoring.devicecheck.dto.response;

import com.ondemandmonitoring.devicecheck.enums.DeviceCheckPhase;
import java.time.Instant;
import lombok.Builder;

@Builder
public record DeviceCheckCriterionResponse(
        String id,
        DeviceCheckPhase phase,
        String code,
        String name,
        String description,
        String category,
        boolean required,
        boolean active,
        int sortOrder,
        /** Built-in code with dedicated backend evaluation; deleting it removes that behaviour. */
        boolean systemLogic,
        Instant createdAt,
        Instant updatedAt,
        Long version) {
}
