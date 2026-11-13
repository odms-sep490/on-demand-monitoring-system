package com.ondemandmonitoring.userschedule.dto.request;

import com.ondemandmonitoring.userschedule.enums.BusySession;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record BusyScheduleRequest(
        @NotNull LocalDate date,
        @NotNull BusySession session,
        @Size(max = 1000) String notes) {
}
