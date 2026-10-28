package com.ondemandmonitoring.mission.service;

import com.ondemandmonitoring.devicecheck.repository.PersistedPostDeviceCheckRepository;
import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.dto.response.MissionSafeStopResponse;
import com.ondemandmonitoring.mission.enums.*;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.impl.MissionSafeStopServiceImpl;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.enums.*;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import com.ondemandmonitoring.userschedule.repository.UserScheduleRepository;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MissionSafeStopReadModelTest {
    MissionRepository missions;
    MissionResultRepository results;
    MissionStaffAssignmentRepository crew;
    DeviceConnectionRepository connections;
    MissionDeviceAssignmentRepository devices;
    FlightTokenRepository tokens;
    UserScheduleRepository schedules;
    IResourceReservationService resources;
    PersistedPostDeviceCheckRepository inspections;
    AuthenticatedUserResolver actor;
    MissionSafeStopServiceImpl service;
    Mission mission;
    Order order;

    @BeforeEach void setup() {
        missions = mock(MissionRepository.class); results = mock(MissionResultRepository.class);
        crew = mock(MissionStaffAssignmentRepository.class); connections = mock(DeviceConnectionRepository.class);
        devices = mock(MissionDeviceAssignmentRepository.class); tokens = mock(FlightTokenRepository.class);
        schedules = mock(UserScheduleRepository.class); resources = mock(IResourceReservationService.class);
        inspections = mock(PersistedPostDeviceCheckRepository.class); actor = mock(AuthenticatedUserResolver.class);
        service = new MissionSafeStopServiceImpl(mock(OrderRepository.class), missions, results, devices, crew,
                connections, tokens, schedules, inspections, resources, actor);
        order = new Order(); order.setId("order"); order.setOrderStatus(OrderStatus.APPROVED);
        order.setExecutionPlanStatus(ExecutionPlanStatus.DRAFT); order.setExecutionPlanRevision(3);
        mission = new Mission(); mission.setId("m1"); mission.setOrder(order);
        mission.setStatus(MissionStatus.PLANNING);
        when(missions.findById("m1")).thenReturn(Optional.of(mission));
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    User authenticate(RoleCode role, String id) {
        var user = User.builder().isActive(true).role(Role.builder().code(role).active(true).build()).build(); user.setId(id);
        when(actor.getCurrentUser()).thenReturn(user);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(id, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
        return user;
    }
    void requested(boolean landing) {
        mission.setSafeStopStatus(MissionSafeStopStatus.REQUESTED); mission.setSafeStopRequestedAt(Instant.now());
        mission.setSafeStopRequiresLanding(landing); mission.setSafeStopRequestedBy("manager"); mission.setSafeStopReason("Weather");
    }
    void assign(User user, MissionStaffRole role) {
        when(crew.findByMissionId("m1")).thenReturn(List.of(MissionStaffAssignment.builder().mission(mission).staff(user)
                .assignedRole(role).isCurrent(true).responseStatus(MissionStaffAssignmentStatus.ACCEPTED).build()));
    }
    void assertNone(MissionSafeStopResponse response) {
        assertFalse(response.canRequestSafeStop()); assertFalse(response.canConfirmSafeStop());
        assertFalse(response.canCleanupSafeStop()); assertFalse(response.canCancelMission());
    }

    @ParameterizedTest @EnumSource(value=RoleCode.class, names={"MANAGER","ADMIN"})
    void managerAdminCapabilitiesUseExistingGuardsAndNeverConfirmLanding(RoleCode role) {
        authenticate(role,"manager"); requested(true); mission.setStatus(MissionStatus.IN_FLIGHT);
        order.setExecutionPlanStatus(ExecutionPlanStatus.FINALIZED);
        var response = service.get("m1");
        assertFalse(response.canRequestSafeStop(), "Plan must be amended to DRAFT before requesting safe-stop");
        order.setExecutionPlanStatus(ExecutionPlanStatus.DRAFT);
        response = service.get("m1");
        assertTrue(response.canRequestSafeStop()); assertFalse(response.canConfirmSafeStop());
        assertFalse(response.canCleanupSafeStop()); assertFalse(response.canCancelMission());
        assertEquals("manager", response.requestedBy()); assertEquals("Weather", response.reason());
        mission.setStatus(MissionStatus.PENDING_REVIEW); mission.setSafeStopStatus(MissionSafeStopStatus.READY_FOR_CANCELLATION);
        mission.setSafeStopConfirmedAt(Instant.now()); mission.setSafeStopCleanedAt(Instant.now());
        order.setExecutionPlanStatus(ExecutionPlanStatus.DRAFT); // Manager opens an amendment before cancellation.
        response = service.get("m1");
        assertTrue(response.canCleanupSafeStop(),"cleanup replay is intentionally idempotent"); assertTrue(response.canCancelMission());
        assertFalse(response.canConfirmSafeStop());
    }
    @Test void flownMissionRequiresOwnAcceptedPilotAndSafeLifecycle() {
        var user=authenticate(RoleCode.STAFF,"pilot"); assign(user,MissionStaffRole.PILOT); requested(true);
        mission.setStatus(MissionStatus.IN_FLIGHT); assertFalse(service.get("m1").canConfirmSafeStop());
        mission.setStatus(MissionStatus.PENDING_REVIEW); assertTrue(service.get("m1").canConfirmSafeStop());
        assertFalse(service.get("m1").canCleanupSafeStop()); assertFalse(service.get("m1").canCancelMission());
        mission.setSafeStopConfirmedAt(Instant.now()); mission.setSafeStopStatus(MissionSafeStopStatus.IN_PROGRESS);
        assertTrue(service.get("m1").canConfirmSafeStop(),"existing confirmation replay remains permitted");
    }

    @ParameterizedTest @EnumSource(value=MissionStatus.class, names={"READY_TO_FLY","CONNECTED","PREFLIGHT_CHECKING","POSTFLIGHT_CHECKING"})
    void cannotConfirmBeforeLifecycleIsQuiescent(MissionStatus status) {
        var pilot=authenticate(RoleCode.STAFF,"pilot"); assign(pilot,MissionStaffRole.PILOT); requested(false);
        mission.setStatus(status);
        assertFalse(service.get("m1").canConfirmSafeStop());
        assertFalse(service.get("m1").canCleanupSafeStop());
        assertEquals(status,mission.getStatus());
    }
    @Test void operatorAllowedOnlyForNotYetFlownCaseAndAcceptedOwnAssignment() {
        var user=authenticate(RoleCode.STAFF,"operator"); assign(user,MissionStaffRole.OPERATOR); requested(false);
        assertTrue(service.get("m1").canConfirmSafeStop());
        mission.setSafeStopRequiresLanding(true); assertFalse(service.get("m1").canConfirmSafeStop());
        mission.setSafeStopRequiresLanding(false);
        crew.findByMissionId("m1").getFirst().setResponseStatus(MissionStaffAssignmentStatus.PENDING);
        assertFalse(service.get("m1").canConfirmSafeStop());
    }
    @Test void siblingCrewCannotConfirmEvenWithStaffAuthority() {
        var pilot=authenticate(RoleCode.STAFF,"pilot-a"); assign(pilot,MissionStaffRole.PILOT); requested(true);
        var other=User.builder().role(Role.builder().code(RoleCode.STAFF).build()).build(); other.setId("pilot-b");
        when(actor.getCurrentUser()).thenReturn(other);
        assertFalse(service.get("m1").canConfirmSafeStop());
    }
    @Test void persistedRoleWithoutCommandAuthorityDoesNotGrantConfirmation() {
        var pilot=authenticate(RoleCode.STAFF,"pilot"); assign(pilot,MissionStaffRole.PILOT); requested(true);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("pilot",null,List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));
        assertNone(service.get("m1"));
    }
    @Test void cancelledAndInconsistentPrerequisitesFailClosed() {
        authenticate(RoleCode.MANAGER,"manager"); requested(false); mission.setSafeStopCleanedAt(Instant.now());
        mission.setStatus(MissionStatus.CANCELLED); assertNone(service.get("m1"));
        mission.setStatus(MissionStatus.PLANNING); mission.setSafeStopStatus(null); assertNone(service.get("m1"));
        mission.setOrder(null); assertNone(service.get("m1"));
    }
    @Test void safeStopFailureRetainsBlockedCleanupAndCancellation() {
        authenticate(RoleCode.MANAGER,"manager"); requested(false); mission.setSafeStopStatus(MissionSafeStopStatus.FAILED);
        assertFalse(service.get("m1").canCleanupSafeStop()); assertFalse(service.get("m1").canCancelMission());
    }
    @Test void finalizedPlanApprovedResultAndResourcesBlockCancellation() {
        authenticate(RoleCode.MANAGER,"manager"); assertTrue(service.get("m1").canCancelMission());
        order.setExecutionPlanStatus(ExecutionPlanStatus.FINALIZED); assertFalse(service.get("m1").canCancelMission());
        order.setExecutionPlanStatus(ExecutionPlanStatus.DRAFT);
        when(missions.hasUnsafeCancellationResources("m1")).thenReturn(true); assertFalse(service.get("m1").canCancelMission());
        when(results.existsByMissionIdAndApprovalStatus("m1",MissionResultApprovalStatus.APPROVED)).thenReturn(true);
        assertNone(service.get("m1"));
    }
    @Test void cleanupCapabilityRequiresConfirmationForLiveSession() {
        authenticate(RoleCode.MANAGER,"manager"); requested(false);
        var session=new DeviceConnection(); session.setConnectionStatus("CONNECTED");
        when(connections.findByMissionId("m1")).thenReturn(List.of(session));
        assertFalse(service.get("m1").canCleanupSafeStop());
        mission.setSafeStopConfirmedAt(Instant.now()); mission.setSafeStopStatus(MissionSafeStopStatus.IN_PROGRESS);
        assertTrue(service.get("m1").canCleanupSafeStop());
    }
    @Test void readsAcquireNoWriteLocksAndDoNotChangeAnyPersistentState() {
        authenticate(RoleCode.MANAGER,"manager"); requested(false); var before=mission.getSafeStopStatus();
        service.get("m1"); service.get("m1"); assertEquals(before,mission.getSafeStopStatus());
        verify(missions,never()).findByIdForUpdate(anyString()); verify(missions,never()).saveAndFlush(any());
        verify(connections,never()).saveAll(any()); verify(crew,never()).saveAll(any()); verify(devices,never()).saveAll(any());
        verifyNoInteractions(resources,tokens,schedules);
    }
    @Test void infrastructureFailureIsNotSilentlyConvertedIntoFalsePermission() {
        authenticate(RoleCode.MANAGER,"manager");
        when(results.existsByMissionIdAndApprovalStatus("m1",MissionResultApprovalStatus.APPROVED))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("database unavailable"));
        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,() -> service.get("m1"));
    }
}
