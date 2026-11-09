package com.ondemandmonitoring.device.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TelemetryRequest {
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    Double batteryPercent;

    Double simX;
    Double simY;
    Double latitude;
    Double longitude;
    Double absoluteAltitude;
    Double altitude;
    Double relativeAltitude;

    @NotNull
    Boolean connected;

    String missionId;
}
