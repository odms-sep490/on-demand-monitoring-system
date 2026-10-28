package com.ondemandmonitoring.mission.service;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.device.domain.Device;
import com.ondemandmonitoring.mission.domain.FlightToken;
import com.ondemandmonitoring.mission.domain.MissionDeviceAssignment;
import com.ondemandmonitoring.mission.domain.MissionStaffAssignment;
import com.ondemandmonitoring.mission.repository.FlightTokenRepository;
import com.ondemandmonitoring.mission.repository.MissionDeviceAssignmentRepository;
import com.ondemandmonitoring.mission.repository.MissionStaffAssignmentRepository;
import com.ondemandmonitoring.mission.service.impl.FlightTokenService;
import com.ondemandmonitoring.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FlightTokenServiceTest {

    @Mock
    private FlightTokenRepository flightTokenRepository;

    @Mock
    private MissionDeviceAssignmentRepository missionDeviceAssignmentRepository;

    @Mock
    private MissionStaffAssignmentRepository missionStaffAssignmentRepository;

    private FlightTokenService flightTokenService;

    @BeforeEach
    void setUp() {
        flightTokenService = new FlightTokenService(
                flightTokenRepository,
                missionDeviceAssignmentRepository,
                missionStaffAssignmentRepository);
    }

    @Test
    @DisplayName("issueFlightToken generates valid 60-minute token for assigned staff and device")
    void issueFlightToken_Success() {
        String missionId = "M-101";
        String deviceId = "DEV-01";
        String staffId = "STAFF-1";

        Device device = new Device();
        device.setId(deviceId);
        device.setDeviceCode("DEVICE-01");

        MissionDeviceAssignment deviceAssignment = new MissionDeviceAssignment();
        var mission = new com.ondemandmonitoring.mission.domain.Mission(); mission.setId(missionId); deviceAssignment.setMission(mission);
        deviceAssignment.setDevice(device);
        deviceAssignment.setIsCurrent(true);

        User staff = new User();
        staff.setId(staffId);

        MissionStaffAssignment staffAssignment = new MissionStaffAssignment();
        staffAssignment.setStaff(staff);
        staffAssignment.setIsCurrent(true);
        staffAssignment.setAssignedRole(com.ondemandmonitoring.mission.enums.MissionStaffRole.PILOT);
        staffAssignment.setResponseStatus(com.ondemandmonitoring.mission.enums.MissionStaffAssignmentStatus.ACCEPTED);

        when(missionDeviceAssignmentRepository.findAllByMissionIdAndIsCurrentTrueOrderByCreatedAtDesc(missionId))
                .thenReturn(List.of(deviceAssignment));
        when(missionStaffAssignmentRepository.findAllByMissionIdAndStaffIdAndIsCurrentTrueOrderByAssignedAtDesc(
                missionId,
                staffId))
                .thenReturn(List.of(staffAssignment));

        when(flightTokenRepository.save(any(FlightToken.class))).thenAnswer(invocation -> invocation.getArgument(0));

        FlightToken token = flightTokenService.issueFlightToken(missionId, deviceId, staffId);

        assertThat(token).isNotNull();
        assertThat(token.getMissionId()).isEqualTo(missionId);
        assertThat(token.getDeviceAssignment()).isEqualTo(deviceAssignment);
        assertThat(token.getStaffAssignment()).isEqualTo(staffAssignment);
        assertThat(token.getExpiresAt()).isAfter(token.getIssuedAt());
        assertThat(token.getExpiresAt().getEpochSecond() - token.getIssuedAt().getEpochSecond())
                .isEqualTo(FlightTokenService.TOKEN_TTL_SECONDS); // 3600 seconds = 60 minutes
    }

    @Test
    @DisplayName("issueFlightToken throws ApiException when requested staff is not assigned to mission")
    void issueFlightToken_UnassignedStaff_ThrowsException() {
        String missionId = "M-101";
        String deviceId = "DEV-01";
        String requestedStaffId = "STAFF-WRONG";

        Device device = new Device();
        device.setId(deviceId);

        MissionDeviceAssignment deviceAssignment = new MissionDeviceAssignment();
        deviceAssignment.setDevice(device);
        deviceAssignment.setIsCurrent(true);

        User staff = new User();
        staff.setId("STAFF-CORRECT");

        MissionStaffAssignment staffAssignment = new MissionStaffAssignment();
        staffAssignment.setStaff(staff);
        staffAssignment.setIsCurrent(true);

        when(missionDeviceAssignmentRepository.findAllByMissionIdAndIsCurrentTrueOrderByCreatedAtDesc(missionId))
                .thenReturn(List.of(deviceAssignment));
        when(missionStaffAssignmentRepository.findAllByMissionIdAndStaffIdAndIsCurrentTrueOrderByAssignedAtDesc(
                missionId,
                requestedStaffId))
                .thenReturn(List.of());

        assertThatThrownBy(() -> flightTokenService.issueFlightToken(missionId, deviceId, requestedStaffId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not assigned to mission");
    }

    @Test
    @DisplayName("issueFlightToken throws ApiException when requested device is not assigned to mission")
    void issueFlightToken_UnassignedDevice_ThrowsException() {
        String missionId = "M-101";
        String requestedDeviceId = "DEV-WRONG";
        String staffId = "STAFF-1";

        Device device = new Device();
        device.setId("DEV-CORRECT");

        MissionDeviceAssignment deviceAssignment = new MissionDeviceAssignment();
        deviceAssignment.setDevice(device);
        deviceAssignment.setIsCurrent(true);

        when(missionDeviceAssignmentRepository.findAllByMissionIdAndIsCurrentTrueOrderByCreatedAtDesc(missionId))
                .thenReturn(List.of(deviceAssignment));

        assertThatThrownBy(() -> flightTokenService.issueFlightToken(missionId, requestedDeviceId, staffId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not assigned to mission");
    }

    @Test
    @DisplayName("validateToken returns true for active unexpired token")
    void validateToken_ValidToken_ReturnsTrue() {
        String tokenValue = "valid-token-xyz";
        FlightToken token = new FlightToken();
        token.setTokenValue(tokenValue);
        token.setIssuedAt(Instant.now());
        token.setExpiresAt(Instant.now().plusSeconds(3600));
        token.setUsed(false);
        token.setRevoked(false);

        when(flightTokenRepository.findByTokenValue(tokenValue)).thenReturn(Optional.of(token));

        boolean isValid = flightTokenService.validateToken(tokenValue);
        assertThat(isValid).isTrue();
    }
}
