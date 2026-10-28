package com.ondemandmonitoring.devicecheck.service.impl;

import com.ondemandmonitoring.devicecheck.service.IPersistedPreDeviceCheckService;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.devicecheck.domain.PersistedPreDeviceCheck;
import com.ondemandmonitoring.devicecheck.domain.PersistedPreDeviceCheckItem;
import com.ondemandmonitoring.devicecheck.dto.request.PreDeviceCheckItemUpdateRequest;
import com.ondemandmonitoring.devicecheck.dto.response.PersistedPreDeviceCheckResponse;
import com.ondemandmonitoring.devicecheck.enums.PreDeviceCheckStatus;
import com.ondemandmonitoring.devicecheck.enums.PreDeviceCheckType;
import com.ondemandmonitoring.devicecheck.enums.PreDeviceItemStatus;
import com.ondemandmonitoring.devicecheck.enums.PreflightCheckSource;
import com.ondemandmonitoring.device.domain.Device;
import com.ondemandmonitoring.device.domain.DeviceTelemetry;
import com.ondemandmonitoring.device.enums.DeviceStatus;
import com.ondemandmonitoring.device.repository.DeviceTelemetryRepository;
import com.ondemandmonitoring.mission.domain.DeviceConnection;
import com.ondemandmonitoring.mission.enums.FlightPermitStatus;
import com.ondemandmonitoring.mission.repository.DeviceConnectionRepository;
import com.ondemandmonitoring.order.enums.OrderStatus;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import com.ondemandmonitoring.weather.domain.MissionWeatherCheck;
import com.ondemandmonitoring.weather.repository.MissionWeatherCheckRepository;
import com.ondemandmonitoring.devicecheck.repository.PersistedPreDeviceCheckRepository;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.ondemandmonitoring.mission.service.impl.MissionRuntimeGate.requireOperationalCommandsAllowed;

@Service
@RequiredArgsConstructor
public class PersistedPreDeviceCheckService implements IPersistedPreDeviceCheckService {

    private static final long START_IDEMPOTENCY_WINDOW_SECONDS = 30;
    private static final double DEFAULT_MIN_PREFLIGHT_BATTERY_PERCENT = 30.0;
    private static final Set<PreDeviceCheckType> REMOVED_CHECKS = Set.of(
            PreDeviceCheckType.SATELLITE_SIGNAL,
            PreDeviceCheckType.POSITIONING_HEALTH,
            PreDeviceCheckType.COMPASS_HEALTH,
            PreDeviceCheckType.IMU_HEALTH,
            PreDeviceCheckType.HOME_POSITION,
            PreDeviceCheckType.ALTITUDE_SENSOR,
            PreDeviceCheckType.SYSTEM_SELF_CHECK,
            PreDeviceCheckType.CONNECTION_FAILSAFE,
            PreDeviceCheckType.RETURN_TO_HOME,
            PreDeviceCheckType.LOW_BATTERY_PROTECTION,
            PreDeviceCheckType.FLIGHT_GEOFENCE,
            PreDeviceCheckType.EMERGENCY_LANDING,
            PreDeviceCheckType.FLIGHT_ROUTE,
            PreDeviceCheckType.FLIGHT_LIMITS,
            PreDeviceCheckType.MISSION_ENERGY,
            PreDeviceCheckType.LANDING_DESTINATION,
            PreDeviceCheckType.RENTAL_BOOKING,
            PreDeviceCheckType.AIRCRAFT_PERMISSION,
            PreDeviceCheckType.AIRCRAFT_HANDOVER,
            PreDeviceCheckType.MAINTENANCE_STATUS,
            PreDeviceCheckType.MANAGEMENT_CONNECTION,
            PreDeviceCheckType.MEDIA_STORAGE);

    private static final List<PreDeviceCheckType> CHECKS = Arrays.stream(PreDeviceCheckType.values())
            .filter(check -> !REMOVED_CHECKS.contains(check))
            .toList();
    private static final Set<String> IGNORED_CHECK_TYPES = Set.of(
            "GAZEBO", "LOCAL_POSITION", "MODULES", "motor_esc_health",
            "satellite_signal", "positioning_health", "compass_health",
            "imu_health", "home_position", "altitude_sensor",
            "system_self_check", "connection_failsafe", "return_to_home",
            "low_battery_protection", "flight_geofence", "emergency_landing",
            "flight_route", "flight_limits", "mission_energy", "landing_destination",
            "rental_booking", "aircraft_permission", "aircraft_handover",
            "maintenance_status", "management_connection", "media_storage");
    private static final Set<String> CONNECTION_MANUAL_FALLBACK_TYPES = Set.of(
            "aircraft_connection", "command_link", "flight_controller",
            "aircraft_readiness", "monitoring_connection");

