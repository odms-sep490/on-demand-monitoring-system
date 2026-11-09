package com.ondemandmonitoring.mission.dto.request;

import com.ondemandmonitoring.mission.enums.MissionRescheduleReason;
import com.ondemandmonitoring.mission.enums.MissionRescheduleScope;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;

public record MissionScheduleUpdateRequest(
        @NotNull Instant scheduledStartAt,
        @NotNull Instant scheduledEndAt,
        @NotNull MissionRescheduleReason reason,
        @NotNull MissionRescheduleScope scope,
        LocalDate orderPreferredDateFrom,
        LocalDate orderPreferredDateTo,
        String orderPreferredTimeId,
        @Size(max = 1000) String note,
        boolean customerConfirmed) {
}
