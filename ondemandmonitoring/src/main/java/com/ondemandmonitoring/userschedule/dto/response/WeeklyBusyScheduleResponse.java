package com.ondemandmonitoring.userschedule.dto.response;

import java.time.LocalDate;
import java.util.List;
import lombok.Builder;

@Builder
public record WeeklyBusyScheduleResponse(
        LocalDate weekStart,
        LocalDate weekEnd,
        int maxSessionsPerWeek,
        int usedSessions,
        int remainingSessions,
        List<BusyScheduleResponse> items) {
}
