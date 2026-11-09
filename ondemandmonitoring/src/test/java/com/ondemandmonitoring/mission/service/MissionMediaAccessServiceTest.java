package com.ondemandmonitoring.mission.service;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.device.domain.Device;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.domain.MissionDeviceAssignment;
import com.ondemandmonitoring.mission.domain.MissionStaffAssignment;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.repository.MissionDeviceAssignmentRepository;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.mission.repository.MissionStaffAssignmentRepository;
import com.ondemandmonitoring.mission.service.impl.MissionMediaAccessServiceImpl;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MissionMediaAccessServiceTest {
    private final MissionRepository missions = mock(MissionRepository.class);
    private final MissionDeviceAssignmentRepository deviceAssignments = mock(MissionDeviceAssignmentRepository.class);
    private final MissionStaffAssignmentRepository staffAssignments = mock(MissionStaffAssignmentRepository.class);
    private final AuthenticatedUserResolver currentUser = mock(AuthenticatedUserResolver.class);
    private final IMissionAuthorizationService authorization = mock(IMissionAuthorizationService.class);
    private final IMissionMediaAccessService service =
            new MissionMediaAccessServiceImpl(missions, deviceAssignments, currentUser, authorization);
    private Mission mission;

    @BeforeEach
    void setup() {
        mission = new Mission();
        mission.setId("mission-id");
        mission.setStatus(MissionStatus.IN_FLIGHT);
        when(missions.findById("mission-id")).thenReturn(Optional.of(mission));
        when(currentUser.getCurrentUserId()).thenReturn("operator-id");
        authenticate(List.of());
    }

    @AfterEach
    void cleanup() { SecurityContextHolder.clearContext(); }

    @Test
    void resolvesMissionCodeToImmutableAuthorizedContext() {
        when(missions.findByMissionCode("MS-001")).thenReturn(Optional.of(mission));
        assignOperator();
        var context = service.authorizeOperator("MS-001");
        assertThat(context.getId()).isEqualTo("mission-id");
        assertThat(context.isCaptureAllowed()).isTrue();
        mission.setStatus(MissionStatus.READY);
        when(authorization.canUploadMissionMedia("mission-id")).thenReturn(false);
        assertThat(service.authorizeOperator("mission-id").isCaptureAllowed()).isFalse();
    }

    @Test
    void pendingReviewAllowsInspectorMediaUpload() {
        mission.setStatus(MissionStatus.PENDING_REVIEW);
        when(authorization.canViewMissionMedia("mission-id")).thenReturn(true);
        when(authorization.canUploadMissionMedia("mission-id")).thenReturn(true);

        assertThat(service.authorizeOperator("mission-id").isCaptureAllowed()).isTrue();
    }

    @Test
    void readyToFlyAllowsPilotMediaUploadWhenAuthorized() {
        mission.setStatus(MissionStatus.READY_TO_FLY);
        when(authorization.canViewMissionMedia("mission-id")).thenReturn(true);
        when(authorization.canUploadMissionMedia("mission-id")).thenReturn(true);

        assertThat(service.authorizeOperator("mission-id").isCaptureAllowed()).isTrue();
    }

    @Test
    void completedMissionAllowsInspectorMediaUpload() {
        mission.setStatus(MissionStatus.COMPLETED);
        when(authorization.canViewMissionMedia("mission-id")).thenReturn(true);
        when(authorization.canUploadMissionMedia("mission-id")).thenReturn(true);

        var context = service.authorizeOperator("mission-id");

        assertThat(context.isCaptureAllowed()).isTrue();
    }

    @Test
    void refusesAnonymousAndUnassignedOperator() {
        assertThatThrownBy(() -> service.authorizeOperator("mission-id")).isInstanceOf(ApiException.class);
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> service.authorizeOperator("mission-id")).isInstanceOf(ApiException.class);
    }

    @Test
    void permitsPrivilegedOperatorButStillRequiresAssignedDevice() {
        authenticate(List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        when(authorization.canViewMissionMedia("mission-id")).thenReturn(true);
        assertThat(service.authorizeOperator("mission-id").isCaptureAllowed()).isFalse();
        assertThatThrownBy(() -> service.requireUploadPermission("mission-id")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.requireAssignedDevice("mission-id", "other-device"))
                .isInstanceOf(ApiException.class).hasMessageContaining("Device is not assigned");
    }

    @Test
    void completedMissionAllowsReleasedMissionDevice() {
        mission.setStatus(MissionStatus.COMPLETED);
        when(authorization.canViewMissionMedia("mission-id")).thenReturn(true);
        User staffUser = new User();
        staffUser.setId("operator-id");
        var operator = new MissionStaffAssignment();
        operator.setStaff(staffUser);
        when(staffAssignments.findByMissionId("mission-id")).thenReturn(List.of(operator));
        var device = new Device();
        device.setId("device-id");
        var assignment = new MissionDeviceAssignment();
        assignment.setDevice(device);
        when(deviceAssignments.findByMissionId("mission-id")).thenReturn(List.of(assignment));
        assertThatCode(() -> service.requireAssignedDevice("mission-id", "device-id")).doesNotThrowAnyException();
        when(deviceAssignments.findByMissionId("mission-id")).thenReturn(List.of());
        assertThatThrownBy(() -> service.requireAssignedDevice("mission-id", "device-id"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void customerCanAccessOnlyOwnedMission() {
        var owner = new User();
        owner.setId("customer-id");
        var order = new Order();
        order.setCustomer(owner);
        mission.setOrder(order);
        when(currentUser.getCurrentUserId()).thenReturn("customer-id");
        when(missions.findByOrder_Customer_Id("customer-id")).thenReturn(List.of(mission));
        assertThat(service.authorizeCustomer("mission-id")).isEqualTo("mission-id");
        assertThat(service.ownCustomerMissionIds()).containsExactly("mission-id");
        when(currentUser.getCurrentUserId()).thenReturn("other-customer");
        assertThatThrownBy(() -> service.authorizeCustomer("mission-id")).isInstanceOf(ApiException.class);
    }

    private void assignOperator() {
        when(authorization.canViewMissionMedia("mission-id")).thenReturn(true);
        when(authorization.canUploadMissionMedia("mission-id")).thenReturn(true);
        User staffUser = new User();
        staffUser.setId("operator-id");
        var assignment = new MissionStaffAssignment();
        assignment.setStaff(staffUser);
        when(staffAssignments.findByMissionIdAndIsCurrentTrue("mission-id")).thenReturn(Optional.of(assignment));
    }

    private void authenticate(List<SimpleGrantedAuthority> roles) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("operator", "n/a", roles));
    }
}
