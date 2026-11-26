package com.ondemandmonitoring.resourcelifecycle.service;

import com.ondemandmonitoring.device.domain.Device;
import com.ondemandmonitoring.device.enums.DeviceStatus;
import com.ondemandmonitoring.device.repository.DeviceRepository;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.domain.ResourceTimeLock;
import com.ondemandmonitoring.mission.repository.ResourceTimeLockRepository;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.DroneHealthResponse;
import com.ondemandmonitoring.resourcelifecycle.LifecycleContracts.ScheduleForecastItem;
import com.ondemandmonitoring.resourcelifecycle.MaintenanceRuleEngine;
import com.ondemandmonitoring.resourcelifecycle.entity.ResourceMaintenanceCycleEntity;
import com.ondemandmonitoring.resourcelifecycle.entity.ResourcePolicyEntity;
import com.ondemandmonitoring.resourcelifecycle.entity.ResourceUsageRecordEntity;
import com.ondemandmonitoring.resourcelifecycle.repository.ResourceMaintenanceCycleRepository;
import com.ondemandmonitoring.resourcelifecycle.repository.ResourcePolicyRepository;
import com.ondemandmonitoring.resourcelifecycle.repository.ResourceUsageRecordRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes real-time drone lifecycle health strictly from verified database records,
 * active maintenance policies, and completed checkpoints — never requiring PX4/Gazebo.
 */
@Service
@RequiredArgsConstructor
public class DroneHealthService {

    public static final String BATTERY_UNSUPPORTED_NOTE =
            "Chưa hỗ trợ theo dõi từng viên pin riêng biệt (chưa có entity battery pack; không tự suy ra SOH).";

    private final DeviceRepository deviceRepository;
    private final ResourceUsageRecordRepository usageRepository;
    private final ResourcePolicyRepository policyRepository;
    private final ResourceMaintenanceCycleRepository cycleRepository;
    private final ResourceTimeLockRepository lockRepository;
    private final LifecycleJsonHelper jsonHelper;

    private final MaintenanceRuleEngine maintenanceRuleEngine = new MaintenanceRuleEngine();

    @Transactional(readOnly = true)
    public List<DroneHealthResponse> listAllDroneHealth() {
        return deviceRepository.findAll().stream()
                .sorted(Comparator.comparing(Device::getName, Comparator.nullsLast(String::compareToIgnoreCase)))
                .map(device -> evaluateDevice(device, null))
                .toList();
    }

