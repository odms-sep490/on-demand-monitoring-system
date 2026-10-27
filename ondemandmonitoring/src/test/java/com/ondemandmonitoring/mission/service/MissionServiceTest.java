package com.ondemandmonitoring.mission.service;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.device.domain.Device;
import com.ondemandmonitoring.devicecheck.domain.PersistedPostDeviceCheck;
import com.ondemandmonitoring.device.enums.DeviceStatus;
import com.ondemandmonitoring.device.repository.DeviceRepository;
import com.ondemandmonitoring.device.repository.MaintenanceTicketRepository;
import com.ondemandmonitoring.devicecheck.repository.PersistedPostDeviceCheckRepository;
import com.ondemandmonitoring.devicecheck.service.IPreDeviceCheckCompletionService;
import com.ondemandmonitoring.devicecheck.service.IPersistedPostDeviceCheckService;
import com.ondemandmonitoring.mission.domain.DeviceConnection;
import com.ondemandmonitoring.mission.domain.FlightToken;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.domain.MissionDeviceAssignment;
import com.ondemandmonitoring.mission.domain.MissionStaffAssignment;
import com.ondemandmonitoring.mission.dto.request.AssignDeviceRequest;
import com.ondemandmonitoring.mission.dto.request.AssignStaffRequest;
import com.ondemandmonitoring.mission.dto.response.FlightTokenResponse;
import com.ondemandmonitoring.mission.dto.response.MissionResponse;
import com.ondemandmonitoring.mission.enums.MissionStaffRole;
import com.ondemandmonitoring.mission.enums.FlightPermitStatus;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.enums.StaffResponseStatus;
import com.ondemandmonitoring.mission.mapper.FlightTokenMapper;
import com.ondemandmonitoring.mission.mapper.MissionMapper;
import com.ondemandmonitoring.mission.repository.ControlHandoverRepository;
import com.ondemandmonitoring.mission.repository.DeviceConnectionRepository;
import com.ondemandmonitoring.mission.repository.FlightTokenRepository;
import com.ondemandmonitoring.mission.repository.MissionDeviceAssignmentRepository;
import com.ondemandmonitoring.mission.repository.MissionPlanRepository;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.mission.repository.MissionStaffAssignmentRepository;
import com.ondemandmonitoring.mission.repository.ResourceTimeLockRepository;
import com.ondemandmonitoring.mission.service.impl.DeviceConnectionService;
import com.ondemandmonitoring.mission.service.impl.FlightTokenService;
import com.ondemandmonitoring.mission.service.impl.MissionService;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.finance.repository.InvoiceRepository;
import com.ondemandmonitoring.finance.service.IMissionPaymentEligibilityService;
import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.IStaffDirectoryService;
import com.ondemandmonitoring.userschedule.repository.UserScheduleRepository;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MissionServiceTest {

    MissionRepository missionRepository;
    DeviceRepository deviceRepository;
    FlightTokenRepository flightTokenRepository;
    MissionMapper missionMapper;
    FlightTokenMapper flightTokenMapper;

    MissionPlanRepository missionPlanRepository;
    DeviceConnectionRepository deviceConnectionRepository;
    ControlHandoverRepository controlHandoverRepository;
    PersistedPostDeviceCheckRepository postDeviceCheckRepository;
    MaintenanceTicketRepository maintenanceTicketRepository;
    OrderRepository orderRepository;
    InvoiceRepository invoiceRepository;
    IMissionPaymentEligibilityService missionPaymentEligibilityService;
    IDeviceConnectionService deviceConnectionService;
    IFlightTokenService flightTokenService;
    IMissionResultService missionResultService;
    IPreDeviceCheckCompletionService preDeviceCheckCompletionService;
    IPersistedPostDeviceCheckService persistedPostDeviceCheckService;
    IStaffDirectoryService staffDirectory;
    AuthenticatedUserResolver authenticatedUserResolver;
    UserScheduleRepository userScheduleRepository;
    MissionDeviceAssignmentRepository missionDeviceAssignmentRepository;
    MissionStaffAssignmentRepository missionStaffAssignmentRepository;
    ResourceTimeLockRepository resourceTimeLockRepository;

    MissionService missionService;

    @BeforeEach
    void setUp() {
        missionRepository = mock(MissionRepository.class);
        deviceRepository = mock(DeviceRepository.class);
        flightTokenRepository = mock(FlightTokenRepository.class);
        missionMapper = mock(MissionMapper.class);
        flightTokenMapper = mock(FlightTokenMapper.class);
        missionPlanRepository = mock(MissionPlanRepository.class);
        deviceConnectionRepository = mock(DeviceConnectionRepository.class);
        controlHandoverRepository = mock(ControlHandoverRepository.class);
        postDeviceCheckRepository = mock(PersistedPostDeviceCheckRepository.class);
        maintenanceTicketRepository = mock(MaintenanceTicketRepository.class);
        orderRepository = mock(OrderRepository.class);
        invoiceRepository = mock(InvoiceRepository.class);
        missionPaymentEligibilityService = mock(IMissionPaymentEligibilityService.class);
        staffDirectory = mock(IStaffDirectoryService.class);
        authenticatedUserResolver = mock(AuthenticatedUserResolver.class);
        userScheduleRepository = mock(UserScheduleRepository.class);
        missionDeviceAssignmentRepository = mock(MissionDeviceAssignmentRepository.class);
        missionStaffAssignmentRepository = mock(MissionStaffAssignmentRepository.class);
        resourceTimeLockRepository = mock(ResourceTimeLockRepository.class);
        missionResultService = mock(IMissionResultService.class);
        preDeviceCheckCompletionService = mock(IPreDeviceCheckCompletionService.class);
        persistedPostDeviceCheckService = mock(IPersistedPostDeviceCheckService.class);

        deviceConnectionService = new DeviceConnectionService(
                missionRepository,
                deviceConnectionRepository,
                missionDeviceAssignmentRepository,
                missionStaffAssignmentRepository,
                deviceRepository,
                missionMapper, com.ondemandmonitoring.mission.service.ScopeTestFixture.validScope(),
                mock(com.ondemandmonitoring.mission.service.IResourceReservationService.class));

        flightTokenService = new FlightTokenService(
                flightTokenRepository,
                missionDeviceAssignmentRepository,
                missionStaffAssignmentRepository);

        missionService = new MissionService(
                missionRepository,
                deviceRepository,
                flightTokenRepository,
                missionMapper,
                flightTokenMapper,
                missionPlanRepository,
                deviceConnectionRepository,
                controlHandoverRepository,
                postDeviceCheckRepository,
                maintenanceTicketRepository,
                orderRepository,
                invoiceRepository,
                missionPaymentEligibilityService,
                deviceConnectionService,
                flightTokenService,
                missionResultService,
                preDeviceCheckCompletionService,
                persistedPostDeviceCheckService,
                staffDirectory,
                authenticatedUserResolver,
                userScheduleRepository,
                missionDeviceAssignmentRepository,
                missionStaffAssignmentRepository,
                resourceTimeLockRepository,
                mock(com.ondemandmonitoring.mission.service.IMissionChecklistExecutionService.class),
                mock(com.ondemandmonitoring.mission.service.IMissionChecklistAssignmentService.class),
                new com.ondemandmonitoring.mission.service.impl.ResourceReservationServiceImpl(
                        mock(org.springframework.jdbc.core.JdbcTemplate.class), resourceTimeLockRepository, userScheduleRepository, authenticatedUserResolver),
                mock(com.ondemandmonitoring.device.repository.DeviceTelemetryRepository.class));

        when(missionRepository.findByIdForUpdate(anyString())).thenAnswer(call -> missionRepository.findById(call.getArgument(0)));
        when(missionRepository.findOrderIdByMissionId(anyString())).thenAnswer(call -> missionRepository.findById(call.getArgument(0))
                .map(m -> m.getOrder() == null ? "unit-order" : m.getOrder().getId()));
        when(orderRepository.findByIdForUpdate("unit-order")).thenReturn(Optional.of(new com.ondemandmonitoring.order.domain.Order()));

        when(missionMapper.toResponse(any())).thenAnswer(inv -> {
            Mission m = inv.getArgument(0);
            if (m == null)
                return null;
            return MissionResponse.builder()
                    .id(m.getId())
                    .missionCode(m.getMissionCode())
                    .status(m.getStatus())
                    .actualStartAt(m.getActualStartAt())
                    .actualEndAt(m.getActualEndAt())
                    .build();
        });

        when(flightTokenMapper.toResponse(any())).thenAnswer(inv -> {
            FlightToken ft = inv.getArgument(0);
            if (ft == null)
                return null;
            return FlightTokenResponse.builder()
                    .tokenValue(ft.getTokenValue())
                    .issuedAt(ft.getIssuedAt())
                    .expiresAt(ft.getExpiresAt())
                    .build();
        });
    }

    @Test
    void creationDoesNotTreatUnrelatedDatabaseFailureAsDuplicate() {
        var request = MissionCreationServiceTest.request("order-1", java.util.UUID.randomUUID().toString(), 0);
        var failure = new org.springframework.dao.DataAccessResourceFailureException("Database unavailable");
        when(orderRepository.findByIdForUpdate("order-1")).thenThrow(failure);
        assertThatThrownBy(() -> missionService.createMission("order-1", request)).isSameAs(failure);
        verifyNoInteractions(missionRepository);
    }

    @Test
    void missionAndDeviceOperationsRejectOrderIdentityWithoutLookingUpChildren() {
        assertThatThrownBy(() -> missionService.findById("order-1"))
                .isInstanceOf(com.ondemandmonitoring.common.exception.ApiException.class);
        assertThatThrownBy(() -> deviceConnectionService.connectGcs("order-1"))
                .isInstanceOf(com.ondemandmonitoring.common.exception.ApiException.class);
        verify(missionRepository, never()).findAllByOrderIdOrderByScheduledStartAtAscIdAsc(anyString());
        verifyNoInteractions(missionDeviceAssignmentRepository, missionStaffAssignmentRepository);
    }

    @Test
    void deviceOperationUsesExactChildMissionAndDoesNotTouchSibling() {
        var order = new com.ondemandmonitoring.order.domain.Order(); order.setId("order-1");
        var first = new Mission(); first.setId("m1"); first.setOrder(order);
        var sibling = new Mission(); sibling.setId("m2"); sibling.setOrder(order);
        when(missionRepository.findById("m1")).thenReturn(Optional.of(first));
        assertThat(deviceConnectionService.disconnectGcs("m1", "NORMAL").getId()).isEqualTo("m1");
        verify(deviceConnectionRepository).findTopByMissionIdAndConnectionStatusOrderByConnectedAtDesc("m1", "CONNECTED");
        verify(missionRepository, never()).findById("m2");
        verify(missionRepository, never()).findAllByOrderIdOrderByScheduledStartAtAscIdAsc(anyString());
        assertThat(sibling.getStatus()).isNull();
    }

    @Test
    void telemetryWithExactMissionCannotReuseSiblingSessionOrOrderAlias() {
        var order = new com.ondemandmonitoring.order.domain.Order(); order.setId("order-1");
        var first = new Mission(); first.setId("m1"); first.setOrder(order);
        var second = new Mission(); second.setId("m2"); second.setOrder(order);
        var firstSession = new DeviceConnection(); firstSession.setMission(first);
        var secondSession = new DeviceConnection(); secondSession.setMission(second);
        when(missionRepository.findById("m1")).thenReturn(Optional.of(first));
        when(deviceConnectionRepository.findActiveTelemetryByDeviceIdentifier("device", "CONNECTED"))
                .thenReturn(List.of(secondSession, firstSession));
        assertThat(deviceConnectionService.requireActiveTelemetryConnection("device", "m1")).isSameAs(firstSession);
        assertThatThrownBy(() -> deviceConnectionService.requireActiveTelemetryConnection("device", "order-1"))
                .isInstanceOf(com.ondemandmonitoring.common.exception.ApiException.class);
        assertThatThrownBy(() -> deviceConnectionService.requireActiveTelemetryConnection("device"))
                .isInstanceOf(com.ondemandmonitoring.common.exception.ApiException.class);
        verifyNoInteractions(missionDeviceAssignmentRepository);
    }

    @Test
    void telemetryDoesNotDiscoverAnArbitrarySiblingFromDeviceAssignments() {
        var first = new Mission(); first.setId("m1");
        var second = new Mission(); second.setId("m2");
        var a = new MissionDeviceAssignment(); a.setMission(first);
        var b = new MissionDeviceAssignment(); b.setMission(second);
        when(missionDeviceAssignmentRepository.findCurrentByDeviceIdAndMissionStatusIn(eq("device"), any()))
                .thenReturn(List.of(a, b));
        assertThatThrownBy(() -> deviceConnectionService.requireActiveTelemetryConnection("device", null))
                .isInstanceOf(com.ondemandmonitoring.common.exception.ApiException.class);
        verify(deviceConnectionRepository, never()).save(any());
    }

    @Test
    void searchStaffMissionsMapsRepositoryPage() {
        Mission mission = new Mission();
        mission.setMissionCode("MS-001");
        mission.setStatus(MissionStatus.SCHEDULED);
        PageRequest pageable = PageRequest.of(0, 20);
        when(missionRepository.findAll(any(Specification.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(mission), pageable, 1));

        var result = missionService.searchStaffMissions(MissionStatus.SCHEDULED,
                Instant.parse("2026-09-20T17:00:00Z"),
                Instant.parse("2026-09-27T17:00:00Z"), pageable);

        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().get(0).getMissionCode()).isEqualTo("MS-001");
        assertThat(result.getTotalItems()).isEqualTo(1);
    }

    // =========================================================================
    // F3.1 – Staff Acceptance / Rejection
    // =========================================================================
    @Nested
    @DisplayName("F3.1 - Staff Acceptance & Rejection")
    class F3_1_OperatorAcceptanceAndRejection {

        @Test
        void assignStaffWithoutTelemetryDefersPlanning() {
            Mission mission = buildMission("m-assign", MissionStatus.READY);
            mission.setScheduledStartAt(Instant.now().plusSeconds(3600));
            mission.setScheduledEndAt(Instant.now().plusSeconds(7200));

            User staffUser = new User();
            staffUser.setId("op-01");
            staffUser.setIsActive(true);
            Role role = new Role();
            role.setCode(RoleCode.STAFF);
            role.setActive(true);
            staffUser.setRole(role);

            when(missionRepository.findById("m-assign")).thenReturn(Optional.of(mission));
            when(staffDirectory.requireActiveStaff("op-01")).thenReturn(staffUser);
            when(missionDeviceAssignmentRepository.existsByMissionId("m-assign")).thenReturn(true);
            when(missionStaffAssignmentRepository.findAllByMissionIdAndIsCurrentTrue("m-assign"))
                    .thenReturn(List.of(staffAssignment(mission, "op-01", "PENDING")));
            when(missionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            AssignStaffRequest request = AssignStaffRequest.builder().staffId("op-01").build();
            assertThat(missionService.assignStaff("m-assign", request).getStatus())
                    .isEqualTo(MissionStatus.WAITING_CREW_CONFIRMATION);
        }

        @Test
        @DisplayName("1. acceptMission success when WAITING_OPERATOR_ACCEPTANCE")
        void acceptMission_success() {
            Mission mission = buildMission("m-1", MissionStatus.WAITING_OPERATOR_ACCEPTANCE);
            when(missionRepository.findById("m-1")).thenReturn(Optional.of(mission));
            MissionStaffAssignment assignment = staffAssignment(mission, "op-01", "PENDING");
            when(missionStaffAssignmentRepository.findAllByMissionIdAndStaffIdAndIsCurrentTrueOrderByAssignedAtDesc("m-1", "op-01"))
                    .thenReturn(List.of(assignment));
            when(missionStaffAssignmentRepository.findAllByMissionIdAndIsCurrentTrue("m-1"))
                    .thenReturn(List.of(assignment,
                            staffAssignment(mission, "payload", MissionStaffRole.OPERATOR, "ACCEPTED"),
                            staffAssignment(mission, "maintainer", MissionStaffRole.MAINTAINER, "ACCEPTED"),
                            staffAssignment(mission, "inspector", MissionStaffRole.INSPECTOR, "ACCEPTED")));
            when(missionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            MissionResponse result = missionService.acceptMission("m-1", "op-01");

            assertThat(result.getStatus()).isEqualTo(MissionStatus.WAITING_OPERATOR_ACCEPTANCE);
            verify(missionStaffAssignmentRepository).saveAll(any());
        }

        @Test
        void oneStaffCanAcceptAllAssignedRolesAndWaitForInspectorStart() {
            Mission mission = buildMission("m-solo", MissionStatus.WAITING_CREW_CONFIRMATION);
            var assignments = java.util.Arrays.stream(MissionStaffRole.values())
                    .map(role -> staffAssignment(mission, "seed-staff-1", role, "PENDING"))
                    .toList();
            when(missionRepository.findById("m-solo")).thenReturn(Optional.of(mission));
            when(missionStaffAssignmentRepository.findAllByMissionIdAndStaffIdAndIsCurrentTrueOrderByAssignedAtDesc(
                    "m-solo", "seed-staff-1"))
                    .thenReturn(assignments);
            when(missionStaffAssignmentRepository.findAllByMissionIdAndIsCurrentTrue("m-solo"))
                    .thenReturn(assignments);
            when(missionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            MissionResponse result = missionService.acceptMission("m-solo", "seed-staff-1");

            assertThat(result.getStatus()).isEqualTo(MissionStatus.WAITING_CREW_CONFIRMATION);
            assertThat(assignments).allMatch(entry -> entry.getResponseStatus() == StaffResponseStatus.ACCEPTED);
            verify(missionStaffAssignmentRepository).saveAll(assignments);
        }

        @Test
        @DisplayName("2. acceptMission throws exception when mission not found")
        void acceptMission_notFound_throws() {
            when(missionRepository.findById("m-missing")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> missionService.acceptMission("m-missing", "op-01"))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("Mission not found");
        }

        @Test
        @DisplayName("3. acceptMission throws exception when status is invalid")
        void acceptMission_wrongStatus_throws() {
            Mission mission = buildMission("m-1", MissionStatus.SCHEDULED);
            when(missionRepository.findById("m-1")).thenReturn(Optional.of(mission));

            assertThatThrownBy(() -> missionService.acceptMission("m-1", "op-01"))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("waiting for crew confirmation");
        }

        @Test
        @DisplayName("4. acceptMission rejects wrong staff before planning")
        void acceptMission_wrongOperator_doesNotPlan() {
            Mission mission = buildMission("m-operator", MissionStatus.WAITING_OPERATOR_ACCEPTANCE);
            when(missionRepository.findById("m-operator")).thenReturn(Optional.of(mission));

            assertThatThrownBy(() -> missionService.acceptMission("m-operator", "op-other"))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("Staff is not assigned to mission");
        }

        @Test
        @DisplayName("8. rejectMission success when WAITING_OPERATOR_ACCEPTANCE")
        void rejectMission_success() {
            Mission mission = buildMission("m-2", MissionStatus.WAITING_OPERATOR_ACCEPTANCE);
            MissionStaffAssignment assignment = staffAssignment(mission, "op-01", "PENDING");
            when(missionStaffAssignmentRepository.findAllByMissionIdAndStaffIdAndIsCurrentTrueOrderByAssignedAtDesc("m-2", "op-01"))
                    .thenReturn(List.of(assignment));
            when(missionRepository.findById("m-2")).thenReturn(Optional.of(mission));
            when(missionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            MissionResponse result = missionService.rejectMission("m-2", "op-01", "Trùng lịch cá nhân");

            assertThat(result.getStatus()).isEqualTo(MissionStatus.READY);
            verify(missionStaffAssignmentRepository).saveAll(any());
        }

        @Test
        @DisplayName("9. rejectMission throws exception when mission not found")
        void rejectMission_notFound_throws() {
            when(missionRepository.findById("m-missing")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> missionService.rejectMission("m-missing", "op-01", "Bận"))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("Mission not found");
        }

        @Test
        @DisplayName("10. rejectMission throws exception when status is invalid")
        void rejectMission_wrongStatus_throws() {
            Mission mission = buildMission("m-2", MissionStatus.IN_FLIGHT);
            when(missionRepository.findById("m-2")).thenReturn(Optional.of(mission));

            assertThatThrownBy(() -> missionService.rejectMission("m-2", "op-01", "Trùng lịch"))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("waiting for crew confirmation");
        }
    }

    // =========================================================================
    // F3.2 – GCS Connect & Digital Pre-flight Gate
    // =========================================================================
    @Nested
    @DisplayName("F3.2 - GCS Connect & Pre-flight Gate")
    class F3_2_GcsConnectAndPreflightGate {

        @Test
        @DisplayName("1. connectGcs success from SCHEDULED status")
        void connectGcs_success_fromScheduled() {
            Mission mission = buildMission("m-gcs", MissionStatus.SCHEDULED);
            Device device = buildDevice("DEV-01", DeviceStatus.AVAILABLE);
            MissionDeviceAssignment mda = new MissionDeviceAssignment();
            mda.setDevice(device);
            mda.setIsCurrent(true);

            User staff = new User();
            staff.setId("staff-01");
            MissionStaffAssignment msa = new MissionStaffAssignment();
            msa.setStaff(staff);
            msa.setIsCurrent(true);
            msa.setAssignedRole(MissionStaffRole.OPERATOR);
            msa.setResponseStatus(StaffResponseStatus.ACCEPTED);

            when(missionRepository.findById("m-gcs")).thenReturn(Optional.of(mission));
            when(missionDeviceAssignmentRepository.findAllByMissionIdAndIsCurrentTrueOrderByCreatedAtDesc("m-gcs"))
                    .thenReturn(List.of(mda));
            when(missionStaffAssignmentRepository.findAllByMissionIdAndIsCurrentTrue("m-gcs"))
                    .thenReturn(List.of(msa));
            when(deviceConnectionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(missionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(deviceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            MissionResponse result = missionService.connectGcs("m-gcs");

            assertThat(result.getStatus()).isEqualTo(MissionStatus.CONNECTED);
            assertThat(device.getStatus()).isEqualTo(DeviceStatus.PREFLIGHT);
        }

        @Test
        @DisplayName("2. connectGcs success from CONNECTED status")
        void connectGcs_success_fromConnected() {
            Mission mission = buildMission("m-gcs2", MissionStatus.CONNECTED);
            Device device = buildDevice("DEV-02", DeviceStatus.PREFLIGHT);
            MissionDeviceAssignment deviceAssignment = new MissionDeviceAssignment();
            deviceAssignment.setDevice(device);
            deviceAssignment.setIsCurrent(true);
            when(missionDeviceAssignmentRepository.findAllByMissionIdAndIsCurrentTrueOrderByCreatedAtDesc("m-gcs2"))
                    .thenReturn(List.of(deviceAssignment));
            when(missionStaffAssignmentRepository.findAllByMissionIdAndIsCurrentTrue("m-gcs2"))
                    .thenReturn(List.of(staffAssignment(mission, "staff-01", MissionStaffRole.OPERATOR, "ACCEPTED")));
            when(deviceConnectionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(missionRepository.findById("m-gcs2")).thenReturn(Optional.of(mission));
            when(missionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            MissionResponse result = missionService.connectGcs("m-gcs2");

            assertThat(result.getStatus()).isEqualTo(MissionStatus.CONNECTED);
        }

        @Test
        @DisplayName("3. connectGcs throws exception when status is invalid (e.g. IN_FLIGHT)")
        void connectGcs_invalidStatus_throws() {
            Mission mission = buildMission("m-gcs3", MissionStatus.IN_FLIGHT);
            when(missionRepository.findById("m-gcs3")).thenReturn(Optional.of(mission));

            assertThatThrownBy(() -> missionService.connectGcs("m-gcs3"))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("SCHEDULED state");
        }

        @Test
        @DisplayName("3b. disconnectGcs updates active GCS session status to DISCONNECTED")
        void disconnectGcs_success_updatesSessionToDisconnected() {
            Mission mission = buildMission("m-disc", MissionStatus.CONNECTED);
            DeviceConnection session = new DeviceConnection();
            session.setConnectionStatus("CONNECTED");
            session.setTelemetryActive(true);

            when(missionRepository.findById("m-disc")).thenReturn(Optional.of(mission));
            when(deviceConnectionRepository.findTopByMissionIdAndConnectionStatusOrderByConnectedAtDesc("m-disc",
                    "CONNECTED"))
                    .thenReturn(Optional.of(session));
            when(deviceConnectionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            MissionResponse response = missionService.disconnectGcs("m-disc", "OPERATOR_EXIT");

            assertThat(response).isNotNull();
            assertThat(session.getConnectionStatus()).isEqualTo("DISCONNECTED");
            assertThat(session.getTelemetryActive()).isFalse();
            assertThat(session.getDisconnectReason()).isEqualTo("OPERATOR_EXIT");
        }

        @Test
        @DisplayName("3c. handleGcsSessionLost updates GCS session status to LOST and triggers RTL (RETURNING)")
        void handleGcsSessionLost_updatesSessionToLost_andTriggersRTL() {
            Mission mission = buildMission("m-lost", MissionStatus.IN_FLIGHT);
            Device device = buildDevice("DEV-01", DeviceStatus.ACTIVE_MISSION);
            MissionDeviceAssignment mda = new MissionDeviceAssignment();
            mda.setDevice(device);
            mda.setIsCurrent(true);
            when(missionDeviceAssignmentRepository.findAllByMissionIdAndIsCurrentTrueOrderByCreatedAtDesc("m-lost"))
                    .thenReturn(List.of(mda));

            DeviceConnection session = new DeviceConnection();
            session.setConnectionStatus("CONNECTED");
            session.setTelemetryActive(true);

            when(missionRepository.findById("m-lost")).thenReturn(Optional.of(mission));
            when(deviceConnectionRepository.findTopByMissionIdAndConnectionStatusOrderByConnectedAtDesc("m-lost",
                    "CONNECTED"))
                    .thenReturn(Optional.of(session));
            when(deviceConnectionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(missionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(deviceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            MissionResponse response = missionService.handleGcsSessionLost("m-lost", "SIGNAL_LOSS");

            assertThat(response).isNotNull();
            assertThat(session.getConnectionStatus()).isEqualTo("LOST");
            assertThat(session.getTelemetryActive()).isFalse();
            assertThat(session.getDisconnectReason()).isEqualTo("SIGNAL_LOSS");
            assertThat(mission.getStatus()).isEqualTo(MissionStatus.RETURNING);
            assertThat(device.getStatus()).isEqualTo(DeviceStatus.RETURNING);
        }

        @Test
        void telemetryReadinessUsesCurrentMissionDevice() {
            Mission mission = buildMission("m-ready", MissionStatus.CONNECTED);
            Device device = buildDevice("DEV-01", DeviceStatus.PREFLIGHT);
            device.setDeviceCode("DEV-01");
            MissionDeviceAssignment assignment = new MissionDeviceAssignment();
            assignment.setDevice(device);

            when(missionRepository.findById("m-ready")).thenReturn(Optional.of(mission));
            when(missionDeviceAssignmentRepository.findAllByMissionIdAndIsCurrentTrueOrderByCreatedAtDesc("m-ready"))
                    .thenReturn(List.of(assignment));

            var readiness = missionService.getTelemetryReadiness("m-ready");

            assertThat(readiness.deviceId()).isEqualTo(device.getId());
            assertThat(readiness.ready()).isFalse();
        }
    }

    // =========================================================================
    // Control Handover
    // =========================================================================
    @Nested
    @DisplayName("Control Handover")
    class ControlHandoverTests {

        @Test
        @DisplayName("5. handoverControl success when READY_TO_FLY")
        void handoverControl_success() {
            Mission mission = buildMission("m-5", MissionStatus.READY_TO_FLY);
            User staff = new User();
            staff.setId("op-new");
            MissionStaffAssignment msa = new MissionStaffAssignment();
            msa.setStaff(staff);
            msa.setIsCurrent(true);

            when(missionRepository.findById("m-5")).thenReturn(Optional.of(mission));
            msa.setAssignedRole(MissionStaffRole.OPERATOR);
            msa.setResponseStatus(StaffResponseStatus.ACCEPTED);
            when(missionStaffAssignmentRepository.findAllByMissionIdAndStaffIdAndIsCurrentTrueOrderByAssignedAtDesc("m-5", "op-new"))
                    .thenReturn(List.of(msa));
            when(missionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            MissionResponse result = missionService.handoverControl("m-5", "op-new");

            verify(controlHandoverRepository, atLeastOnce()).save(any());
        }

        @Test
        @DisplayName("6. handoverControl throws when status is not READY_TO_FLY")
        void handoverControl_invalidStatus_throws() {
            Mission mission = buildMission("m-5", MissionStatus.SCHEDULED);
            when(missionRepository.findById("m-5")).thenReturn(Optional.of(mission));

            assertThatThrownBy(() -> missionService.handoverControl("m-5", "op-new"))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("must be READY_TO_FLY");
        }
    }

    // =========================================================================
    // F3.3 – Takeoff & Execution Lifecycle
    // =========================================================================
    @Nested
    @DisplayName("F3.3 - Takeoff & Flight Lifecycle")
    class F3_3_TakeoffAndFlightLifecycle {

        @Test
        @DisplayName("startMission is blocked until the flight permit is approved")
        void startMission_withoutApprovedPermit_throws() {
            Mission mission = buildMission("m-no-permit", MissionStatus.READY_TO_FLY);
            mission.setFlightPermitStatus(FlightPermitStatus.PENDING);
            when(missionRepository.findById("m-no-permit")).thenReturn(Optional.of(mission));

            assertThatThrownBy(() -> missionService.startMission("m-no-permit"))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("Flight permit must be approved");
        }

        @Test
        @DisplayName("1. startMission success with valid FlightToken")
        void startMission_success_withValidToken() {
            Mission mission = buildMission("m-7", MissionStatus.READY_TO_FLY);
            Device device = buildDevice("DEV-01", DeviceStatus.PREFLIGHT);
            MissionDeviceAssignment mda = new MissionDeviceAssignment();
            mda.setDevice(device);
            when(missionDeviceAssignmentRepository.findFirstByMissionIdOrderByCreatedAtDesc("m-7"))
                    .thenReturn(Optional.of(mda));

            FlightToken token = new FlightToken();
            token.setTokenValue("valid-token");
            token.setMissionId("m-7");
            token.setExpiresAt(Instant.now().plusSeconds(600));
            token.setUsed(false);
            token.setRevoked(false);

            when(missionRepository.findById("m-7")).thenReturn(Optional.of(mission));
            when(flightTokenRepository.findByTokenValue("valid-token")).thenReturn(Optional.of(token));
            when(missionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(deviceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            MissionResponse result = missionService.startMission("m-7", "valid-token");

            assertThat(result.getStatus()).isEqualTo(MissionStatus.IN_FLIGHT);
            assertThat(mission.getActualStartAt()).isNotNull();
            assertThat(device.getStatus()).isEqualTo(DeviceStatus.ACTIVE_MISSION);
            assertThat(token.isUsed()).isTrue();
        }

        @Test
        @DisplayName("2. startMission throws and revokes token when FlightToken is expired")
        void startMission_expiredToken_revokesToken_throws() {
            Mission mission = buildMission("m-7", MissionStatus.READY_TO_FLY);

            FlightToken expiredToken = new FlightToken();
            expiredToken.setTokenValue("expired-token");
            expiredToken.setMissionId("m-7");
            expiredToken.setExpiresAt(Instant.now().minusSeconds(10));
            expiredToken.setUsed(false);
            expiredToken.setRevoked(false);

            when(missionRepository.findById("m-7")).thenReturn(Optional.of(mission));
            when(flightTokenRepository.findByTokenValue("expired-token")).thenReturn(Optional.of(expiredToken));

            assertThatThrownBy(() -> missionService.startMission("m-7", "expired-token"))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("expired or revoked");

            assertThat(expiredToken.isRevoked()).isTrue();
        }

        @Test
        @DisplayName("3. startMission throws exception when provided FlightToken does not exist")
        void startMission_tokenNotFound_throws() {
            Mission mission = buildMission("m-7", MissionStatus.READY_TO_FLY);
            when(missionRepository.findById("m-7")).thenReturn(Optional.of(mission));
            when(flightTokenRepository.findByTokenValue("invalid-token")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> missionService.startMission("m-7", "invalid-token"))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("Invalid flight access token");
        }

        @Test
        @DisplayName("4. startMission without explicit token param auto-resolves valid un-used token for mission")
        void startMission_withoutToken_success() {
            Mission mission = buildMission("m-7", MissionStatus.READY_TO_FLY);
            Device device = buildDevice("DEV-01", DeviceStatus.PREFLIGHT);
            MissionDeviceAssignment mda = new MissionDeviceAssignment();
            mda.setDevice(device);
            when(missionDeviceAssignmentRepository.findFirstByMissionIdOrderByCreatedAtDesc("m-7"))
                    .thenReturn(Optional.of(mda));

            FlightToken token = new FlightToken();
            token.setTokenValue("auto-token");
            token.setExpiresAt(Instant.now().plusSeconds(600));
            token.setUsed(false);
            token.setRevoked(false);

            when(missionRepository.findById("m-7")).thenReturn(Optional.of(mission));
            when(flightTokenRepository.findAllByMissionIdAndUsedFalseAndRevokedFalseOrderByIssuedAtDesc("m-7"))
                    .thenReturn(List.of(token));
            when(missionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(deviceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            MissionResponse result = missionService.startMission("m-7");

            assertThat(result.getStatus()).isEqualTo(MissionStatus.IN_FLIGHT);
            assertThat(mission.getActualStartAt()).isNotNull();
            assertThat(token.isUsed()).isTrue();
        }
    }

    // =========================================================================
    // F3.4 & F3.5 - Postflight, Completion & Failure
    // =========================================================================
    @Nested
    @DisplayName("F3.4 & F3.5 - Postflight, Completion & Failure")
    class F3_4_And_F3_5_PostflightCompletionAndFailure {

        @Test
        @DisplayName("1. startPostflightChecking success when RETURNING")
        void startPostflightChecking_success() {
            Mission mission = buildMission("m-pf", MissionStatus.RETURNING);
            when(missionRepository.findById("m-pf")).thenReturn(Optional.of(mission));
            when(missionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            MissionResponse result = missionService.startPostDeviceChecking("m-pf");

            assertThat(result.getStatus()).isEqualTo(MissionStatus.POSTFLIGHT_CHECKING);
        }

        @Test
        @DisplayName("2. startPostflightChecking throws exception when status is not RETURNING")
        void startPostflightChecking_invalidStatus_throws() {
            Mission mission = buildMission("m-pf", MissionStatus.IN_FLIGHT);
            when(missionRepository.findById("m-pf")).thenReturn(Optional.of(mission));

            assertThatThrownBy(() -> missionService.startPostDeviceChecking("m-pf"))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("must be RETURNING");
        }

        @Test
        @DisplayName("3. completeMission success after postcheck is ready for review")
        void completeMission_success() {
            Mission mission = buildMission("m-8", MissionStatus.PENDING_REVIEW);
            Device device = buildDevice("DEV-01", DeviceStatus.AVAILABLE);
            MissionDeviceAssignment mda = new MissionDeviceAssignment();
            mda.setDevice(device);
            when(missionDeviceAssignmentRepository.findFirstByMissionIdOrderByCreatedAtDesc("m-8"))
                    .thenReturn(Optional.of(mda));

            when(missionRepository.findById("m-8")).thenReturn(Optional.of(mission));
            when(missionRepository.findByIdForUpdate("m-8")).thenReturn(Optional.of(mission));
            when(postDeviceCheckRepository.findFirstByMissionIdOrderByCreatedAtDesc("m-8"))
                    .thenReturn(Optional.of(new PersistedPostDeviceCheck()));
            when(missionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(deviceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            MissionResponse result = missionService.completeMission("m-8");

            assertThat(result.getStatus()).isEqualTo(MissionStatus.COMPLETED);
            assertThat(mission.getCompletedAt()).isNotNull();
            assertThat(device.getStatus()).isEqualTo(DeviceStatus.AVAILABLE);
        }

        @Test
        void incompleteBusinessChecklistBlocksAcceptanceWithoutReleasingReportingAssignments() {
            Mission mission = buildMission("m-business", MissionStatus.PENDING_REVIEW);
            var order = new com.ondemandmonitoring.order.domain.Order(); order.setId("o"); mission.setOrder(order);
            when(orderRepository.findByIdForUpdate("o")).thenReturn(Optional.of(order));
            Device device = buildDevice("DEV-01", DeviceStatus.RETURNING);
            var deviceAssignment = new MissionDeviceAssignment(); deviceAssignment.setDevice(device);
            var staffAssignment = new MissionStaffAssignment();
            deviceAssignment.setIsCurrent(true);
            staffAssignment.setIsCurrent(true);
            when(missionRepository.findById(mission.getId())).thenReturn(Optional.of(mission));
            when(missionRepository.findByIdForUpdate(mission.getId())).thenReturn(Optional.of(mission));
            when(missionRepository.save(any())).thenAnswer(call -> call.getArgument(0));
            when(missionDeviceAssignmentRepository.findFirstByMissionIdOrderByCreatedAtDesc(mission.getId()))
                    .thenReturn(Optional.of(deviceAssignment));
            when(missionDeviceAssignmentRepository.findByMissionId(mission.getId())).thenReturn(List.of(deviceAssignment));
            when(missionStaffAssignmentRepository.findByMissionId(mission.getId())).thenReturn(List.of(staffAssignment));
            when(postDeviceCheckRepository.findFirstByMissionIdOrderByCreatedAtDesc(mission.getId()))
                    .thenReturn(Optional.of(new PersistedPostDeviceCheck()));
            var resultRepository = mock(com.ondemandmonitoring.mission.repository.MissionResultRepository.class);
            var itemRepository = mock(com.ondemandmonitoring.order.repository.OrderChecklistItemRepository.class);
            var executionRepository = mock(com.ondemandmonitoring.mission.repository.MissionChecklistExecutionRepository.class);
            var auth = mock(IMissionAuthorizationService.class);
            when(auth.canExecuteMonitoringChecklist(mission.getId())).thenReturn(true);
            var item = new com.ondemandmonitoring.order.domain.OrderChecklistItem(); item.setId("pending-item"); item.setOrder(order);
            when(itemRepository.findAllByOrderIdInOrderByDisplayOrderAscIdAsc(List.of("o"))).thenReturn(List.of(item));
            var checklist = new com.ondemandmonitoring.mission.service.impl.MissionChecklistExecutionServiceImpl(
                    missionRepository, mock(com.ondemandmonitoring.mission.service.IMissionChecklistScopeService.class), executionRepository, resultRepository, auth, authenticatedUserResolver,
                    org.mapstruct.factory.Mappers.getMapper(com.ondemandmonitoring.mission.mapper.MissionChecklistExecutionMapper.class), com.ondemandmonitoring.mission.service.EvidenceTestFixture.emptyService());
            assertThat(checklist.isReadyForSubmission(mission)).isFalse();
            var results = new com.ondemandmonitoring.mission.service.impl.MissionResultService(
                    resultRepository, missionRepository, mock(com.ondemandmonitoring.media.repository.MediaAssetRepository.class),
                    mock(com.ondemandmonitoring.media.service.IMediaAssetService.class),
                    mock(com.ondemandmonitoring.mission.mapper.MissionResultMapper.class),
                    new com.ondemandmonitoring.order.service.impl.OrderExecutionCompletionPolicy(orderRepository, missionRepository, resultRepository, checklist,
                            mock(com.ondemandmonitoring.order.repository.OrderChecklistItemRepository.class),
                            mock(com.ondemandmonitoring.mission.repository.MissionChecklistAssignmentRepository.class),
                            new com.ondemandmonitoring.mission.service.impl.ExecutionPlanCoverageService()),
                    checklist, authenticatedUserResolver, auth);
            org.springframework.test.util.ReflectionTestUtils.setField(missionService, "missionResultService", results);
            org.springframework.test.util.ReflectionTestUtils.setField(missionService, "checklistExecutionService", checklist);

            assertThatThrownBy(() -> missionService.completeMission(mission.getId()))
                    .isInstanceOf(ApiException.class)
                    .extracting(exception -> ((ApiException) exception).getErrorCode())
                    .isEqualTo(ErrorCode.CHECKLIST_NOT_READY);
            assertThat(deviceAssignment.getReleasedAt()).isNull();
            assertThat(staffAssignment.getReleasedAt()).isNull();
            verify(resultRepository, never()).save(any());
            assertThat(mission.getStatus()).isEqualTo(MissionStatus.PENDING_REVIEW);
        }

        @Test
        @DisplayName("4. completeMission throws exception when status is SCHEDULED (fully invalid for completion)")
        void completeMission_invalidStatus_throws() {
            Mission mission = buildMission("m-8", MissionStatus.SCHEDULED);
            when(missionRepository.findById("m-8")).thenReturn(Optional.of(mission));
            when(missionRepository.findByIdForUpdate("m-8")).thenReturn(Optional.of(mission));

            assertThatThrownBy(() -> missionService.completeMission("m-8"))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("PENDING_REVIEW");
        }

        @Test
        void completeMission_rejectsMissingPostflightInspection() {
            Mission mission = buildMission("m-8", MissionStatus.PENDING_REVIEW);
            when(missionRepository.findById("m-8")).thenReturn(Optional.of(mission));
            when(missionRepository.findByIdForUpdate("m-8")).thenReturn(Optional.of(mission));
            when(postDeviceCheckRepository.findFirstByMissionIdOrderByCreatedAtDesc("m-8"))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> missionService.completeMission("m-8"))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("post-flight inspection");
        }

        @Test
        @DisplayName("5. failMission cannot bypass safe-stop for an airborne mission")
        void failMission_airborneRequiresSafeStopWithoutReleasingResources() {
            Mission mission = buildMission("m-9", MissionStatus.IN_FLIGHT);
            Device device = buildDevice("DEV-01", DeviceStatus.ACTIVE_MISSION);
            MissionDeviceAssignment mda = new MissionDeviceAssignment();
            mda.setDevice(device);
            when(missionDeviceAssignmentRepository.findFirstByMissionIdOrderByCreatedAtDesc("m-9"))
                    .thenReturn(Optional.of(mda));

            when(missionRepository.findById("m-9")).thenReturn(Optional.of(mission));
            when(missionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(deviceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            assertThatThrownBy(() -> missionService.failMission("m-9", "GPS lost"))
                    .isInstanceOf(ApiException.class).extracting(e -> ((ApiException)e).getErrorCode())
                    .isEqualTo(com.ondemandmonitoring.common.exception.ErrorCode.MISSION_CANCELLATION_REQUIRES_SAFE_STOP);
            assertThat(mission.getStatus()).isEqualTo(MissionStatus.IN_FLIGHT);
            assertThat(device.getStatus()).isEqualTo(DeviceStatus.ACTIVE_MISSION);
            verify(resourceTimeLockRepository,never()).saveAllAndFlush(anyIterable());
        }

        @Test
        @DisplayName("6. updatePostFlightStatus success updates device status and completes mission")
        void updatePostFlightStatus_success() {
            Mission mission = buildMission("m-post", MissionStatus.POSTFLIGHT_CHECKING);
            Device device = buildDevice("DEV-01", DeviceStatus.RETURNING);
            MissionDeviceAssignment mda = new MissionDeviceAssignment();
            mda.setDevice(device);
            when(missionDeviceAssignmentRepository.findFirstByMissionIdOrderByCreatedAtDesc("m-post"))
                    .thenReturn(Optional.of(mda));
            mda.setIsCurrent(true);
            when(missionDeviceAssignmentRepository.findByMissionId("m-post")).thenReturn(List.of(mda));
            var crew = new MissionStaffAssignment(); crew.setIsCurrent(true); var staff = new User(); staff.setId("postflight-staff"); crew.setStaff(staff);
            when(missionStaffAssignmentRepository.findByMissionId("m-post")).thenReturn(List.of(crew));
            var schedule = new com.ondemandmonitoring.userschedule.domain.UserSchedule();
            schedule.setScheduleType(com.ondemandmonitoring.userschedule.enums.UserScheduleType.MISSION);
            schedule.setStatus(com.ondemandmonitoring.userschedule.enums.UserScheduleStatus.SCHEDULED);
            when(userScheduleRepository.findByReferenceId("m-post")).thenReturn(List.of(schedule));
            var deviceLock = new com.ondemandmonitoring.mission.domain.ResourceTimeLock(); deviceLock.setResourceId(device.getId());
            var staffLock = new com.ondemandmonitoring.mission.domain.ResourceTimeLock(); staffLock.setResourceId(staff.getId());
            when(resourceTimeLockRepository.findByMissionId("m-post")).thenReturn(List.of(deviceLock,staffLock));

            when(missionRepository.findById("m-post")).thenReturn(Optional.of(mission));
            when(missionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(deviceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            MissionResponse result = missionService.updatePostFlightStatus("m-post", DeviceStatus.AVAILABLE,
                    "Cánh quạt bình thường");

            assertThat(device.getStatus()).isEqualTo(DeviceStatus.AVAILABLE);
            assertThat(result.getStatus()).isEqualTo(MissionStatus.PENDING_REVIEW);
            assertThat(deviceLock.getReleasedAt()).isNotNull(); assertThat(staffLock.getReleasedAt()).isNotNull();
            assertThat(deviceLock.getReleaseReason()).isEqualTo("POSTFLIGHT_COMPLETE");
            verify(resourceTimeLockRepository,never()).deleteAll(anyIterable());
            assertThat(schedule.getStatus()).isEqualTo(com.ondemandmonitoring.userschedule.enums.UserScheduleStatus.COMPLETED);
            assertThat(mission.getActualEndAt()).isNotNull();
            assertThat(mda.getReleasedAt()).isNull(); assertThat(crew.getReleasedAt()).isNull();
        }

        @Test
        @DisplayName("7. updatePostFlightStatus throws exception if mission has no assigned device")
        void updatePostFlightStatus_noDeviceAssigned_throws() {
            Mission mission = buildMission("m-nodev", MissionStatus.POSTFLIGHT_CHECKING);
            when(missionDeviceAssignmentRepository.findFirstByMissionIdOrderByCreatedAtDesc("m-nodev"))
                    .thenReturn(Optional.empty());

            when(missionRepository.findById("m-nodev")).thenReturn(Optional.of(mission));

            assertThatThrownBy(() -> missionService.updatePostFlightStatus("m-nodev", DeviceStatus.AVAILABLE, "Notes"))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("has no assigned");
        }
    }

    // =========================================================================
    // Acceptance Criteria Tests
    // =========================================================================
    @Nested
    @DisplayName("AC – Resource Assignment & Safety")
    class AC_ResourceAssignmentAndSafety {

        @Test
        @DisplayName("AC-1. assignDevice throws when device is under MAINTENANCE")
        void assignDevice_maintenanceDevice_blocked() {
            Mission mission = buildMission("m-assign", MissionStatus.READY);
            mission.setScheduledStartAt(Instant.now().plusSeconds(3600));
            mission.setScheduledEndAt(Instant.now().plusSeconds(7200));
            Device maintenanceDevice = buildDevice("DEV-BROKEN", DeviceStatus.MAINTENANCE);

            when(missionRepository.findById("m-assign")).thenReturn(Optional.of(mission));
            when(deviceRepository.findById("DEV-BROKEN-id")).thenReturn(Optional.of(maintenanceDevice));

            AssignDeviceRequest request = new AssignDeviceRequest();
            request.setDeviceId("DEV-BROKEN-id");

            assertThatThrownBy(() -> missionService.assignDevice("m-assign", request))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("MAINTENANCE");
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Mission buildMission(String id, MissionStatus status) {
        Mission m = new Mission();
        m.setId(id);
        m.setMissionCode("MC-" + id);
        m.setStatus(status);
        m.setFlightPermitStatus(FlightPermitStatus.APPROVED);
        var order = new com.ondemandmonitoring.order.domain.Order(); order.setId("order-" + id);
        m.setOrder(order);
        lenient().when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        return m;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(MissionStaffRole.class)
    void missingAnyOperationalRolePreventsScheduling(MissionStaffRole missing) {
        var mission = buildMission("crew", MissionStatus.WAITING_CREW_CONFIRMATION);
        var crew = java.util.Arrays.stream(MissionStaffRole.values())
                .filter(role -> role != missing)
                .map(role -> staffAssignment(mission, role.name(), role, "ACCEPTED")).toList();
        when(missionStaffAssignmentRepository.findAllByMissionIdAndIsCurrentTrue("crew")).thenReturn(crew);
        assertThat((Boolean) org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                missionService, "hasRequiredCrewAssigned", "crew")).isFalse();
        assertThat((Boolean) org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                missionService, "isRequiredCrewAccepted", "crew")).isFalse();
    }

    @Test
    void allFourRolesMustAcceptBeforeScheduling() {
        var mission = buildMission("crew", MissionStatus.WAITING_CREW_CONFIRMATION);
        var crew = java.util.Arrays.stream(MissionStaffRole.values())
                .map(role -> staffAssignment(mission, role.name(), role, "ACCEPTED")).toList();
        when(missionStaffAssignmentRepository.findAllByMissionIdAndIsCurrentTrue("crew")).thenReturn(crew);
        assertThat((Boolean) org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                missionService, "hasRequiredCrewAssigned", "crew")).isTrue();
        assertThat((Boolean) org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                missionService, "isRequiredCrewAccepted", "crew")).isTrue();
        crew.getFirst().setResponseStatus(StaffResponseStatus.PENDING);
        assertThat((Boolean) org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                missionService, "isRequiredCrewAccepted", "crew")).isFalse();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"released", "inactive", "historical"})
    void unusableAcceptedCrewMustNotScheduleMission(String state) {
        var mission = buildMission("crew-invalid", MissionStatus.WAITING_CREW_CONFIRMATION);
        var crew = java.util.Arrays.stream(MissionStaffRole.values())
                .map(role -> staffAssignment(mission, role.name(), role, "ACCEPTED")).toList();
        var operator = crew.stream().filter(entry -> entry.getAssignedRole() == MissionStaffRole.OPERATOR)
                .findFirst().orElseThrow();
        switch (state) {
            case "released" -> operator.setReleasedAt(Instant.now());
            case "inactive" -> operator.getStaff().setIsActive(false);
            case "historical" -> operator.setIsCurrent(false);
            default -> throw new AssertionError(state);
        }
        var pilot = crew.stream().filter(entry -> entry.getAssignedRole() == MissionStaffRole.PILOT)
                .findFirst().orElseThrow();
        pilot.setResponseStatus(StaffResponseStatus.PENDING);
        when(missionRepository.findById("crew-invalid")).thenReturn(Optional.of(mission));
        when(missionStaffAssignmentRepository.findAllByMissionIdAndStaffIdAndIsCurrentTrueOrderByAssignedAtDesc(
                "crew-invalid", "PILOT")).thenReturn(List.of(pilot));
        when(missionStaffAssignmentRepository.findAllByMissionIdAndIsCurrentTrue("crew-invalid"))
                .thenReturn(crew);
        when(missionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(missionService.acceptMission("crew-invalid", "PILOT").getStatus())
                .isEqualTo(MissionStatus.WAITING_CREW_CONFIRMATION);
        assertThat((Boolean) org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                missionService, "hasRequiredCrewAssigned", "crew-invalid")).isFalse();
    }

    @Test
    void postflightAcceptanceReleaseAndNonCompliantBusinessReportRemainSeparate() {
        var mission = buildMission("integrated", MissionStatus.POSTFLIGHT_CHECKING);
        var order = new com.ondemandmonitoring.order.domain.Order(); order.setId("order");
        order.setOrderStatus(com.ondemandmonitoring.order.enums.OrderStatus.APPROVED); mission.setOrder(order);
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        order.setExecutionPlanStatus(com.ondemandmonitoring.order.enums.ExecutionPlanStatus.FINALIZED);
        order.setExecutionPlanRevision(1);
        var device = buildDevice("device", DeviceStatus.RETURNING);
        var deviceAssignment = new MissionDeviceAssignment(); deviceAssignment.setDevice(device); deviceAssignment.setIsCurrent(true);
        var crew = java.util.Arrays.stream(MissionStaffRole.values()).map(role -> {
            var entry = staffAssignment(mission, role.name(), role, "ACCEPTED");
            entry.getStaff().setIsActive(true);
            entry.getStaff().setRole(Role.builder().code(RoleCode.STAFF).active(true).build()); return entry;
        }).toList();
        var replaced = staffAssignment(mission, "old-inspector", MissionStaffRole.INSPECTOR, "ACCEPTED");
        replaced.setIsCurrent(false); replaced.setReleasedAt(Instant.now()); replaced.setReleaseReason("REPLACED");
        var history = new java.util.ArrayList<>(crew); history.add(replaced);
        when(missionRepository.findById("integrated")).thenReturn(Optional.of(mission));
        when(missionRepository.findByIdForUpdate("integrated")).thenReturn(Optional.of(mission));
        when(missionRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(missionDeviceAssignmentRepository.findFirstByMissionIdOrderByCreatedAtDesc("integrated"))
                .thenReturn(Optional.of(deviceAssignment));
        when(missionDeviceAssignmentRepository.findByMissionId("integrated")).thenReturn(List.of(deviceAssignment));
        when(missionStaffAssignmentRepository.findByMissionId("integrated")).thenReturn(history);
        when(postDeviceCheckRepository.findFirstByMissionIdOrderByCreatedAtDesc("integrated"))
                .thenReturn(Optional.of(new PersistedPostDeviceCheck()));
        var policy = new com.ondemandmonitoring.mission.service.impl.MissionAuthorizationServiceImpl(
                missionStaffAssignmentRepository, missionRepository, authenticatedUserResolver, mock(com.ondemandmonitoring.mission.repository.MissionResultRepository.class), ScopeTestFixture.validScope());
        var items = mock(com.ondemandmonitoring.order.repository.OrderChecklistItemRepository.class);
        var executions = mock(com.ondemandmonitoring.mission.repository.MissionChecklistExecutionRepository.class);
        var resultRepo = mock(com.ondemandmonitoring.mission.repository.MissionResultRepository.class);
        var item = new com.ondemandmonitoring.order.domain.OrderChecklistItem(); item.setId("item"); item.setOrder(order);
        item.setEvidencePolicyVersion(0); item.setMinimumEvidenceCount(0);
        var execution = new com.ondemandmonitoring.mission.domain.MissionChecklistExecution();
        execution.setId("execution"); execution.setVersion(0L); execution.setMissionId(mission.getId());
        execution.setMission(mission); execution.setOrderId(order.getId()); execution.setOrderChecklistItemId(item.getId());
        execution.setOrderChecklistItem(item); execution.setExecutionStatus(com.ondemandmonitoring.mission.enums.ChecklistExecutionStatus.PENDING);
        ScopeTestFixture.link(execution, mission);
        when(items.findAllByOrderIdInOrderByDisplayOrderAscIdAsc(List.of("order"))).thenReturn(List.of(item));
        when(executions.findOrderedByMissionId(mission.getId())).thenReturn(List.of(execution));
        when(executions.findById(execution.getId())).thenReturn(Optional.of(execution));
        when(executions.findByIdForUpdate(execution.getId())).thenReturn(Optional.of(execution));
        when(executions.updateIfVersionMatches(anyString(), anyLong(), anyString(), anyString(),
                nullable(String.class), nullable(String.class), nullable(Instant.class), nullable(Instant.class),
                nullable(String.class), any(Instant.class))).thenAnswer(call -> {
                    execution.setExecutionStatus(com.ondemandmonitoring.mission.enums.ChecklistExecutionStatus.valueOf(call.getArgument(2)));
                    execution.setAssessmentStatus(com.ondemandmonitoring.mission.enums.ChecklistAssessmentStatus.valueOf(call.getArgument(3)));
                    execution.setObservation(call.getArgument(4));
                    execution.setUnableToVerifyReason(call.getArgument(5));
                    execution.setStartedAt(call.getArgument(6));
                    execution.setCompletedAt(call.getArgument(7));
                    execution.setLastModifiedBy(call.getArgument(8));
                    execution.setUpdatedAt(call.getArgument(9));
                    execution.setVersion(execution.getVersion() + 1);
                    return 1;
                });
        when(executions.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        var checklist = new com.ondemandmonitoring.mission.service.impl.MissionChecklistExecutionServiceImpl(
                missionRepository, ScopeTestFixture.validScope(), executions, resultRepo, policy, authenticatedUserResolver,
                org.mapstruct.factory.Mappers.getMapper(com.ondemandmonitoring.mission.mapper.MissionChecklistExecutionMapper.class), com.ondemandmonitoring.mission.service.EvidenceTestFixture.emptyService());
        var resultMapper = mock(com.ondemandmonitoring.mission.mapper.MissionResultMapper.class);
        var results = new com.ondemandmonitoring.mission.service.impl.MissionResultService(
                resultRepo, missionRepository, mock(com.ondemandmonitoring.media.repository.MediaAssetRepository.class),
                mock(com.ondemandmonitoring.media.service.IMediaAssetService.class), resultMapper,
                new com.ondemandmonitoring.order.service.impl.OrderExecutionCompletionPolicy(orderRepository, missionRepository, resultRepo, checklist,
                        mock(com.ondemandmonitoring.order.repository.OrderChecklistItemRepository.class),
                        mock(com.ondemandmonitoring.mission.repository.MissionChecklistAssignmentRepository.class),
                        new com.ondemandmonitoring.mission.service.impl.ExecutionPlanCoverageService()),
                checklist, authenticatedUserResolver, policy);
        when(resultRepo.save(any())).thenAnswer(call -> {
            com.ondemandmonitoring.mission.domain.MissionResult result = call.getArgument(0); result.setId("result");
            when(resultRepo.findByMissionId(mission.getId())).thenReturn(Optional.of(result));
            when(resultRepo.findByMissionIds(List.of(mission.getId()))).thenReturn(List.of(result));
            when(resultRepo.findById("result")).thenReturn(Optional.of(result));
            when(resultRepo.findMissionIdByResultId("result")).thenReturn(Optional.of(mission.getId())); return result;
        });
        org.springframework.test.util.ReflectionTestUtils.setField(missionService, "missionResultService", results);
        var maintainer = crew.stream().filter(entry -> entry.getAssignedRole() == MissionStaffRole.MAINTAINER).findFirst().orElseThrow();
        when(authenticatedUserResolver.getCurrentUser()).thenReturn(maintainer.getStaff());
        // Actual postflight service path; result must not exist before Inspector acceptance.
        missionService.updatePostFlightStatus(mission.getId(), DeviceStatus.AVAILABLE, "Inspection passed");
        assertThat(mission.getStatus()).isEqualTo(MissionStatus.PENDING_REVIEW);
        assertThat(deviceAssignment.getReleasedAt()).isNull(); verify(resultRepo, never()).save(any());
        // The inspector owns the final acceptance package, including the checklist assessment.
        var operator = crew.stream().filter(entry -> entry.getAssignedRole() == MissionStaffRole.OPERATOR).findFirst().orElseThrow();
        var checklistActor = crew.stream().filter(entry -> entry.getAssignedRole() == MissionStaffRole.INSPECTOR).findFirst().orElseThrow();
        when(authenticatedUserResolver.getCurrentUser()).thenReturn(checklistActor.getStaff());
        when(authenticatedUserResolver.getCurrentUserId()).thenReturn(checklistActor.getStaff().getId());
        var update = new com.ondemandmonitoring.mission.dto.request.ChecklistExecutionUpdateRequest(); update.setExpectedVersion(0L);
        update.setExecutionStatus(com.ondemandmonitoring.mission.enums.ChecklistExecutionStatus.COMPLETED);
        update.setAssessmentStatus(com.ondemandmonitoring.mission.enums.ChecklistAssessmentStatus.NON_COMPLIANT);
        checklist.update(mission.getId(), execution.getId(), update);
        assertThat(mission.getStatus()).isEqualTo(MissionStatus.PENDING_REVIEW);
        var inspector = crew.stream().filter(entry -> entry.getAssignedRole() == MissionStaffRole.INSPECTOR).findFirst().orElseThrow();
        when(authenticatedUserResolver.getCurrentUser()).thenReturn(inspector.getStaff());
        assertThat(policy.canCompleteMission(mission.getId())).isTrue(); missionService.completeMission(mission.getId());
        var result = resultRepo.findByMissionId(mission.getId()).orElseThrow();
        assertThat(result.getApprovalStatus()).isEqualTo(com.ondemandmonitoring.mission.enums.MissionResultApprovalStatus.DRAFT);
        assertThat(result.getSubmittedAt()).isNull(); assertThat(deviceAssignment.getReleasedAt()).isNotNull();
        assertThat(crew).allMatch(entry -> "MISSION_COMPLETE".equals(entry.getReleaseReason()));
        assertThat(replaced.getReleaseReason()).isEqualTo("REPLACED");
        results.upsert(mission.getId(), new com.ondemandmonitoring.mission.dto.request.MissionResultRequest());
        when(authenticatedUserResolver.getCurrentUser()).thenReturn(operator.getStaff());
        assertThatThrownBy(() -> results.upsert(mission.getId(), new com.ondemandmonitoring.mission.dto.request.MissionResultRequest()))
                .isInstanceOf(ApiException.class);
        assertThat(result.getApprovalStatus()).isEqualTo(com.ondemandmonitoring.mission.enums.MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL);
        assertThat(order.getOrderStatus()).isEqualTo(com.ondemandmonitoring.order.enums.OrderStatus.APPROVED);
        var manager = User.builder().isActive(true).role(Role.builder().code(RoleCode.MANAGER).active(true).build()).build();
        manager.setId("manager"); when(authenticatedUserResolver.getCurrentUser()).thenReturn(manager);
        when(authenticatedUserResolver.getCurrentUserId()).thenReturn(manager.getId());
        when(resultRepo.findOrderIdByResultId(result.getId())).thenReturn(Optional.of(order.getId()));
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        when(missionRepository.findIdsByOrderIdOrderById(order.getId())).thenReturn(List.of(mission.getId()));
        when(resultRepo.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        results.approve(result.getId(), new com.ondemandmonitoring.mission.dto.request.MissionResultReviewRequest());
        assertThat(order.getOrderStatus()).isEqualTo(com.ondemandmonitoring.order.enums.OrderStatus.COMPLETED);
        assertThat(mission.getStatus()).isEqualTo(MissionStatus.COMPLETED);
    }

    private MissionStaffAssignment staffAssignment(Mission mission, String staffId, String status) {
        return staffAssignment(mission, staffId, MissionStaffRole.PILOT, status);
    }

    private MissionStaffAssignment staffAssignment(
            Mission mission,
            String staffId,
            MissionStaffRole role,
            String status) {
        MissionStaffAssignment assignment = new MissionStaffAssignment();
        assignment.setMission(mission);
        User staff = new User();
        staff.setId(staffId);
        assignment.setStaff(staff);
        assignment.setAssignedRole(role);
        assignment.setResponseStatus(StaffResponseStatus.valueOf(status));
        assignment.setIsCurrent(true);
        return assignment;
    }

    private Device buildDevice(String code, DeviceStatus status) {
        Device d = new Device();
        d.setId(code + "-id");
        d.setDeviceCode(code);
        d.setStatus(status);
        return d;
    }
}
