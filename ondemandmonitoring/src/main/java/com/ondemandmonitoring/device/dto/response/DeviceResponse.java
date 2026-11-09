package com.ondemandmonitoring.device.dto.response;

import com.ondemandmonitoring.device.enums.DeviceStatus;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceResponse {

    private String id;
    private String serialNumber;
    private String name;
    private DeviceModelResponse deviceModel;
    private DeviceStatus status;
    private String imageUrl;
    private Instant createdAt;
    private Instant updatedAt;
    private Long version;
}
