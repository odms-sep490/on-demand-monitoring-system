package com.ondemandmonitoring.mission.dto.request;

import com.ondemandmonitoring.mission.enums.MissionRescheduleReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record MissionRescheduleRequest(
        @NotNull Instant scheduledStartAt,
        @NotNull Instant scheduledEndAt,
        @NotNull MissionRescheduleReason reason,
        @Size(max = 1000) String note,
        boolean customerConfirmed) {
}
