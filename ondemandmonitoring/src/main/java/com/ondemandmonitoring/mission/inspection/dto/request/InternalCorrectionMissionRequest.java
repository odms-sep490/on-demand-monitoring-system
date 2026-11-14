package com.ondemandmonitoring.mission.inspection.dto.request;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;

public record InternalCorrectionMissionRequest(
    @NotNull @PositiveOrZero Long expectedVersion,
    @NotNull UUID clientRequestId,
    @NotBlank @Size(max = 255) String scopeLabel,
    @NotNull Instant scheduledStartAt,
    @NotNull Instant scheduledEndAt) {}
