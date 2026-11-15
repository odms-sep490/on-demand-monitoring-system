package com.ondemandmonitoring.mission.inspection;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.devicecheck.service.IPersistedPreDeviceCheckService;
import com.ondemandmonitoring.finance.domain.Invoice;
import com.ondemandmonitoring.finance.enums.*;
import com.ondemandmonitoring.finance.repository.InvoiceRepository;
import com.ondemandmonitoring.flightpermit.domain.FlightPermit;
import com.ondemandmonitoring.flightpermit.repository.FlightPermitRepository;
import com.ondemandmonitoring.flightpermit.service.impl.FlightPermitServiceImpl;
import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.dto.request.FlightPermitUpdateRequest;
import com.ondemandmonitoring.mission.enums.*;
import com.ondemandmonitoring.mission.mapper.MissionMapper;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class FlightPermitLifecycleGateTest {
  final FlightPermitServiceImpl service = mock(FlightPermitServiceImpl.class, CALLS_REAL_METHODS);
  final MissionRepository missions = mock(MissionRepository.class);
  final FlightPermitRepository permits = mock(FlightPermitRepository.class);
  final OrderRepository orders = mock(OrderRepository.class);
  final InvoiceRepository invoices = mock(InvoiceRepository.class);
  final MissionStaffAssignmentRepository crew = mock(MissionStaffAssignmentRepository.class);
  final MissionMapper mapper = mock(MissionMapper.class);
  final Mission mission = new Mission();
  final MissionStaffAssignment pilot = new MissionStaffAssignment();
  final Order order = new Order();

  @BeforeEach
  void setup() {
    ReflectionTestUtils.setField(service, "missionRepository", missions);
    ReflectionTestUtils.setField(service, "flightPermitRepository", permits);
    ReflectionTestUtils.setField(service, "orderRepository", orders);
    ReflectionTestUtils.setField(service, "invoiceRepository", invoices);
    ReflectionTestUtils.setField(service, "missionStaffAssignmentRepository", crew);
    ReflectionTestUtils.setField(service, "missionMapper", mapper);
    ReflectionTestUtils.setField(
        service, "authenticatedUserResolver", mock(AuthenticatedUserResolver.class));
    ReflectionTestUtils.setField(
        service, "persistedPreDeviceCheckService", mock(IPersistedPreDeviceCheckService.class));
    order.setId("order");
    mission.setId("mission");
    mission.setOrder(order);
    mission.setStatus(MissionStatus.PENDING_CONFIRMATION);
    var permit = FlightPermit.pendingFor(mission);
    when(missions.findByIdWithOrder("mission")).thenReturn(Optional.of(mission));
    when(missions.save(mission)).thenReturn(mission);
    when(orders.findByIdForUpdate("order")).thenReturn(Optional.of(order));
    when(permits.findByMissionId("mission")).thenReturn(Optional.of(permit));
    var invoice = new Invoice();
    invoice.setStatus(InvoiceStatus.PARTIALLY_PAID);
    invoice.setPaidAmount(java.math.BigDecimal.valueOf(30));
    invoice.setDepositAmount(java.math.BigDecimal.valueOf(30));
    invoice.setDepositStatus(DepositStatus.DEPOSIT_PAID);
    when(invoices.findByOrderId("order")).thenReturn(Optional.of(invoice));
    pilot.setResponseStatus(MissionStaffAssignmentStatus.PENDING);
    when(crew.findAllByMissionIdAndIsCurrentTrue("mission")).thenReturn(List.of(pilot));
  }

  void origin(String origin) {
    mission.setPurpose(origin.equals("ORIGINAL") ? MissionPurpose.ORIGINAL : MissionPurpose.REWORK);
    if (origin.equals("CUSTOMER_REWORK")) mission.setResultRevisionCaseId("customer-case");
    if (origin.equals("INTERNAL_CORRECTION"))
      mission.setInspectionCorrection(
          new com.ondemandmonitoring.mission.inspection.domain.InspectionCorrection());
  }

  @ParameterizedTest
  @ValueSource(strings = {"ORIGINAL", "CUSTOMER_REWORK", "INTERNAL_CORRECTION"})
  void planningMustBeFinalizedBeforeAnyPermitProcessing(String origin) {
    origin(origin);
    mission.setStatus(MissionStatus.PLANNING);
    assertThatThrownBy(
            () ->
                service.updateFlightPermit(
                    "mission",
                    new FlightPermitUpdateRequest(
                        FlightPermitStatus.PENDING, "Note", null, null, null)))
        .isInstanceOf(ApiException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.EXECUTION_PLAN_NOT_FINALIZED);
    verifyNoInteractions(permits);
  }

  @ParameterizedTest
  @ValueSource(strings = {"ORIGINAL", "CUSTOMER_REWORK", "INTERNAL_CORRECTION"})
  void pendingCrewBlocksDraftPermitUploadsAsWellAsApproval(String origin) {
    origin(origin);
    for (var status : FlightPermitStatus.values()) {
      assertThatThrownBy(
              () ->
                  service.updateFlightPermit(
                      "mission",
                      new FlightPermitUpdateRequest(
                          status,
                          "Note",
                          "permit.pdf",
                          "application/pdf",
                          "data:application/pdf;base64,AA==")))
          .isInstanceOf(ApiException.class)
          .hasMessageContaining("accept the mission");
    }
    verify(permits, never()).save(any());
    verify(missions, never()).save(any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"ORIGINAL", "CUSTOMER_REWORK", "INTERNAL_CORRECTION"})
  void acceptedCrewCanProceedToPermitProcessing(String origin) {
    origin(origin);
    pilot.setResponseStatus(MissionStaffAssignmentStatus.ACCEPTED);
    service.updateFlightPermit(
        "mission",
        new FlightPermitUpdateRequest(
            FlightPermitStatus.PENDING, "Permit preparation", null, null, null));
    verify(permits).save(mission.getFlightPermit());
  }

  @ParameterizedTest
  @ValueSource(strings = {"ORIGINAL", "CUSTOMER_REWORK", "INTERNAL_CORRECTION"})
  void executionStillRequiresApprovedPermit(String origin) {
    origin(origin);
    assertThatThrownBy(() -> service.requireApprovedForExecution(mission))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("approved");
  }
}