    @Transactional(readOnly = true)
    public DroneHealthResponse getDroneHealth(String deviceId) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new IllegalArgumentException("Device not found: " + deviceId));
        return evaluateDevice(device, null);
    }

    @Transactional(readOnly = true)
    public DroneHealthResponse evaluateDevice(Device device, ResourcePolicyEntity overridePolicy) {
        String modelId = device.getDeviceModel() != null ? device.getDeviceModel().getId() : null;
        String modelName = device.getDeviceModel() != null ? device.getDeviceModel().getModelName() : null;

        ResourcePolicyEntity policy = overridePolicy != null
                ? overridePolicy
                : (modelId == null
                        ? null
                        : policyRepository.findFirstByFamilyAndTargetIdAndActiveTrue("MAINTENANCE", modelId)
                                .orElse(null));

        List<ResourceUsageRecordEntity> verifiedRecords = usageRepository
                .findByResourceKindAndResourceIdAndStatusOrderByStartedAtAsc("DEVICE", device.getId(), "VERIFIED");

        long lifetimeActualSeconds = 0L;
        long lifetimeEstimatedSeconds = 0L;
        long lifetimeCycles = 0L;
        double measuredDistance = 0.0;
        boolean hasDistance = false;
        boolean hasTelemetry = false;
        boolean hasManual = false;

        for (ResourceUsageRecordEntity record : verifiedRecords) {
            lifetimeActualSeconds += record.getActualSeconds();
            lifetimeEstimatedSeconds += record.getEstimatedSeconds();
            if (record.getCycles() != null) {
                lifetimeCycles += record.getCycles();
            }
            if (record.getDistanceM() != null) {
                measuredDistance += record.getDistanceM();
                hasDistance = true;
            }
            if ("TELEMETRY_ACTUAL".equals(record.getSource())) {
                hasTelemetry = true;
            } else if ("VERIFIED_MANUAL".equals(record.getSource())) {
                hasManual = true;
            }
        }

        // Estimated usage from past/completed time locks when no verified usage exists for those intervals
        long estimatedFromLocks = computeUnverifiedLockSeconds(device.getId(), verifiedRecords);
        lifetimeEstimatedSeconds += estimatedFromLocks;

        List<ResourceMaintenanceCycleEntity> deviceCycles = cycleRepository
                .findByDeviceIdOrderByDetectedAtDesc(device.getId());

        Optional<ResourceMaintenanceCycleEntity> openCycle = deviceCycles.stream()
                .filter(cycle -> cycle.getCheckpointAt() == null)
                .findFirst();

        Optional<ResourceMaintenanceCycleEntity> latestCheckpointForPolicy = policy == null
                ? Optional.empty()
                : deviceCycles.stream()
                        .filter(cycle -> policy.getId().equals(cycle.getPolicyId()) && cycle.getCheckpointAt() != null)
                        .max(Comparator.comparing(ResourceMaintenanceCycleEntity::getCheckpointAt));

        long checkpointSeconds = latestCheckpointForPolicy
                .map(cycle -> cycle.getCheckpointSeconds() == null ? 0L : cycle.getCheckpointSeconds())
                .orElse(0L);
        long checkpointCycles = latestCheckpointForPolicy
                .map(cycle -> cycle.getCheckpointCycles() == null ? 0L : cycle.getCheckpointCycles())
                .orElse(0L);
        Instant lastMaintenanceAt = latestCheckpointForPolicy
                .map(ResourceMaintenanceCycleEntity::getCheckpointAt)
                .orElse(null);

        boolean hasVerifiedUsage = !verifiedRecords.isEmpty();
        Long sinceMaintenanceSeconds = hasVerifiedUsage
                ? Math.max(0L, lifetimeActualSeconds - checkpointSeconds)
                : null;
        long sinceMaintenanceCycles = Math.max(0L, lifetimeCycles - checkpointCycles);

        int daysSinceMaintenance = lastMaintenanceAt == null
                ? 0
                : (int) Math.max(0L, Duration.between(lastMaintenanceAt, Instant.now()).toDays());

        MaintenanceRuleEngine.Health engineHealth = evaluatePolicy(
                policy, sinceMaintenanceSeconds, (int) sinceMaintenanceCycles, daysSinceMaintenance);

        List<String> reasons = new ArrayList<>(engineHealth.reasons());
        boolean blocked = engineHealth.blocked();
        if (openCycle.isPresent()) {
            blocked = true;
            if (!reasons.contains("OPEN_MAINTENANCE_CYCLE")) {
                reasons.add("OPEN_MAINTENANCE_CYCLE");
            }
        }
        if (device.getStatus() == DeviceStatus.MAINTENANCE || device.getStatus() == DeviceStatus.OUT_OF_SERVICE) {
            blocked = true;
            if (!reasons.contains("DEVICE_STATUS_" + device.getStatus().name())) {
                reasons.add("DEVICE_STATUS_" + device.getStatus().name());
            }
        }

        String usageSource = resolveUsageSource(hasTelemetry, hasManual, lifetimeEstimatedSeconds > 0L);

        return new DroneHealthResponse(
                device.getId(),
                device.getName(),
                device.getSerialNumber(),
                modelId,
                modelName,
                device.getStatus() == null ? "UNKNOWN" : device.getStatus().name(),
                openCycle.isPresent() ? "MAINTENANCE_REQUIRED" : engineHealth.status(),
                hasVerifiedUsage ? lifetimeActualSeconds : null,
                lifetimeEstimatedSeconds,
                sinceMaintenanceSeconds,
                sinceMaintenanceCycles,
                lifetimeCycles,
                hasDistance ? measuredDistance : null,
                engineHealth.remainingSeconds(),
                engineHealth.remainingCycles(),
                engineHealth.remainingDays(),
                engineHealth.utilization(),
                policy == null ? null : policy.getId(),
                policy == null ? null : policy.getName(),
                policy == null ? 0L : policy.getRevision(),
                usageSource,
                blocked,
                openCycle.map(ResourceMaintenanceCycleEntity::getTicketId).orElse(null),
                openCycle.map(ResourceMaintenanceCycleEntity::getId).orElse(null),
                lastMaintenanceAt,
                List.copyOf(reasons),
                BATTERY_UNSUPPORTED_NOTE);
    }

    @Transactional(readOnly = true)
    public List<ScheduleForecastItem> computeScheduleBasedForecasts() {
        Instant now = Instant.now();
        List<ResourceTimeLock> activeLocks = lockRepository.findAll().stream()
                .filter(lock -> "DEVICE".equals(lock.getResourceType())
                        && lock.getReleasedAt() == null
                        && lock.getEndTime() != null
                        && lock.getEndTime().isAfter(now))
                .toList();

        List<ScheduleForecastItem> forecasts = new ArrayList<>();
        for (Device device : deviceRepository.findAll()) {
            DroneHealthResponse health = evaluateDevice(device, null);
            long scheduledSeconds = activeLocks.stream()
                    .filter(lock -> device.getId().equals(lock.getResourceId()))
                    .mapToLong(lock -> {
                        Instant effectiveStart = lock.getStartTime().isBefore(now) ? now : lock.getStartTime();
                        return Math.max(0L, Duration.between(effectiveStart, lock.getEndTime()).getSeconds());
                    })
                    .sum();
            Long remainingAfter = health.remainingSeconds() == null
                    ? null
                    : Math.max(0L, health.remainingSeconds() - scheduledSeconds);
            boolean projectedBreach = health.blocked()
                    || (health.remainingSeconds() != null && scheduledSeconds > health.remainingSeconds());

            forecasts.add(new ScheduleForecastItem(
                    device.getId(),
                    device.getName(),
                    health.sinceMaintenanceSeconds(),
                    scheduledSeconds,
                    remainingAfter,
                    projectedBreach,
                    "SCHEDULE_BASED_FORECAST"));
        }
        return forecasts;
    }

    public MaintenanceRuleEngine.Health evaluatePolicy(
            ResourcePolicyEntity policy,
            Long verifiedSeconds,
            int cyclesSinceMaintenance,
            int daysSinceMaintenance) {
        if (policy == null) {
            return maintenanceRuleEngine.evaluate(null, 0, 0, null, null, null, 60, 80);
        }
        Map<String, Object> rules = jsonHelper.toMap(policy.getRulesJson());
        Long maxSeconds = jsonHelper.readLong(rules, "maxFlightSeconds");
        if (maxSeconds == null) {
            Double maxHours = jsonHelper.readDouble(rules, "maxFlightHours");
            if (maxHours != null && maxHours > 0.0) {
                maxSeconds = Math.round(maxHours * 3600.0);
            }
        }
        Integer maxCycles = jsonHelper.readInt(rules, "maxFlightCycles");
        Integer maxDays = jsonHelper.readInt(rules, "maxCalendarDays");
        Integer maxPreflightFailures = jsonHelper.readInt(rules, "maxPreflightFailures");
        Integer maxIncidents = jsonHelper.readInt(rules, "maxIncidents");
        int monitorPercent = Optional.ofNullable(jsonHelper.readInt(rules, "monitorPercent")).orElse(60);
        int warningPercent = Optional.ofNullable(jsonHelper.readInt(rules, "warningPercent")).orElse(80);

        return maintenanceRuleEngine.evaluate(
                verifiedSeconds,
                cyclesSinceMaintenance,
                daysSinceMaintenance,
                0,
                0,
                maxSeconds,
                maxCycles,
                maxDays,
                maxPreflightFailures,
                maxIncidents,
                monitorPercent,
                warningPercent);
    }

    private long computeUnverifiedLockSeconds(String deviceId, List<ResourceUsageRecordEntity> verifiedRecords) {
        List<ResourceTimeLock> deviceLocks = lockRepository.findAll().stream()
                .filter(lock -> "DEVICE".equals(lock.getResourceType())
                        && deviceId.equals(lock.getResourceId())
                        && lock.getStartTime() != null
                        && lock.getEndTime() != null
                        && lock.getStartTime().isBefore(lock.getEndTime())
                        && lock.getEndTime().isBefore(Instant.now()))
                .toList();

        long estimated = 0L;
        for (ResourceTimeLock lock : deviceLocks) {
            Mission mission = lock.getMission();
            String missionId = mission == null ? null : mission.getId();
            boolean coveredByVerified = missionId != null && verifiedRecords.stream()
                    .anyMatch(record -> missionId.equals(record.getMissionId()));
            if (!coveredByVerified) {
                estimated += Duration.between(lock.getStartTime(), lock.getEndTime()).getSeconds();
            }
        }
        return estimated;
    }

    private String resolveUsageSource(boolean hasTelemetry, boolean hasManual, boolean hasEstimated) {
        if (hasTelemetry && (hasManual || hasEstimated)) {
            return "MIXED";
        }
        if (hasTelemetry) {
            return "TELEMETRY_ACTUAL";
        }
        if (hasManual && hasEstimated) {
            return "MIXED";
        }
        if (hasManual) {
            return "VERIFIED_MANUAL";
        }
        if (hasEstimated) {
            return "ASSIGNMENT_ESTIMATED";
        }
        return "UNKNOWN";
    }
}
