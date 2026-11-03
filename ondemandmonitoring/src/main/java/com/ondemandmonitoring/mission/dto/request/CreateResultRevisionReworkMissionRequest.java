package com.ondemandmonitoring.mission.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CreateResultRevisionReworkMissionRequest(
        @NotNull Long expectedCaseVersion,
        @NotNull UUID clientRequestId,
        @NotBlank @Size(max = 255) String scopeLabel,
        @NotNull Instant scheduledStartAt,
        @NotNull Instant scheduledEndAt,
        @NotEmpty @Size(max = 100) List<@NotBlank String> caseItemIds) {}
