package com.ondemandmonitoring.ai.dto;

import java.time.Instant;
import java.util.List;

public record AiResourceEstimateResponse(
        String orderId,
        String missionId,
        String model,
        Instant generatedAt,
        Integer estimatedStaffCount,
        List<StaffRoleEstimate> staffByRole,
        Integer estimatedDeviceCount,
        List<DeviceTypeEstimate> devicesByType,
        String summary,
        List<String> assumptions,
        Integer minimumSuggestedDevices,
        Integer recommendedDevices,
        String reason,
        List<String> warnings) {

    public record StaffRoleEstimate(String role, Integer count, String reason) {
    }

    public record DeviceTypeEstimate(String deviceTypeCode, Integer count, String reason) {
    }
}
