package com.ondemandmonitoring.devicecheck.dto.request;

import com.ondemandmonitoring.devicecheck.enums.PreDeviceItemStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PreDeviceCheckItemUpdateRequest {

    @NotNull
    PreDeviceItemStatus status;

    String message;

    String measuredValue;
}