    static boolean isActiveCheckType(String checkType) {
        return !IGNORED_CHECK_TYPES.contains(checkType);
    }

    private final PersistedPreDeviceCheckRepository runRepository;
    private final MissionRepository missionRepository;
    private final DeviceConnectionRepository connectionRepository;
    private final DeviceTelemetryRepository telemetryRepository;
    private final AuthenticatedUserResolver authenticatedUserResolver;
    private final MissionWeatherCheckRepository weatherCheckRepository;

    @Transactional
    @Override
    @PreAuthorize("@missionAuthorizationService.canOperatePayload(#missionId)")
    public PersistedPreDeviceCheckResponse start(String missionId) {
        Mission mission = missionRepository.findByIdForUpdate(missionId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.MISSION_NOT_FOUND,
                        "Mission not found: " + missionId));
        requireOperationalCommandsAllowed(mission);
        if (mission.getStatus() != MissionStatus.CONNECTED
                && mission.getStatus() != MissionStatus.PREFLIGHT_CHECKING) {
            throw new ApiException(ErrorCode.MISSION_STATUS_INVALID, "Pre-device inspection requires a connected mission");
        }

        PersistedPreDeviceCheck activeRun = runRepository
                .findFirstByMissionIdOrderByCreatedAtDesc(missionId)
                .filter(run -> run.getStatus() == PreDeviceCheckStatus.CHECKING
                        && run.getStartedAt() != null
                        && run.getStartedAt().isAfter(
                                Instant.now().minusSeconds(START_IDEMPOTENCY_WINDOW_SECONDS)))
                .orElse(null);
        if (activeRun != null) {
            return PersistedPreDeviceCheckResponse.from(activeRun);
        }

        PersistedPreDeviceCheck run = new PersistedPreDeviceCheck();
        run.setMission(mission);
        run.setStatus(PreDeviceCheckStatus.CHECKING);
        run.setTotalChecks(CHECKS.size());
        run.setPassedChecks(0);
        run.setFailedChecks(0);
        run.setStartedAt(Instant.now());

        DeviceConnection connection = connectionRepository
                .findTopByMissionIdAndConnectionStatusOrderByConnectedAtDesc(missionId, "CONNECTED")
                .orElse(null);
        run.setDeviceConnection(connection);

        for (PreDeviceCheckType definition : CHECKS) {
            PersistedPreDeviceCheckItem item = new PersistedPreDeviceCheckItem();
            item.setPreDeviceCheck(run);
            item.setCheckType(definition.code());
            item.setCheckName(definition.displayName());
            item.setCheckLevel(definition.level());
            item.setDescription(definition.description());
            item.setCategory(definition.category());
            item.setRequired(definition.required());
            item.setSource(definition.source());
            evaluateAvailableEvidence(item, definition, mission, connection);
            run.getItems().add(item);
        }

