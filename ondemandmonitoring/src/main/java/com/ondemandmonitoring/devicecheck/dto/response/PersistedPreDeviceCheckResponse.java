package com.ondemandmonitoring.devicecheck.dto.response;

import com.ondemandmonitoring.device.domain.Device;
import com.ondemandmonitoring.devicecheck.domain.PersistedPreDeviceCheck;
import com.ondemandmonitoring.devicecheck.domain.PersistedPreDeviceCheckItem;
import com.ondemandmonitoring.devicecheck.enums.PreDeviceCheckLevel;
import com.ondemandmonitoring.devicecheck.enums.PreDeviceCheckStatus;
import com.ondemandmonitoring.devicecheck.enums.PreDeviceItemStatus;
import com.ondemandmonitoring.devicecheck.enums.PreflightCheckSource;
import com.ondemandmonitoring.mission.domain.DeviceConnection;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PersistedPreDeviceCheckResponse {
    private static final Set<String> IGNORED_CHECK_TYPES = Set.of(
            "GAZEBO", "LOCAL_POSITION", "MODULES", "motor_esc_health",
            "satellite_signal", "positioning_health", "compass_health",
            "imu_health", "home_position", "altitude_sensor");

    String id;
    String missionId;
    String deviceConnectionId;
    String deviceId;
    String deviceCode;
    String deviceName;
    String deviceSerialNumber;
    PreDeviceCheckStatus status;
    Integer totalChecks;
    Integer passedChecks;
    Integer failedChecks;
    Instant startedAt;
    Instant completedAt;
    Integer progressPercent;
    List<Item> items;

    @Getter
    @Builder
    public static class Item {

        String checkType;
        String checkName;
        String description;
        String category;
        Boolean required;
        PreflightCheckSource source;
        PreDeviceItemStatus status;
        PreDeviceCheckLevel checkLevel;
        String message;
        String measuredValue;
        String errorCode;
        String checkedBy;
        Instant checkedAt;
        Instant expiresAt;
    }

    public static PersistedPreDeviceCheckResponse from(PersistedPreDeviceCheck run) {
        Instant now = Instant.now();
        DeviceConnection connection = run.getDeviceConnection();
        Device device = connection == null ? null : connection.getDevice();
        List<PersistedPreDeviceCheckItem> visibleItems = run.getItems().stream()
                .filter(i -> !IGNORED_CHECK_TYPES.contains(i.getCheckType()))
                .toList();
        int total = visibleItems.size();
        int done = (int) visibleItems.stream()
                .filter(i -> isResolved(displayStatus(i)))
                .count();
        int passed = (int) visibleItems.stream()
                .filter(i -> displayStatus(i) == PreDeviceItemStatus.PASSED)
                .count();
        int failed = (int) visibleItems.stream()
                .filter(i -> displayStatus(i) == PreDeviceItemStatus.FAILED)
                .count();
        PreDeviceCheckStatus status = resolveStatus(run, total, done, failed);

        return builder()
                .id(run.getId())
                .missionId(run.getMission().getId())
                .deviceConnectionId(connection == null ? null : connection.getId())
                .deviceId(device == null ? null : device.getId())
                .deviceCode(device == null ? null : device.getDeviceCode())
                .deviceName(device == null ? null : device.getName())
                .deviceSerialNumber(device == null ? null : device.getSerialNumber())
                .status(status)
                .totalChecks(total)
                .passedChecks(passed)
                .failedChecks(failed)
                .startedAt(run.getStartedAt())
                .completedAt(run.getCompletedAt())
                .progressPercent(total == 0 ? 0 : done * 100 / total)
                .items(visibleItems.stream()
                        .map(i -> Item.builder()
                                .checkType(i.getCheckType())
                                .checkName(i.getCheckName())
                                .description(i.getDescription())
                                .category(i.getCategory())
                                .required(i.getRequired())
                                .source(i.getSource())
                                .status(displayStatus(i))
                                .checkLevel(i.getCheckLevel())
                                .message(i.getMessage())
                                .measuredValue(i.getMeasuredValue())
                                .errorCode(i.getErrorCode())
                                .checkedBy(i.getCheckedBy())
                                .checkedAt(i.getCheckedAt())
                                .expiresAt(i.getExpiresAt())
                                .build())
                        .toList())
                .build();
    }

    private static PreDeviceCheckStatus resolveStatus(PersistedPreDeviceCheck run, int total, int done, int failed) {
        if (failed > 0) {
            return PreDeviceCheckStatus.FAILED;
        }
        if (total > 0 && done == total) {
            return PreDeviceCheckStatus.PASSED;
        }
        return run.getStatus() == PreDeviceCheckStatus.CANCELLED
                ? PreDeviceCheckStatus.CANCELLED
                : PreDeviceCheckStatus.CHECKING;
    }

    private static boolean isResolved(PreDeviceItemStatus status) {
        return status == PreDeviceItemStatus.PASSED
                || status == PreDeviceItemStatus.WARNING
                || status == PreDeviceItemStatus.FAILED
                || status == PreDeviceItemStatus.UNKNOWN
                || status == PreDeviceItemStatus.NOT_APPLICABLE;
    }

    /** Keep older persisted STALE rows compatible with the two-result UI. */
    private static PreDeviceItemStatus displayStatus(PersistedPreDeviceCheckItem item) {
        return item.getStatus() == PreDeviceItemStatus.STALE
                ? PreDeviceItemStatus.FAILED
                : item.getStatus();
    }
}
