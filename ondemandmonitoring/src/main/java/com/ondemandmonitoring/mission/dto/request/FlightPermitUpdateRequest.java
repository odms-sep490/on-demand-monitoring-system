package com.ondemandmonitoring.mission.dto.request;

import com.ondemandmonitoring.mission.enums.FlightPermitStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record FlightPermitUpdateRequest(
        @NotNull FlightPermitStatus status,
        @Size(max = 1000) String note,
        @Size(max = 160) String fileName,
        @Size(max = 100) String contentType,
        String dataUrl) {
}
