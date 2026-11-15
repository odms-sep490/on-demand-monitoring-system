package com.ondemandmonitoring.missiontemplate.dto;

import java.time.Instant;
import java.util.List;

public record MissionTemplateResponse(
        String id,
        String serviceId,
        String scopeLabel,
        List<String> checklistIds,
        Instant createdAt,
        Instant updatedAt,
        Long version) {}
