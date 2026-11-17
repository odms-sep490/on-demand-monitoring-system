package com.ondemandmonitoring.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Objects;

/** Structured fields expected in the model's JSON content. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiResourceEstimateContent(
        Integer minimumSuggestedDevices,
        Integer recommendedDevices,
        String reason,
        List<String> warnings,
        Integer estimatedStaffCount,
        List<StaffRoleEstimate> staffByRole,
        Integer estimatedDeviceCount,
        List<DeviceTypeEstimate> devicesByType,
        String summary,
        List<String> assumptions) {

    public AiResourceEstimateContent {
        warnings = safeList(warnings);
        staffByRole = safeList(staffByRole);
        devicesByType = safeList(devicesByType);
        assumptions = safeList(assumptions);
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values.stream().filter(Objects::nonNull).toList();
    }

    public record StaffRoleEstimate(String role, Integer count, String reason) {
    }

    public record DeviceTypeEstimate(String deviceTypeCode, Integer count, String reason) {
    }
}
