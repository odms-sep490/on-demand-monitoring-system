package com.ondemandmonitoring.mission.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.mission.domain.FlightToken;
import com.ondemandmonitoring.mission.domain.MissionDeviceAssignment;
import com.ondemandmonitoring.mission.domain.MissionStaffAssignment;
import com.ondemandmonitoring.mission.enums.DeviceRole;
import com.ondemandmonitoring.mission.enums.MissionStaffRole;
import com.ondemandmonitoring.mission.enums.MissionStaffAssignmentStatus;
import com.ondemandmonitoring.mission.repository.FlightTokenRepository;
import com.ondemandmonitoring.mission.repository.MissionDeviceAssignmentRepository;
import com.ondemandmonitoring.mission.repository.MissionStaffAssignmentRepository;
import com.ondemandmonitoring.mission.service.IFlightTokenService;
import com.ondemandmonitoring.mission.util.FlightTokenGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;

/**
 * Dedicated service for issuing 60-minute HMAC-SHA256 flight tokens and
 * validating staff authorization.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlightTokenService implements IFlightTokenService {

    public static final long TOKEN_TTL_SECONDS = 3600; // 60 minutes TTL

    private final FlightTokenRepository flightTokenRepository;
    private final MissionDeviceAssignmentRepository missionDeviceAssignmentRepository;
    private final MissionStaffAssignmentRepository missionStaffAssignmentRepository;

    @Override
    @Transactional
    public FlightToken issueFlightToken(String missionId, String deviceId, String staffId) {
        MissionDeviceAssignment deviceAssignment = missionDeviceAssignmentRepository
                .findAllByMissionIdAndIsCurrentTrueOrderByCreatedAtDesc(missionId)
                .stream()
                .filter(assignment -> assignment.getDevice() != null
                        && (deviceId == null || deviceId.isBlank()
                                || deviceId.equals(assignment.getDevice().getId())))
                .min(Comparator.comparing(assignment -> assignment.getDeviceRole() != DeviceRole.MAIN))
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_REQUEST,
                        "Device " + deviceId + " is not assigned to mission " + missionId));

        MissionStaffAssignment staffAssignment = missionStaffAssignmentRepository
                .findAllByMissionIdAndStaffIdAndIsCurrentTrueOrderByAssignedAtDesc(missionId, staffId)
                .stream()
                .filter(entry -> entry.getAssignedRole() == MissionStaffRole.PILOT
                        && entry.getResponseStatus() == MissionStaffAssignmentStatus.ACCEPTED)
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_REQUEST,
                        "Staff " + staffId + " is not assigned to mission " + missionId));

        String resolvedDeviceId = deviceAssignment.getDevice().getId();
        MissionRuntimeGate.requireOperationalCommandsAllowed(deviceAssignment.getMission());
        String resolvedStaffId = staffAssignment.getStaff().getId().toString();
        Instant now = Instant.now();
        FlightToken token = new FlightToken();
        token.setTokenValue(FlightTokenGenerator.generateTokenValue(missionId, resolvedDeviceId, resolvedStaffId, now));
        token.setMissionId(missionId);
        token.setDeviceAssignment(deviceAssignment);
        token.setStaffAssignment(staffAssignment);
        token.setIssuedAt(now);
        token.setExpiresAt(now.plusSeconds(TOKEN_TTL_SECONDS));
        token.setUsed(false);
        token.setRevoked(false);
        log.info("[TOKEN-ISSUED] Issued 60-min HMAC-SHA256 flight token for mission {}, device {}, staff {}",
                missionId, resolvedDeviceId, resolvedStaffId);
        return flightTokenRepository.save(token);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean validateToken(String tokenValue) {
        if (tokenValue == null || tokenValue.isBlank()) {
            return false;
        }
        return flightTokenRepository.findByTokenValue(tokenValue)
                .map(FlightToken::isValid)
                .orElse(false);
    }
}
