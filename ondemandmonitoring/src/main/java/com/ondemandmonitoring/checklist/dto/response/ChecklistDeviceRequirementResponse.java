package com.ondemandmonitoring.checklist.dto.response;

import com.ondemandmonitoring.device.dto.response.DeviceTypeResponse;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class ChecklistDeviceRequirementResponse {
    private String id;
    private String checklistId;
    private DeviceTypeResponse deviceType;
    private Instant createdAt;
    private Instant updatedAt;
    private Long version;
}