        refreshRunStatus(run);
        return PersistedPreDeviceCheckResponse.from(runRepository.save(run));
    }

    @Transactional(readOnly = true)
    @Override
    @PreAuthorize("@missionAuthorizationService.canViewMission(#missionId)")
    public List<PersistedPreDeviceCheckResponse> history(String missionId) {
        ensureMission(missionId);

        return runRepository.findByMissionIdOrderByCreatedAtDesc(missionId)
                .stream()
                .map(PersistedPreDeviceCheckResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    @Override
    @PreAuthorize("@missionAuthorizationService.canViewMission(#missionId)")
    public PersistedPreDeviceCheckResponse current(String missionId) {
        ensureMission(missionId);

        return runRepository.findFirstByMissionIdOrderByCreatedAtDesc(missionId)
                .map(PersistedPreDeviceCheckResponse::from)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    @Override
    @PreAuthorize("@deviceCheckAuthorizationService.canViewPreCheck(#id)")
    public PersistedPreDeviceCheckResponse get(String id) {
        return PersistedPreDeviceCheckResponse.from(runRepository.findById(id)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "Preflight check not found: " + id)));
    }

    @Transactional
    @Override
    @PreAuthorize("@deviceCheckAuthorizationService.canInspectPreCheck(#id)")
    public PersistedPreDeviceCheckResponse update(String id, String type, PreDeviceCheckItemUpdateRequest request) {
        // Mission precedes the run lock, matching completion and safe-stop serialization.
        PersistedPreDeviceCheck existing = runRepository.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Preflight check not found: " + id));
        Mission mission = missionRepository.findByIdForUpdate(existing.getMission().getId())
                .orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
        requireOperationalCommandsAllowed(mission);
        PersistedPreDeviceCheck run = runRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "Preflight check not found: " + id));

        PersistedPreDeviceCheckItem item = run.getItems().stream()
                .filter(candidate -> type.equals(candidate.getCheckType()))
                .findFirst()
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "Unknown check type: " + type));

        if (isTerminal(item.getStatus()) && !isTerminal(request.getStatus())) {
            return PersistedPreDeviceCheckResponse.from(run);
        }

        boolean mediaFallback = PreDeviceCheckType.MEDIA_STORAGE.code().equals(item.getCheckType())
                && (item.getStatus() == PreDeviceItemStatus.FAILED
                || item.getStatus() == PreDeviceItemStatus.UNKNOWN
                || item.getStatus() == PreDeviceItemStatus.STALE);
        boolean batteryFallback = PreDeviceCheckType.BATTERY_HEALTH.code().equals(item.getCheckType())
                && (item.getStatus() == PreDeviceItemStatus.FAILED
                || item.getStatus() == PreDeviceItemStatus.UNKNOWN
                || item.getStatus() == PreDeviceItemStatus.STALE);
        boolean connectionFallback = CONNECTION_MANUAL_FALLBACK_TYPES.contains(item.getCheckType());
        if (item.getSource() != PreflightCheckSource.MANUAL
                && !mediaFallback && !batteryFallback && !connectionFallback) {
            throw new ApiException(ErrorCode.ACCESS_DENIED,
                    "Only manual inspection items can be confirmed by the operator");
        }
        if (batteryFallback) {
            double percent = parseBatteryPercent(request.getMeasuredValue());
            boolean operatorConfirmed = request.getMessage() != null
                    && request.getMessage().startsWith("Người vận hành xác nhận")
                    && (request.getStatus() == PreDeviceItemStatus.PASSED
                    || request.getStatus() == PreDeviceItemStatus.FAILED);
            if (operatorConfirmed) {
                item.setStatus(request.getStatus());
                item.setMeasuredValue(percent + "%");
                item.setMessage(request.getMessage() + " với mức pin " + percent + "%");
                item.setErrorCode(request.getStatus() == PreDeviceItemStatus.FAILED
                        ? "OPERATOR_REJECTED_BATTERY" : null);
            } else {
                evaluateManualBattery(item, run.getDeviceConnection() == null ? null : run.getDeviceConnection().getDevice(), percent);
                boolean automaticGazeboReading = request.getMessage() != null
                        && request.getMessage().startsWith("Hệ thống tự động");
                item.setMessage((automaticGazeboReading
                        ? "Pin Gazebo tự động: "
                        : "Người vận hành nhập mức pin thủ công: ") + percent + "%");
            }
            item.setCheckedAt(Instant.now());
            item.setExpiresAt(Instant.now().plusSeconds(300));
            item.setCheckedBy(authenticatedUserResolver.getCurrentUserId());
            refreshRunStatus(run);
            return PersistedPreDeviceCheckResponse.from(run);
        }
        if (request.getStatus() != PreDeviceItemStatus.PASSED
                && request.getStatus() != PreDeviceItemStatus.FAILED
                && request.getStatus() != PreDeviceItemStatus.NOT_APPLICABLE) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Manual inspection result must be PASSED, FAILED or NOT_APPLICABLE");
        }
        if (request.getStatus() == PreDeviceItemStatus.FAILED
                && (request.getMessage() == null || request.getMessage().isBlank())) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "A failure reason is required for a failed manual inspection");
        }
        if (request.getStatus() == PreDeviceItemStatus.NOT_APPLICABLE && Boolean.TRUE.equals(item.getRequired())) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "A required inspection cannot be marked not applicable");
        }

        item.setStatus(request.getStatus());
        item.setMessage(request.getMessage());
        item.setMeasuredValue(request.getMeasuredValue());
        item.setCheckedAt(Instant.now());
        item.setExpiresAt(Instant.now().plusSeconds(900));
        item.setCheckedBy(authenticatedUserResolver.getCurrentUserId());

        refreshRunStatus(run);

        if (run.getStatus() != PreDeviceCheckStatus.CHECKING) {
            run.setCompletedAt(Instant.now());
        }

        return PersistedPreDeviceCheckResponse.from(run);
    }

    /** Internal evidence path used by trusted backend probes; intentionally not exposed by a controller. */
    @Transactional
    @Override
    public PersistedPreDeviceCheckResponse recordSystemResult(
            String id, String type, PreDeviceItemStatus status, String message) {
        PersistedPreDeviceCheck run = runRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Preflight check not found: " + id));
        PersistedPreDeviceCheckItem item = run.getItems().stream()
                .filter(candidate -> type.equals(candidate.getCheckType())).findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Unknown check type: " + type));
        if (item.getSource() == PreflightCheckSource.MANUAL) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Manual inspections require operator confirmation");
        }
        item.setStatus(status);
        item.setMessage(message);
        item.setCheckedAt(Instant.now());
        item.setExpiresAt(Instant.now().plusSeconds(300));
        refreshRunStatus(run);
        return PersistedPreDeviceCheckResponse.from(run);
    }

    @Transactional
    @Override
    @PreAuthorize("@deviceCheckAuthorizationService.canInspectPreCheck(#id)")
    public PersistedPreDeviceCheckResponse recheck(String id, String type) {
        PersistedPreDeviceCheck run = runRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Preflight check not found: " + id));
        PersistedPreDeviceCheckItem item = run.getItems().stream()
                .filter(candidate -> type.equals(candidate.getCheckType())).findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Unknown check type: " + type));
        if (!PreDeviceCheckType.BATTERY_HEALTH.code().equals(type)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "This check does not support an individual retry");
        }
        DeviceConnection connection = run.getDeviceConnection();
        if (connection == null) {
            connection = connectionRepository.findTopByMissionIdAndConnectionStatusOrderByConnectedAtDesc(
                    run.getMission().getId(), "CONNECTED").orElse(null);
            run.setDeviceConnection(connection);
        }
        DeviceTelemetry telemetry = connection == null ? null : telemetryRepository
                .findTopByDeviceConnectionIdOrderByRecordedAtDesc(connection.getId()).orElse(null);
        clearEvidence(item);
        Instant now = Instant.now();
        if (telemetry != null && telemetry.getRecordedAt() != null
                && telemetry.getRecordedAt().isBefore(now.minusSeconds(10))) {
            item.setStatus(PreDeviceItemStatus.STALE);
            item.setMessage("Dữ liệu pin từ Gazebo đã quá hạn");
            item.setErrorCode("BATTERY_TELEMETRY_STALE");
            item.setMeasuredValue(telemetry.getBatteryPercent() == null
                    ? telemetry.getRecordedAt().toString()
                    : telemetry.getBatteryPercent() + "%");
        } else {
            evaluateBattery(item, connection == null ? null : connection.getDevice(), telemetry);
        }
        item.setCheckedAt(now);
        item.setExpiresAt(now.plusSeconds(300));
        refreshRunStatus(run);
        return PersistedPreDeviceCheckResponse.from(run);
    }

    private boolean isTerminal(PreDeviceItemStatus status) {
        return status == PreDeviceItemStatus.PASSED || status == PreDeviceItemStatus.FAILED
                || status == PreDeviceItemStatus.NOT_APPLICABLE;
    }

    private boolean blocksFlight(PreDeviceItemStatus status) {
        return status == PreDeviceItemStatus.FAILED || status == PreDeviceItemStatus.UNKNOWN
                || status == PreDeviceItemStatus.STALE || status == PreDeviceItemStatus.PENDING
                || status == PreDeviceItemStatus.CHECKING;
    }

    private void refreshRunStatus(PersistedPreDeviceCheck run) {
        List<PersistedPreDeviceCheckItem> activeItems = run.getItems().stream()
                .filter(i -> isActiveCheckType(i.getCheckType())).toList();
        int passed = (int) activeItems.stream().filter(i -> i.getStatus() == PreDeviceItemStatus.PASSED
                || i.getStatus() == PreDeviceItemStatus.NOT_APPLICABLE).count();
        int failed = (int) activeItems.stream().filter(i -> i.getStatus() == PreDeviceItemStatus.FAILED).count();
        boolean failedRequired = activeItems.stream().anyMatch(i -> Boolean.TRUE.equals(i.getRequired())
                && i.getStatus() == PreDeviceItemStatus.FAILED);
        boolean allResolved = activeItems.stream().allMatch(i -> i.getStatus() == PreDeviceItemStatus.PASSED
                || i.getStatus() == PreDeviceItemStatus.WARNING || i.getStatus() == PreDeviceItemStatus.NOT_APPLICABLE);
        run.setTotalChecks(activeItems.size()); run.setPassedChecks(passed); run.setFailedChecks(failed);
        run.setStatus(failedRequired ? PreDeviceCheckStatus.FAILED : allResolved ? PreDeviceCheckStatus.PASSED : PreDeviceCheckStatus.CHECKING);
        if (run.getStatus() != PreDeviceCheckStatus.CHECKING) run.setCompletedAt(Instant.now());
    }

    private void evaluateAvailableEvidence(PersistedPreDeviceCheckItem item, PreDeviceCheckType definition,
                                           Mission mission, DeviceConnection connection) {
        if (definition.source() == PreflightCheckSource.MANUAL) {
            item.setStatus(PreDeviceItemStatus.PENDING);
            item.setMessage("Chờ người vận hành xác nhận");
            return;
        }
        Instant now = Instant.now();
        item.setCheckedAt(now);
        item.setExpiresAt(now.plusSeconds(300));
        Device device = connection == null ? null : connection.getDevice();
        DeviceTelemetry telemetry = connection == null ? null : telemetryRepository
                .findTopByDeviceConnectionIdOrderByRecordedAtDesc(connection.getId()).orElse(null);

        switch (definition) {
            case AIRCRAFT_CONNECTION, MONITORING_CONNECTION -> {
                if (connection == null || !Boolean.TRUE.equals(connection.getTelemetryActive())) {
                    fail(item, "Không có kết nối giám sát đang hoạt động", "CONNECTION_UNAVAILABLE");
                } else if (telemetry == null) {
                    unknown(item, "Chưa nhận được dữ liệu telemetry", "TELEMETRY_MISSING");
                } else if (telemetry.getRecordedAt().isBefore(now.minusSeconds(10))) {
                    item.setStatus(PreDeviceItemStatus.STALE);
                    item.setMessage("Dữ liệu telemetry đã quá hạn");
                    item.setErrorCode("TELEMETRY_STALE");
                    item.setMeasuredValue(telemetry.getRecordedAt().toString());
                } else if (!Boolean.TRUE.equals(telemetry.getConnected())) {
                    fail(item, "Thiết bị báo mất kết nối", "AIRCRAFT_DISCONNECTED");
                } else {
                    pass(item, "Kết nối đang hoạt động", telemetry.getRecordedAt().toString());
                }
            }
            case BATTERY_HEALTH -> evaluateBattery(item, device, telemetry);
            case WEATHER_CONDITIONS -> {
                MissionWeatherCheck weather = weatherCheckRepository
                        .findFirstByMissionIdOrderByCreatedAtDesc(mission.getId()).orElse(null);
                if (weather == null) unknown(item, "Chưa có kết quả đánh giá thời tiết", "WEATHER_DATA_MISSING");
                else if (weather.getCheckedAt().isBefore(now.minusSeconds(900))) {
                    item.setStatus(PreDeviceItemStatus.STALE);
                    item.setMessage("Kết quả thời tiết đã quá hạn");
                    item.setErrorCode("WEATHER_DATA_STALE");
                } else if (Boolean.TRUE.equals(weather.getSafeToFly())) {
                    pass(item, weather.getSummary(), "Gió " + weather.getWindSpeedMps() + " m/s; tầm nhìn " + weather.getVisibilityKm() + " km");
                    item.setExpiresAt(weather.getCheckedAt().plusSeconds(900));
                } else fail(item, weather.getSummary(), "WEATHER_UNSAFE");
            }
            case AIRSPACE_PERMISSION -> {
                if (mission.getFlightPermitStatus() == FlightPermitStatus.APPROVED) pass(item, "Giấy phép bay đã được xác nhận", null);
                else fail(item, "Giấy phép bay chưa được xác nhận", "FLIGHT_PERMISSION_REQUIRED");
            }
            case FLIGHT_ROUTE, FLIGHT_LIMITS, LANDING_DESTINATION -> {
                if (mission.getOrder() != null && mission.getOrder().getExecutionPlanStatus()
                        == com.ondemandmonitoring.order.enums.ExecutionPlanStatus.FINALIZED)
                    pass(item, "Kế hoạch bay đã được chốt", String.valueOf(mission.getOrder().getExecutionPlanRevision()));
                else unknown(item, "Chưa có kế hoạch bay đã chốt", "FLIGHT_PLAN_MISSING");
            }
            case RENTAL_BOOKING -> {
                OrderStatus status = mission.getOrder() == null ? null : mission.getOrder().getOrderStatus();
                if (status == OrderStatus.APPROVED || status == OrderStatus.IN_PROGRESS) pass(item, "Đơn thuê hợp lệ", status.name());
                else fail(item, "Đơn thuê không ở trạng thái được phép vận hành", "BOOKING_INVALID");
            }
            case AIRCRAFT_PERMISSION -> pass(item, "Người dùng đã được backend xác thực quyền vận hành", null);
            case MAINTENANCE_STATUS -> {
                if (device == null) unknown(item, "Không xác định được thiết bị", "DEVICE_MISSING");
                else if (device.getStatus() == DeviceStatus.MAINTENANCE || device.getStatus() == DeviceStatus.OUT_OF_SERVICE || device.getStatus() == DeviceStatus.OFFLINE)
                    fail(item, "Thiết bị không ở trạng thái khai thác", "DEVICE_UNAVAILABLE");
                else pass(item, "Thiết bị không bị khóa bảo trì", device.getStatus().name());
            }
            case MANAGEMENT_CONNECTION -> pass(item, "Hệ thống quản lý đang xử lý phiên kiểm tra", null);
            default -> unknown(item, "Nguồn dữ liệu hiện tại chưa đủ để xác minh", "EVIDENCE_UNAVAILABLE");
        }
    }

    private void evaluateBattery(PersistedPreDeviceCheckItem item, Device device, DeviceTelemetry telemetry) {
        if (telemetry == null || telemetry.getBatteryPercent() == null) {
            unknown(item, "Chưa có telemetry pin", "BATTERY_TELEMETRY_MISSING");
            return;
        }
        double threshold = batteryThreshold(device);
        item.setMeasuredValue(telemetry.getBatteryPercent() + "%");
        if (telemetry.getBatteryPercent() >= threshold) pass(item, "Pin đáp ứng ngưỡng của thiết bị", item.getMeasuredValue());
        else fail(item, "Pin thấp hơn ngưỡng vận hành", "BATTERY_BELOW_LIMIT");
    }

    private double parseBatteryPercent(String value) {
        try {
            double percent = Double.parseDouble(value == null ? "" : value.replace("%", "").trim());
            if (!Double.isFinite(percent) || percent < 0 || percent > 100) throw new NumberFormatException();
            return percent;
        } catch (NumberFormatException exception) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Phần trăm pin phải nằm trong khoảng 0 đến 100");
        }
    }

    private void evaluateManualBattery(PersistedPreDeviceCheckItem item, Device device, double percent) {
        item.setMeasuredValue(percent + "%");
        double threshold = batteryThreshold(device);
        if (percent >= threshold) {
            pass(item, "Mức pin nhập tay đáp ứng ngưỡng của thiết bị", item.getMeasuredValue());
        } else {
            fail(item, "Mức pin nhập tay thấp hơn ngưỡng vận hành", "BATTERY_BELOW_LIMIT");
        }
    }

    private double batteryThreshold(Device device) {
        Object configured = device == null
                || device.getDeviceModel() == null
                || device.getDeviceModel().getSpecsMetadata() == null
                ? null
                : device.getDeviceModel().getSpecsMetadata().get("minPreflightBatteryPercent");
        return configured instanceof Number number
                ? number.doubleValue()
                : DEFAULT_MIN_PREFLIGHT_BATTERY_PERCENT;
    }

    private void clearEvidence(PersistedPreDeviceCheckItem item) {
        item.setMeasuredValue(null); item.setMessage(null); item.setErrorCode(null); item.setCheckedBy(null);
    }

    private void pass(PersistedPreDeviceCheckItem item, String message, String measured) {
        item.setStatus(PreDeviceItemStatus.PASSED); item.setMessage(message); item.setMeasuredValue(measured);
    }
    private void fail(PersistedPreDeviceCheckItem item, String message, String code) {
        item.setStatus(PreDeviceItemStatus.FAILED); item.setMessage(message); item.setErrorCode(code);
    }
    private void unknown(PersistedPreDeviceCheckItem item, String message, String code) {
        item.setStatus(PreDeviceItemStatus.UNKNOWN); item.setMessage(message); item.setErrorCode(code);
    }

    private void ensureMission(String id) {
        if (!missionRepository.existsById(id)) {
            throw new ApiException(
                    ErrorCode.MISSION_NOT_FOUND,
                    "Mission not found: " + id);
        }
    }
}
