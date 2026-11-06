package com.ondemandmonitoring.mission.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RequestDeviceReplacementRequest(
        @NotBlank @Size(max = 500) String reason) {
}
