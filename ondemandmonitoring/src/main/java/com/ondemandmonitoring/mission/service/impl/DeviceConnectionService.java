package com.ondemandmonitoring.mission.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.device.domain.Device;
import com.ondemandmonitoring.device.enums.DeviceStatus;
import com.ondemandmonitoring.device.repository.DeviceRepository;
import com.ondemandmonitoring.mission.domain.DeviceConnection;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.domain.MissionDeviceAssignment;
import com.ondemandmonitoring.mission.domain.MissionStaffAssignment;
import com.ondemandmonitoring.mission.enums.DeviceRole;
import com.ondemandmonitoring.mission.enums.MissionStaffRole;
import com.ondemandmonitoring.mission.enums.MissionStaffAssignmentStatus;
import com.ondemandmonitoring.mission.dto.response.MissionResponse;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.mapper.MissionMapper;
import com.ondemandmonitoring.mission.repository.DeviceConnectionRepository;
import com.ondemandmonitoring.mission.repository.MissionDeviceAssignmentRepository;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.mission.repository.MissionStaffAssignmentRepository;
import com.ondemandmonitoring.mission.service.IDeviceConnectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Service implementation managing device connection sessions
 * (`device_connections`)
 * and automated safety triggers (such as Return-To-Launch on signal loss).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceConnectionService implements IDeviceConnectionService {

    private final MissionRepository missionRepository;
    private final DeviceConnectionRepository deviceConnectionRepository;
    private final MissionDeviceAssignmentRepository missionDeviceAssignmentRepository;
    private final MissionStaffAssignmentRepository missionStaffAssignmentRepository;
    private final DeviceRepository deviceRepository;
    private final MissionMapper missionMapper;
    private final com.ondemandmonitoring.mission.service.IMissionChecklistScopeService checklistScope;
    private final com.ondemandmonitoring.mission.service.IResourceReservationService resources;

    @Override
    @Transactional
    public MissionResponse connectGcs(String missionId) {
        Mission mission = getMissionByIdOrThrow(missionId);
        MissionRuntimeGate.requireOperationalCommandsAllowed(mission);
        String resolvedMissionId = mission.getId();

        if (mission.getStatus() != MissionStatus.SCHEDULED && mission.getStatus() != MissionStatus.CONNECTED) {
            throw new ApiException(ErrorCode.MISSION_STATUS_INVALID,
                    "Mission must be in SCHEDULED state to pair with device, current: " + mission.getStatus());
        }

        mission.setStatus(MissionStatus.CONNECTED);

        MissionDeviceAssignment deviceAssignment = getAssignedDeviceAssignment(resolvedMissionId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "Mission has no current device assignment: " + resolvedMissionId));
        Device device = deviceAssignment.getDevice();
        lockExclusiveSession(device, resolvedMissionId);
        MissionStaffAssignment staffAssignment = getCurrentStaffAssignment(resolvedMissionId);
        if (staffAssignment == null) {
            throw new ApiException(
                    ErrorCode.RESOURCE_NOT_FOUND,
                    "Mission has no current staff assignment: " + resolvedMissionId);
        }

        device.setStatus(DeviceStatus.PREFLIGHT);
        deviceRepository.save(device);

        DeviceConnection connection = deviceConnectionRepository
                .findTopByMissionIdAndConnectionStatusOrderByConnectedAtDesc(resolvedMissionId, "CONNECTED")
                .orElseGet(DeviceConnection::new);
        connection.setMission(mission);
        connection.setDeviceAssignment(deviceAssignment);
        connection.setStaffAssignment(staffAssignment);
        connection.setConnectionStatus("CONNECTED");
        connection.setTelemetryActive(true);
        connection.setConnectedAt(Instant.now());
        connection.setDisconnectedAt(null);
        connection.setDisconnectReason(null);
        DeviceConnection savedConnection = deviceConnectionRepository.save(connection);

        log.info("Mission {} device connected. connectionId={} deviceId={}",
                resolvedMissionId, savedConnection.getId(), device.getId());
        Mission saved = missionRepository.save(mission);
        return missionMapper.toResponse(saved);
    }

    @Override
    @Transactional
    public MissionResponse disconnectGcs(String missionId, String disconnectReason) {
        Mission mission = getMissionByIdOrThrow(missionId);
        String resolvedMissionId = mission.getId();

        deviceConnectionRepository.findTopByMissionIdAndConnectionStatusOrderByConnectedAtDesc(resolvedMissionId, "CONNECTED")
                .ifPresent(session -> {
                    session.setConnectionStatus("DISCONNECTED");
                    session.setTelemetryActive(false);
                    session.setDisconnectedAt(Instant.now());
                    session.setDisconnectReason(
                            disconnectReason != null && !disconnectReason.isBlank() ? disconnectReason : "NORMAL");
                    deviceConnectionRepository.save(session);
                    log.info("[DEVICE-DISCONNECT] Mission {} device session disconnected cleanly. Reason: {}",
                            missionId, session.getDisconnectReason());
                });

        return missionMapper.toResponse(mission);
    }

    @Override
    @Transactional
    public MissionResponse handleGcsSessionLost(String missionId, String reason) {
        Mission mission = getMissionByIdOrThrow(missionId);
        String resolvedMissionId = mission.getId();

        deviceConnectionRepository.findTopByMissionIdAndConnectionStatusOrderByConnectedAtDesc(resolvedMissionId, "CONNECTED")
                .ifPresent(session -> {
                    session.setConnectionStatus("LOST");
                    session.setTelemetryActive(false);
                    session.setDisconnectedAt(Instant.now());
                    session.setDisconnectReason(reason != null && !reason.isBlank() ? reason : "SIGNAL_LOSS");
                    deviceConnectionRepository.save(session);
                    log.warn("[DEVICE-LOST] Mission {} device telemetry signal LOST. Reason: {}", missionId,
                            session.getDisconnectReason());
                });

        mission.setStatus(MissionStatus.RETURNING);
        Device device = getAssignedDevice(resolvedMissionId);
        if (device != null) {
            device.setStatus(DeviceStatus.RETURNING);
            deviceRepository.save(device);
        }
        log.warn(
                "[RTL-TRIGGER] Mission {} status set to RETURNING due to device signal loss. Device status updated to RETURNING.",
                missionId);

        Mission saved = missionRepository.save(mission);
        return missionMapper.toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public DeviceConnection requireActiveTelemetryConnection(String deviceId) {
        return uniqueTelemetryConnection(deviceConnectionRepository
                .findActiveTelemetryByDeviceIdentifier(deviceId, "CONNECTED"))
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "No active device connection found for telemetry device: " + deviceId));
    }

    @Override
    @Transactional
    public DeviceConnection requireActiveTelemetryConnection(String deviceId, String missionId) {
        if (missionId != null && !missionId.isBlank()) getMissionByIdOrThrow(missionId);
        var connections = deviceConnectionRepository.findActiveTelemetryByDeviceIdentifier(deviceId, "CONNECTED");
        if (missionId != null && !missionId.isBlank()) {
            getMissionByIdOrThrow(missionId);
            connections = connections.stream().filter(connection -> connection.getMission() != null
                    && missionId.equals(connection.getMission().getId())).toList();
        }
        if (connections.size() == 1 && connections.getFirst().getMission() != null)
            getMissionByIdOrThrow(connections.getFirst().getMission().getId());
        return uniqueTelemetryConnection(connections)
                .orElseGet(() -> connectTelemetrySession(deviceId, missionId));
    }

    private Optional<DeviceConnection> uniqueTelemetryConnection(List<DeviceConnection> connections) {
        if (connections.size() > 1) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Ambiguous telemetry connection; exact Mission required");
        }
        return connections.stream().findFirst().map(connection -> {
            if (connection.getMission() != null) requireRuntime(connection.getMission());
            return connection;
        });
    }

    private DeviceConnection connectTelemetrySession(String deviceId, String missionId) {
        MissionDeviceAssignment deviceAssignment = resolveTelemetryDeviceAssignment(deviceId, missionId);
        Mission mission = getMissionByIdOrThrow(deviceAssignment.getMission().getId());
        MissionRuntimeGate.requireOperationalCommandsAllowed(mission);
        requireRuntime(mission);
        if (mission.getStatus() != MissionStatus.SCHEDULED
                && mission.getStatus() != MissionStatus.CONNECTED
                && mission.getStatus() != MissionStatus.PREFLIGHT_CHECKING
                && mission.getStatus() != MissionStatus.READY_TO_FLY
                && mission.getStatus() != MissionStatus.IN_FLIGHT
                && mission.getStatus() != MissionStatus.IN_PROGRESS) {
            throw new ApiException(
                    ErrorCode.MISSION_STATUS_INVALID,
                    "Mission cannot accept telemetry in status: " + mission.getStatus());
        }

        MissionStaffAssignment staffAssignment = getCurrentStaffAssignment(mission.getId());
        if (staffAssignment == null) {
            throw new ApiException(
                    ErrorCode.RESOURCE_NOT_FOUND,
                    "Mission has no current staff assignment: " + mission.getId());
        }

        Device device = deviceAssignment.getDevice();
        lockExclusiveSession(device, mission.getId());
        if (mission.getStatus() == MissionStatus.SCHEDULED) {
            mission.setStatus(MissionStatus.CONNECTED);
            missionRepository.save(mission);
        }
        if (mission.getStatus() == MissionStatus.CONNECTED
                || mission.getStatus() == MissionStatus.PREFLIGHT_CHECKING
                || mission.getStatus() == MissionStatus.READY_TO_FLY) {
            device.setStatus(DeviceStatus.PREFLIGHT);
            deviceRepository.save(device);
        }

        DeviceConnection connection = new DeviceConnection();
        connection.setMission(mission);
        connection.setDeviceAssignment(deviceAssignment);
        connection.setStaffAssignment(staffAssignment);
        connection.setConnectionStatus("CONNECTED");
        connection.setTelemetryActive(true);
        connection.setConnectedAt(Instant.now());
        connection.setDisconnectedAt(null);
        connection.setDisconnectReason(null);

        DeviceConnection savedConnection = deviceConnectionRepository.save(connection);
        log.info("Telemetry auto-connected mission {}. connectionId={} deviceId={}",
                mission.getId(), savedConnection.getId(), device.getId());
        return savedConnection;
    }

    private MissionDeviceAssignment resolveTelemetryDeviceAssignment(String deviceId, String missionId) {
        if (missionId != null && !missionId.isBlank()) {
            Mission mission = getMissionByIdOrThrow(missionId);
            return getAssignedDeviceAssignment(mission.getId())
                    .filter(assignment -> matchesDevice(assignment.getDevice(), deviceId))
                    .orElseThrow(() -> new ApiException(
                            ErrorCode.RESOURCE_NOT_FOUND,
                            "Telemetry device is not assigned to mission: " + deviceId));
        }

        List<MissionStatus> telemetryStatuses = List.of(
                MissionStatus.SCHEDULED,
                MissionStatus.CONNECTED,
                MissionStatus.PREFLIGHT_CHECKING,
                MissionStatus.READY_TO_FLY,
                MissionStatus.IN_FLIGHT,
                MissionStatus.IN_PROGRESS);
        List<MissionDeviceAssignment> assignmentsById =
                missionDeviceAssignmentRepository.findCurrentByDeviceIdAndMissionStatusIn(deviceId, telemetryStatuses);
        List<MissionDeviceAssignment> assignments = assignmentsById.isEmpty()
                ? missionDeviceAssignmentRepository.findCurrentByDeviceCodeAndMissionStatusIn(
                        deviceId,
                        telemetryStatuses)
                : assignmentsById;

        // Device-only telemetry compatibility may discover a unique Mission, never pick a sibling.
        if (assignments.stream().map(assignment -> assignment.getMission().getId()).distinct().count() > 1) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Telemetry device has multiple Missions; exact Mission required");
        }

        return assignments.stream()
                .filter(assignment -> assignment.getDeviceRole() == DeviceRole.MAIN)
                .findFirst()
                .or(() -> assignments.stream().findFirst())
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "No active device connection found for telemetry device: " + deviceId));
    }

    private boolean matchesDevice(Device device, String deviceId) {
        if (device == null || deviceId == null || deviceId.isBlank()) {
            return false;
        }
        return deviceId.equals(device.getId()) || deviceId.equals(device.getDeviceCode());
    }

    private Device getAssignedDevice(String missionId) {
        return getAssignedDeviceAssignment(missionId)
                .map(MissionDeviceAssignment::getDevice)
                .orElse(null);
    }

    private java.util.Optional<MissionDeviceAssignment> getAssignedDeviceAssignment(String missionId) {
        List<MissionDeviceAssignment> assignments =
                missionDeviceAssignmentRepository.findAllByMissionIdAndIsCurrentTrueOrderByCreatedAtDesc(missionId);
        return assignments.stream()
                .filter(assignment -> assignment.getDeviceRole() == DeviceRole.MAIN)
                .findFirst()
                .or(() -> assignments.stream().findFirst());
    }

    private MissionStaffAssignment getCurrentStaffAssignment(String missionId) {
        List<MissionStaffAssignment> assignments = missionStaffAssignmentRepository.findAllByMissionIdAndIsCurrentTrue(
                missionId);
        return assignments.stream()
                .filter(assignment -> assignment.getAssignedRole() == MissionStaffRole.OPERATOR
                        && assignment.getResponseStatus() == MissionStaffAssignmentStatus.ACCEPTED
                        && assignment.getReleasedAt() == null)
                .findFirst()
                .orElse(null);
    }

    private void requireRuntime(Mission mission) {
        MissionRuntimeGate.requireFinalizedStructure(mission);
        checklistScope.requireMaterialized(mission);
        MissionRuntimeGate.requireSessionAllowed(mission);
    }

    private Mission getMissionByIdOrThrow(String missionId) {
        return missionRepository.findByIdForUpdate(missionId)
                .map(mission -> { requireRuntime(mission); return mission; })
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Mission not found: " + missionId));
    }

    private void lockExclusiveSession(Device device, String missionId) {
        resources.lockResources(List.of(device.getId()));
        if (deviceConnectionRepository.findActiveTelemetryByDeviceIdentifier(device.getId(), "CONNECTED").stream()
                .anyMatch(c -> c.getMission() != null && !missionId.equals(c.getMission().getId())))
            throw new ApiException(ErrorCode.RESOURCE_TIME_CONFLICT, "Device has another active Mission session");
    }
}
