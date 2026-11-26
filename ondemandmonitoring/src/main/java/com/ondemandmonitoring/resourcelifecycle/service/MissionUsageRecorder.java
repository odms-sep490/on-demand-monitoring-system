package com.ondemandmonitoring.resourcelifecycle.service;

import com.ondemandmonitoring.device.domain.DeviceTelemetry;
import com.ondemandmonitoring.device.repository.DeviceTelemetryRepository;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.domain.MissionDeviceAssignment;
import com.ondemandmonitoring.mission.domain.MissionStaffAssignment;
import com.ondemandmonitoring.mission.enums.MissionStaffAssignmentStatus;
import com.ondemandmonitoring.mission.repository.MissionDeviceAssignmentRepository;
import com.ondemandmonitoring.mission.repository.MissionStaffAssignmentRepository;
import com.ondemandmonitoring.resourcelifecycle.FlightTimeCalculator;
import com.ondemandmonitoring.resourcelifecycle.entity.ResourceUsageRecordEntity;
import com.ondemandmonitoring.resourcelifecycle.repository.ResourceUsageRecordRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Turns a finished Mission into verified usage records so drone flight hours and staff work hours
 * accumulate automatically instead of depending on manual usage entries.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MissionUsageRecorder {
    static final String SYSTEM_ACTOR = "SYSTEM_MISSION_COMPLETION";
    static final String SOURCE_MISSION = "MISSION_ACTUAL";
    static final String SOURCE_TELEMETRY = "TELEMETRY_ACTUAL";
    private static final double AIRBORNE_ALTITUDE_METERS = 1.0;
    private static final int TELEMETRY_SAMPLE_LIMIT = 20_000;

    private final ResourceUsageRecordRepository usageRepository;
    private final MissionDeviceAssignmentRepository deviceAssignments;
    private final MissionStaffAssignmentRepository staffAssignments;
    private final DeviceTelemetryRepository telemetryRepository;
    private final MaintenanceLifecycleService maintenanceLifecycleService;
    private final ResourceAuditService auditService;
    private final FlightTimeCalculator flightTimeCalculator = new FlightTimeCalculator();

    /** Runs inside the caller's transaction. Idempotent: a resource already recorded for this mission is skipped. */
    public void recordFinishedMission(Mission mission) {
        Instant start = mission.getActualStartAt();
        Instant end = mission.getActualEndAt();
        if (start == null || end == null || !start.isBefore(end)) {
            log.info("Mission {} has no valid actual start/end; usage not recorded", mission.getId());
            return;
        }
        Set<String> touchedDevices = new HashSet<>();
        for (MissionDeviceAssignment assignment : deviceAssignments
                .findAllByMissionIdAndIsCurrentTrueOrderByCreatedAtDesc(mission.getId())) {
            if (assignment.getDevice() == null) continue;
            String deviceId = assignment.getDevice().getId();
            if (!touchedDevices.add(deviceId)) continue;
            if (recordDevice(mission, assignment, deviceId, start, end)) evaluateMaintenanceAfterCommit(deviceId);
        }
        Set<String> touchedStaff = new HashSet<>();
        // Every current staff member who did not decline worked the mission, even without a formal accept.
        for (MissionStaffAssignment assignment : staffAssignments.findAllByMissionIdAndIsCurrentTrue(mission.getId())) {
            if (assignment.getStaff() == null || assignment.getResponseStatus() == MissionStaffAssignmentStatus.REJECTED) continue;
            String staffId = assignment.getStaff().getId();
            if (!touchedStaff.add(staffId)) continue;
            String role = assignment.getAssignedRole() == null ? null : assignment.getAssignedRole().name();
            save(mission, "STAFF", staffId, assignment.getId(), role, start, end,
                    Duration.between(start, end).getSeconds(), null, null, SOURCE_MISSION);
        }
    }

    /**
     * Maintenance evaluation runs in its own transaction after the mission commits, so a policy or
     * ticket failure can never roll back mission completion.
     */
    private void evaluateMaintenanceAfterCommit(String deviceId) {
        Runnable evaluate = () -> {
            try {
                maintenanceLifecycleService.evaluateAndTriggerIfBreached(deviceId);
            } catch (RuntimeException e) {
                log.error("Maintenance evaluation failed for device {}", deviceId, e);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evaluate.run();
                }
            });
        } else {
            evaluate.run();
        }
    }

    private boolean recordDevice(Mission mission, MissionDeviceAssignment assignment, String deviceId, Instant start, Instant end) {
        long windowSeconds = Duration.between(start, end).getSeconds();
        long seconds = windowSeconds;
        Integer cycles = 1;
        Double distance = null;
        String source = SOURCE_MISSION;
        List<DeviceTelemetry> telemetry = telemetryRepository.findLatestForMissionDevice(
                mission.getId(), deviceId, PageRequest.of(0, TELEMETRY_SAMPLE_LIMIT));
        if (telemetry.size() >= 2) {
            var result = flightTimeCalculator.calculate(telemetry.stream().map(MissionUsageRecorder::sample).toList(), start, end);
            if (result.actualSeconds() > 0) {
                seconds = Math.min(result.actualSeconds(), windowSeconds);
                cycles = Math.max(1, result.takeoffs());
                distance = result.measuredDistanceMeters();
                source = SOURCE_TELEMETRY;
            }
        }
        return save(mission, "DEVICE", deviceId, assignment.getId(), null, start, end, seconds, cycles, distance, source);
    }

    private static FlightTimeCalculator.Sample sample(DeviceTelemetry t) {
        Boolean airborne = t.getRelativeAltitudeM() == null ? null : t.getRelativeAltitudeM() > AIRBORNE_ALTITUDE_METERS;
        return new FlightTimeCalculator.Sample(t.getRecordedAt(), airborne, airborne, t.getConnected(),
                t.getLatitude(), t.getLongitude(), t.getRelativeAltitudeM());
    }

    private boolean save(Mission mission, String kind, String resourceId, String assignmentId, String role,
            Instant start, Instant end, long seconds, Integer cycles, Double distance, String source) {
        String key = "mission:" + mission.getId() + ":" + kind + ":" + resourceId;
        if (usageRepository.findByIdempotencyKey(key).isPresent()) return false;
        Instant now = Instant.now();
        ResourceUsageRecordEntity entity = new ResourceUsageRecordEntity();
        entity.setId(UUID.randomUUID());
        entity.setResourceKind(kind);
        entity.setResourceId(resourceId);
        entity.setMissionId(mission.getId());
        entity.setAssignmentId(assignmentId);
        entity.setRole(role);
        entity.setStartedAt(start);
        entity.setEndedAt(end);
        entity.setActualSeconds(Math.max(0L, seconds));
        entity.setEstimatedSeconds(0L);
        entity.setCycles(cycles);
        entity.setDistanceM(distance);
        entity.setSource(source);
        entity.setStatus("VERIFIED");
        entity.setEvidenceReference("mission:" + mission.getId());
        entity.setReason("Recorded automatically when the mission finished");
        entity.setIdempotencyKey(key);
        entity.setCreatedBy(SYSTEM_ACTOR);
        entity.setCreatedAt(now);
        entity.setVerifiedBy(SYSTEM_ACTOR);
        entity.setVerifiedAt(now);
        usageRepository.save(entity);
        auditService.record(SYSTEM_ACTOR, "USAGE_RECORD_AUTO_CREATED", entity.getId().toString(), null,
                Map.of("resourceKind", kind, "resourceId", resourceId, "missionId", mission.getId(),
                        "actualSeconds", entity.getActualSeconds(), "source", source),
                entity.getReason());
        return true;
    }
}
