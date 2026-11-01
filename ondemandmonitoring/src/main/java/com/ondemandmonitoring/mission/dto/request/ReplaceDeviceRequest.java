package com.ondemandmonitoring.mission.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ReplaceDeviceRequest {
    @NotBlank(message = "deviceId is required")
    private String deviceId;

    @NotBlank(message = "reason is required")
    @Size(max = 500, message = "reason must be at most 500 characters")
    private String reason;
}
