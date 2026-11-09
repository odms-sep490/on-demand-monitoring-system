package com.ondemandmonitoring.devicecheck.dto.response;

import com.ondemandmonitoring.device.domain.Device;
import com.ondemandmonitoring.devicecheck.domain.PersistedPostDeviceCheck;
import com.ondemandmonitoring.devicecheck.domain.PersistedPostDeviceCheckItem;
import com.ondemandmonitoring.devicecheck.enums.DeviceCheckItemStatus;
import com.ondemandmonitoring.devicecheck.enums.DeviceCheckLevel;
import com.ondemandmonitoring.devicecheck.enums.DeviceCheckStatus;
import com.ondemandmonitoring.mission.domain.DeviceConnection;
import java.time.Instant;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PersistedPostDeviceCheckResponse {

    String id;
    String missionId;
    String deviceConnectionId;
    String deviceId;
    String deviceCode;
    String deviceName;
    String deviceSerialNumber;
    DeviceCheckStatus status;
    Integer totalChecks;
    Integer passedChecks;
    Integer failedChecks;
    Instant startedAt;
    Instant completedAt;
    Boolean overallOk;
    Double landingBatteryPercent;
    String landingBatteryState;
    Instant checkedAt;
    Integer progressPercent;
    List<Item> items;

    @Getter
    @Builder
    public static class Item {

        String checkType;
        String checkName;
        DeviceCheckItemStatus status;
        DeviceCheckLevel checkLevel;
        String message;
        Instant checkedAt;
    }

    public static PersistedPostDeviceCheckResponse from(PersistedPostDeviceCheck run) {
        DeviceConnection connection = run.getDeviceConnection();
        Device device = connection == null ? null : connection.getDevice();
        int total = run.getTotalChecks() == null ? 0 : run.getTotalChecks();
        Double landingBatteryPercent = run.getItems().stream()
                .filter(i -> "e1".equalsIgnoreCase(i.getCheckType())
                        || "BATTERY".equalsIgnoreCase(i.getCheckType()))
                .map(PersistedPostDeviceCheckResponse::parseLeadingDouble)
                .filter(value -> value != null)
                .findFirst()
                .orElse(null);
        int done = run.getItems().stream()
                .filter(i -> i.getStatus() == DeviceCheckItemStatus.PASSED
                        || i.getStatus() == DeviceCheckItemStatus.FAILED)
                .toList()
                .size();

        return builder()
                .id(run.getId())
                .missionId(run.getMission().getId())
                .deviceConnectionId(connection == null ? null : connection.getId())
                .deviceId(device == null ? null : device.getId())
                .deviceCode(device == null ? null : device.getDeviceCode())
                .deviceName(device == null ? null : device.getName())
                .deviceSerialNumber(device == null ? null : device.getSerialNumber())
                .status(run.getStatus())
                .totalChecks(total)
                .passedChecks(run.getPassedChecks())
                .failedChecks(run.getFailedChecks())
                .startedAt(run.getStartedAt())
                .completedAt(run.getCompletedAt())
                .overallOk(run.getStatus() == DeviceCheckStatus.PASSED)
                .landingBatteryPercent(landingBatteryPercent)
                .landingBatteryState(batteryState(landingBatteryPercent))
                .checkedAt(run.getCompletedAt() == null ? run.getStartedAt() : run.getCompletedAt())
                .progressPercent(total == 0 ? 0 : done * 100 / total)
                .items(run.getItems().stream()
                        .map(i -> Item.builder()
                                .checkType(i.getCheckType())
                                .checkName(i.getCheckName())
                                .status(i.getStatus())
                                .checkLevel(i.getCheckLevel())
                                .message(i.getMessage())
                                .checkedAt(i.getCheckedAt())
                                .build())
                        .toList())
                .build();
    }

    private static Double parseLeadingDouble(PersistedPostDeviceCheckItem item) {
        if (item.getMessage() == null) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(-?\\d+(?:\\.\\d+)?)")
                .matcher(item.getMessage());
        if (!matcher.find()) {
            return null;
        }
        return Double.parseDouble(matcher.group(1));
    }

    private static String batteryState(Double percent) {
        if (percent == null) {
            return null;
        }
        if (percent <= 15) {
            return "CRITICAL";
        }
        if (percent <= 30) {
            return "LOW";
        }
        return "NORMAL";
    }
}


