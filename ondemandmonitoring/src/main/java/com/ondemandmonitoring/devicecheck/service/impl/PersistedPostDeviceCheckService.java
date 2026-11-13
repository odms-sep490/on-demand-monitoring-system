package com.ondemandmonitoring.devicecheck.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.devicecheck.domain.PersistedPostDeviceCheck;
import com.ondemandmonitoring.devicecheck.domain.PersistedPostDeviceCheckItem;
import com.ondemandmonitoring.devicecheck.dto.request.DeviceCheckItemUpdateRequest;
import com.ondemandmonitoring.devicecheck.dto.response.PersistedPostDeviceCheckResponse;
import com.ondemandmonitoring.devicecheck.enums.DeviceCheckItemStatus;
import com.ondemandmonitoring.devicecheck.enums.DeviceCheckLevel;
import com.ondemandmonitoring.devicecheck.enums.DeviceCheckStatus;
import com.ondemandmonitoring.devicecheck.repository.PersistedPostDeviceCheckRepository;
import com.ondemandmonitoring.devicecheck.service.IPersistedPostDeviceCheckService;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.domain.MissionDeviceAssignment;
import com.ondemandmonitoring.mission.dto.request.PostFlightStatusRequest;
import com.ondemandmonitoring.mission.enums.DeviceRole;
import com.ondemandmonitoring.mission.enums.MissionDeviceAssignmentStatus;
import com.ondemandmonitoring.mission.repository.MissionDeviceAssignmentRepository;
import com.ondemandmonitoring.mission.enums.InspectionResult;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class PersistedPostDeviceCheckService implements IPersistedPostDeviceCheckService {

    private static final List<Definition> CHECKS = List.of(
            new Definition("a1", "Airframe condition", DeviceCheckLevel.CRITICAL),
            new Definition("a2", "Frame and landing gear", DeviceCheckLevel.CRITICAL),
            new Definition("p1", "Motors", DeviceCheckLevel.CRITICAL),
            new Definition("p2", "Propellers", DeviceCheckLevel.CRITICAL),
            new Definition("e1", "Landing battery", DeviceCheckLevel.CRITICAL),
            new Definition("e2", "Camera and payload", DeviceCheckLevel.WARNING),
            new Definition("e3", "GPS positioning", DeviceCheckLevel.WARNING),
            new Definition("e4", "Battery handling safety", DeviceCheckLevel.CRITICAL),
            new Definition("d1", "Communication logs", DeviceCheckLevel.WARNING));

    private final PersistedPostDeviceCheckRepository runRepository;
    private final MissionRepository missionRepository;
    private final MissionDeviceAssignmentRepository missionDeviceAssignmentRepository;

    @Transactional
    @Override
    @PreAuthorize("@missionAuthorizationService.canMaintainDevice(#missionId)")
    public PersistedPostDeviceCheckResponse start(String missionId) {
        Mission mission = missionRepository.findById(missionId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.MISSION_NOT_FOUND,
                        "Mission not found: " + missionId));
        MissionDeviceAssignment assignment = missionDeviceAssignmentRepository
                .findAllByMissionIdAndDeviceRoleAndIsCurrentTrueOrderByCreatedAtDesc(missionId, DeviceRole.MAIN)
                .stream()
                .filter(candidate -> candidate.getDeviceRole() == DeviceRole.MAIN)
                .filter(candidate -> candidate.getReleasedAt() == null)
                .filter(candidate -> candidate.getStatus() == MissionDeviceAssignmentStatus.POSTFLIGHT_CHECKING)
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.MISSION_STATUS_INVALID,
                        "Post-device inspection requires the assigned device to be in postflight checking."));

        PersistedPostDeviceCheck run = new PersistedPostDeviceCheck();
        run.setMission(mission);
        run.setMissionDeviceAssignment(assignment);
        run.setStatus(DeviceCheckStatus.CHECKING);
        run.setTotalChecks(CHECKS.size());
        run.setPassedChecks(0);
        run.setFailedChecks(0);
        run.setStartedAt(Instant.now());

        for (Definition definition : CHECKS) {
            PersistedPostDeviceCheckItem item = new PersistedPostDeviceCheckItem();
            item.setPostDeviceCheck(run);
            item.setCheckType(definition.type);
            item.setCheckName(definition.name);
            item.setCheckLevel(definition.level);
            run.getItems().add(item);
        }

        return PersistedPostDeviceCheckResponse.from(runRepository.save(run));
    }

    @Transactional(readOnly = true)
    @Override
    @PreAuthorize("@missionAuthorizationService.canViewMission(#missionId)")
    public List<PersistedPostDeviceCheckResponse> history(String missionId) {
        ensureMission(missionId);
        return runRepository.findByMissionIdOrderByCreatedAtDesc(missionId)
                .stream()
                .map(PersistedPostDeviceCheckResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    @Override
    @PreAuthorize("@missionAuthorizationService.canViewMission(#missionId)")
    public PersistedPostDeviceCheckResponse current(String missionId) {
        ensureMission(missionId);
        return runRepository.findFirstByMissionIdOrderByCreatedAtDesc(missionId)
                .map(PersistedPostDeviceCheckResponse::from)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    @Override
    @PreAuthorize("@missionAuthorizationService.canViewMission(#missionId)")
    public PersistedPostDeviceCheckResponse current(String missionId, String deviceId) {
        if (deviceId == null) return current(missionId);
        ensureMission(missionId);
        return runRepository.findByMissionIdOrderByCreatedAtDesc(missionId).stream()
                .filter(r -> r.getMissionDeviceAssignment() != null && r.getMissionDeviceAssignment().getDevice() != null
                        && deviceId.equals(r.getMissionDeviceAssignment().getDevice().getId()))
                .findFirst().map(PersistedPostDeviceCheckResponse::from).orElse(null);
    }

    @Transactional(readOnly = true)
    @Override
    @PreAuthorize("@deviceCheckAuthorizationService.canViewPostCheck(#id)")
    public PersistedPostDeviceCheckResponse get(String id) {
        return PersistedPostDeviceCheckResponse.from(runRepository.findById(id)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "Post-device check not found: " + id)));
    }

    @Transactional
    @Override
    @PreAuthorize("@deviceCheckAuthorizationService.canInspectPostCheck(#id)")
    public PersistedPostDeviceCheckResponse update(String id, String type, DeviceCheckItemUpdateRequest request) {
        PersistedPostDeviceCheck run = runRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "Post-device check not found: " + id));

        PersistedPostDeviceCheckItem item = run.getItems().stream()
                .filter(candidate -> type.equals(candidate.getCheckType()))
                .findFirst()
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "Unknown check type: " + type));

        item.setStatus(request.getStatus());
        item.setMessage(request.getMessage());
        item.setCheckedAt(Instant.now());

        int passed = (int) run.getItems().stream()
                .filter(i -> i.getStatus() == DeviceCheckItemStatus.PASSED)
                .count();
        int failed = (int) run.getItems().stream()
                .filter(i -> i.getStatus() == DeviceCheckItemStatus.FAILED)
                .count();
        boolean criticalFailed = run.getItems().stream()
                .anyMatch(i -> i.getCheckLevel() == DeviceCheckLevel.CRITICAL
                        && i.getStatus() == DeviceCheckItemStatus.FAILED);
        boolean finished = run.getItems().stream()
                .allMatch(i -> i.getStatus() == DeviceCheckItemStatus.PASSED
                        || i.getStatus() == DeviceCheckItemStatus.FAILED);

        run.setPassedChecks(passed);
        run.setFailedChecks(failed);
        if (criticalFailed) {
            run.setStatus(DeviceCheckStatus.FAILED);
        } else if (finished) {
            run.setStatus(DeviceCheckStatus.PASSED);
        } else {
            run.setStatus(DeviceCheckStatus.CHECKING);
        }
        if (run.getStatus() != DeviceCheckStatus.CHECKING) {
            run.setCompletedAt(Instant.now());
        }

        return PersistedPostDeviceCheckResponse.from(run);
    }

    @Transactional
    @Override
    @PreAuthorize("@missionAuthorizationService.canMaintainDevice(#missionId)")
    public void recordInspection(String missionId, Map<String, InspectionResult> results,
            PostFlightStatusRequest.TelemetrySnapshot telemetrySnapshot) {
        recordInspection(missionId, null, results, telemetrySnapshot);
    }

    @Transactional
    @Override
    @PreAuthorize("@missionAuthorizationService.canMaintainDevice(#missionId)")
    public void recordInspection(String missionId, String deviceId, Map<String, InspectionResult> results,
            PostFlightStatusRequest.TelemetrySnapshot telemetrySnapshot) {
        if (results == null || results.isEmpty()) {
            return;
        }
        Mission mission = missionRepository.findById(missionId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.MISSION_NOT_FOUND,
                        "Mission not found: " + missionId));
        MissionDeviceAssignment mainAssignment = missionDeviceAssignmentRepository
                .findAllByMissionIdAndIsCurrentTrueOrderByCreatedAtDesc(missionId).stream()
                .filter(a -> a.getReleasedAt() == null && a.getDevice() != null)
                .filter(a -> deviceId == null ? a.getDeviceRole() == DeviceRole.MAIN : deviceId.equals(a.getDevice().getId()))
                .findFirst().orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Drone is not assigned to this mission"));
        PersistedPostDeviceCheck run = runRepository.findByMissionIdOrderByCreatedAtDesc(missionId).stream()
                .filter(candidate -> candidate.getMissionDeviceAssignment() != null && mainAssignment.getId().equals(candidate.getMissionDeviceAssignment().getId()))
                .findFirst()
                .orElseGet(() -> {
                    PersistedPostDeviceCheck created = new PersistedPostDeviceCheck();
                    created.setMission(mission);
                    created.setMissionDeviceAssignment(mainAssignment);
                    created.setStartedAt(Instant.now());
                    created.setItems(new ArrayList<>());
                    return created;
                });

        run.setMissionDeviceAssignment(mainAssignment);
        run.setTotalChecks(CHECKS.size());
        Instant checkedAt = Instant.now();
        for (Definition definition : CHECKS) {
            PersistedPostDeviceCheckItem item = run.getItems().stream()
                    .filter(candidate -> definition.type().equals(candidate.getCheckType()))
                    .findFirst()
                    .orElseGet(() -> {
                        PersistedPostDeviceCheckItem created = new PersistedPostDeviceCheckItem();
                        created.setPostDeviceCheck(run);
                        created.setCheckType(definition.type());
                        run.getItems().add(created);
                        return created;
                    });
            InspectionResult result = results.get(definition.type());
            item.setCheckName(definition.name());
            item.setCheckLevel(definition.level());
            item.setStatus(result == InspectionResult.FAIL
                    ? DeviceCheckItemStatus.FAILED
                    : DeviceCheckItemStatus.PASSED);
            item.setMessage(postflightMessage(definition.type(), result, telemetrySnapshot));
            item.setCheckedAt(checkedAt);
        }

        int passed = (int) run.getItems().stream()
                .filter(item -> item.getStatus() == DeviceCheckItemStatus.PASSED)
                .count();
        int failed = (int) run.getItems().stream()
                .filter(item -> item.getStatus() == DeviceCheckItemStatus.FAILED)
                .count();
        run.setPassedChecks(passed);
        run.setFailedChecks(failed);
        run.setStatus(failed > 0 ? DeviceCheckStatus.FAILED : DeviceCheckStatus.PASSED);
        run.setCompletedAt(checkedAt);
        runRepository.save(run);
    }

    private String postflightMessage(String type, InspectionResult result,
            PostFlightStatusRequest.TelemetrySnapshot telemetrySnapshot) {
        if ("e1".equals(type) && telemetrySnapshot != null && telemetrySnapshot.getBatteryPercent() != null) {
            return "Landing battery " + telemetrySnapshot.getBatteryPercent() + "%";
        }
        if (result == InspectionResult.FAIL) {
            return "Post-flight inspection failed";
        }
        if (result == InspectionResult.WARN) {
            return "Post-flight inspection warning";
        }
        return "Post-flight inspection passed";
    }

    private void ensureMission(String id) {
        if (!missionRepository.existsById(id)) {
            throw new ApiException(
                    ErrorCode.MISSION_NOT_FOUND,
                    "Mission not found: " + id);
        }
    }

    private record Definition(String type, String name, DeviceCheckLevel level) {}
}


