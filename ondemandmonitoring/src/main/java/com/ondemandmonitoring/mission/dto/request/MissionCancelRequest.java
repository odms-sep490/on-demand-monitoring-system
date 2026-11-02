package com.ondemandmonitoring.mission.dto.request;

import jakarta.validation.constraints.*;

public record MissionCancelRequest(
                @PositiveOrZero Long expectedPlanRevision,
                @NotBlank @Size(max = 1000) String reason) {
}
