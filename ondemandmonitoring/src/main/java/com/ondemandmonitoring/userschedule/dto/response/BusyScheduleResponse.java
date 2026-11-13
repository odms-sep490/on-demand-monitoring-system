package com.ondemandmonitoring.userschedule.dto.response;

import com.ondemandmonitoring.userschedule.enums.BusySession;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Builder;

@Builder
public record BusyScheduleResponse(
        String id,
        String staffId,
        String staffName,
        LocalDate date,
        DayOfWeek dayOfWeek,
        BusySession session,
        Instant startTime,
        Instant endTime,
        String notes) {
}
