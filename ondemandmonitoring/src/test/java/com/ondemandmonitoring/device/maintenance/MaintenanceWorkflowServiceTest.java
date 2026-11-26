package com.ondemandmonitoring.device.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.when;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.device.domain.Device;
import com.ondemandmonitoring.device.domain.MaintenanceTicket;
import com.ondemandmonitoring.device.enums.DeviceStatus;
import com.ondemandmonitoring.device.maintenance.domain.MaintenanceAcceptanceDecision;
import com.ondemandmonitoring.device.maintenance.domain.MaintenanceEvidence;
import com.ondemandmonitoring.device.maintenance.domain.MaintenanceHandover;
import com.ondemandmonitoring.device.maintenance.domain.MaintenancePartReplacement;
import com.ondemandmonitoring.device.maintenance.domain.MaintenancePostCheck;
import com.ondemandmonitoring.device.maintenance.domain.MaintenanceReportVersion;
import com.ondemandmonitoring.device.maintenance.domain.MaintenanceWorkItem;
import com.ondemandmonitoring.device.maintenance.domain.MaintenanceWorkflowEvent;
import com.ondemandmonitoring.device.maintenance.dto.MaintenanceWorkflowDtos.*;
import com.ondemandmonitoring.device.maintenance.enums.AcceptanceDecisionType;
import com.ondemandmonitoring.device.maintenance.enums.HandoverDirection;
import com.ondemandmonitoring.device.maintenance.enums.HandoverStatus;
import com.ondemandmonitoring.device.maintenance.enums.MaintenanceReportStatus;
import com.ondemandmonitoring.device.maintenance.enums.MaintenanceWorkActionType;
import com.ondemandmonitoring.device.maintenance.enums.MaintenanceWorkStatus;
import com.ondemandmonitoring.device.maintenance.enums.MaintenanceWorkflowStage;
import com.ondemandmonitoring.device.maintenance.enums.PostCheckResult;
import com.ondemandmonitoring.device.maintenance.policy.MaintenanceTransitionPolicy;
import com.ondemandmonitoring.device.maintenance.policy.TechnicalCapabilityResolver;
import com.ondemandmonitoring.device.maintenance.repository.MaintenanceAcceptanceDecisionRepository;
import com.ondemandmonitoring.device.maintenance.repository.MaintenanceEvidenceRepository;
import com.ondemandmonitoring.device.maintenance.repository.MaintenanceHandoverRepository;
import com.ondemandmonitoring.device.maintenance.repository.MaintenancePartReplacementRepository;
import com.ondemandmonitoring.device.maintenance.repository.MaintenanceReportVersionRepository;
import com.ondemandmonitoring.device.maintenance.repository.MaintenanceWorkItemRepository;
import com.ondemandmonitoring.device.maintenance.repository.MaintenanceWorkflowEventRepository;
import com.ondemandmonitoring.device.maintenance.service.MaintenanceAuditAdapter;
import com.ondemandmonitoring.device.maintenance.service.MaintenanceEligibilityGuard;
import com.ondemandmonitoring.device.maintenance.service.MaintenanceWorkflowService;
import com.ondemandmonitoring.device.repository.DeviceRepository;
import com.ondemandmonitoring.device.repository.MaintenanceTicketRepository;
import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.repository.UserRepository;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class MaintenanceWorkflowServiceTest {

    @Mock
    private MaintenanceTicketRepository ticketRepository;
    @Mock
    private DeviceRepository deviceRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private MaintenanceHandoverRepository handoverRepository;
    @Mock
    private MaintenanceWorkItemRepository workItemRepository;
    @Mock
    private MaintenancePartReplacementRepository partRepository;
    @Mock
    private MaintenanceReportVersionRepository reportRepository;
    @Mock
    private MaintenanceAcceptanceDecisionRepository decisionRepository;
    @Mock
    private MaintenanceEvidenceRepository evidenceRepository;
    @Mock
    private MaintenanceWorkflowEventRepository eventRepository;
    @Mock
    private TechnicalCapabilityResolver capabilityResolver;
    @Mock
    private MaintenanceEligibilityGuard eligibilityGuard;
    @Mock
    private MaintenanceAuditAdapter auditAdapter;
    @Mock
    private AuthenticatedUserResolver userResolver;

    private MaintenanceTransitionPolicy transitionPolicy;
    private MaintenanceWorkflowService service;

    private User custodian;
    private User technical;
    private User otherTechnical;
    private User manager;
    private User customer;
    private Device drone;
    private MaintenanceTicket ticket;

    @BeforeEach
    void setUp() {
        transitionPolicy = new MaintenanceTransitionPolicy();
        service = new MaintenanceWorkflowService(
                ticketRepository,
                deviceRepository,
                userRepository,
                handoverRepository,
                workItemRepository,
                partRepository,
                reportRepository,
                decisionRepository,
                evidenceRepository,
                eventRepository,
                capabilityResolver,
                transitionPolicy,
                eligibilityGuard,
                auditAdapter,
                userResolver,
                new ObjectMapper());

        custodian = user("custodian-1", "Pilot Custodian", RoleCode.STAFF);
        technical = user("tech-1", "Tech Nguyen", RoleCode.STAFF);
        otherTechnical = user("tech-2", "Other Tech", RoleCode.STAFF);
        manager = user("mgr-1", "Manager Tran", RoleCode.MANAGER);
        customer = user("cust-1", "Customer Le", RoleCode.CUSTOMER);

        drone = new Device();
        drone.setId("dev-1");
        drone.setName("Autel EVO II 01");
        drone.setSerialNumber("SN-EVO-001");
        drone.setStatus(DeviceStatus.MAINTENANCE);

        ticket = new MaintenanceTicket();
        ticket.setId("tkt-1");
        ticket.setTicketCode("MNT-SN-EVO-001-AAA111");
        ticket.setDevice(drone);
        ticket.setAssignedStaff(technical);
        ticket.setHandedOverBy(custodian);
        ticket.setReportedBy(custodian.getId());
        ticket.setIssueType("POSTFLIGHT_DAMAGE");
        ticket.setSeverity("HIGH");
        ticket.setDescription("Gimbal vibration after landing");
        ticket.setWorkflowStage(MaintenanceWorkflowStage.DRAFT_HANDOVER);
        ticket.setStatus("OPEN");
        ticket.setCurrentRound(1);
        ticket.setVersion(1L);

        lenient().when(ticketRepository.saveAndFlush(any(MaintenanceTicket.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(handoverRepository.save(any(MaintenanceHandover.class)))
                .thenAnswer(inv -> {
                    MaintenanceHandover h = inv.getArgument(0);
                    if (h.getId() == null) {
                        h.setId("ho-1");
                    }
                    return h;
                });
        lenient().when(handoverRepository.findByTicket_IdOrderByRoundNumberDescRevisionDescCreatedAtDesc(any()))
                .thenReturn(List.of());
        lenient().when(workItemRepository.findByTicket_IdOrderByRoundNumberAscCreatedAtAsc(any()))
                .thenReturn(List.of());
        lenient().when(partRepository.findByTicket_IdOrderByRoundNumberAscCreatedAtAsc(any()))
                .thenReturn(List.of());
        lenient().when(reportRepository.findByTicket_IdOrderByRevisionDesc(any()))
                .thenReturn(List.of());
        lenient().when(decisionRepository.findByTicket_IdOrderByDecidedAtDesc(any()))
                .thenReturn(List.of());
        lenient().when(evidenceRepository.findByTicket_IdOrderByUploadedAtDesc(any()))
                .thenReturn(List.of());
        lenient().when(eventRepository.findByTicket_IdOrderByOccurredAtAsc(any()))
                .thenReturn(List.of());
        lenient().when(auditAdapter.record(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new MaintenanceWorkflowEvent());
    }

    @Test
    void custodianSubmitsHandoverDirectlyToQualifiedTechnicalWithoutManagerApproval() {
        MaintenanceHandover draft = new MaintenanceHandover();
        draft.setId("ho-in-1");
        draft.setTicket(ticket);
        draft.setDevice(drone);
        draft.setDirection(HandoverDirection.INBOUND);
        draft.setRoundNumber(1);
        draft.setRevision(1);
        draft.setStatus(HandoverStatus.DRAFT);
        draft.setFromUser(custodian);
        draft.setToUser(technical);
        draft.setMaintenanceReason("Gimbal vibration");
        draft.setOverallCondition("Frame intact, gimbal loose");
        draft.setLocked(false);

        when(userResolver.getCurrentUser()).thenReturn(custodian);
        when(ticketRepository.findByIdForUpdate("tkt-1")).thenReturn(Optional.of(ticket));
        when(capabilityResolver.canActAsCustodian(custodian, drone, ticket)).thenReturn(true);
        when(userRepository.findById("tech-1")).thenReturn(Optional.of(technical));
        when(capabilityResolver.isQualifiedTechnical(technical)).thenReturn(true);
        when(handoverRepository.findFirstByTicket_IdAndDirectionAndStatusOrderByRoundNumberDescRevisionDescCreatedAtDesc(
                "tkt-1", HandoverDirection.INBOUND, HandoverStatus.DRAFT)).thenReturn(Optional.of(draft));

        MaintenanceWorkflowDetailResponse response = service.submitInboundHandover(
                "tkt-1", new SubmitInboundHandoverRequest(1L, "idem-submit-1"));

        assertThat(response.stage()).isEqualTo(MaintenanceWorkflowStage.AWAITING_TECHNICAL_RECEIPT);
        assertThat(draft.isLocked()).isTrue();
        assertThat(draft.getStatus()).isEqualTo(HandoverStatus.SUBMITTED);
        verify(eligibilityGuard).blockDeviceForMaintenance(drone);
    }

    @Test
    void otherTechnicalCannotConfirmReceipt() {
        ticket.setWorkflowStage(MaintenanceWorkflowStage.AWAITING_TECHNICAL_RECEIPT);
        when(userResolver.getCurrentUser()).thenReturn(otherTechnical);
        when(ticketRepository.findByIdForUpdate("tkt-1")).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.confirmOrRejectTechnicalReceipt(
                "tkt-1",
                new TechnicalReceiptRequest(1L, true, false, null, null, List.of(), List.of(), null)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void technicalCannotStartWorkBeforeReceivingDrone() {
        ticket.setWorkflowStage(MaintenanceWorkflowStage.AWAITING_TECHNICAL_RECEIPT);
        when(userResolver.getCurrentUser()).thenReturn(technical);
        when(ticketRepository.findByIdForUpdate("tkt-1")).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.startWork("tkt-1", new StartWorkRequest(1L, "Starting early")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void staleVersionIsRejectedWithConflict() {
        ticket.setVersion(5L);
        ticket.setWorkflowStage(MaintenanceWorkflowStage.RECEIVED);
        when(userResolver.getCurrentUser()).thenReturn(technical);
        when(ticketRepository.findByIdForUpdate("tkt-1")).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.startWork("tkt-1", new StartWorkRequest(4L, "Stale version")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void technicalRejectingReceiptRequiresReason() {
        ticket.setWorkflowStage(MaintenanceWorkflowStage.AWAITING_TECHNICAL_RECEIPT);
        MaintenanceHandover submitted = new MaintenanceHandover();
        submitted.setId("ho-in-1");
        submitted.setTicket(ticket);
        submitted.setStatus(HandoverStatus.SUBMITTED);

        when(userResolver.getCurrentUser()).thenReturn(technical);
        when(ticketRepository.findByIdForUpdate("tkt-1")).thenReturn(Optional.of(ticket));
        when(capabilityResolver.isQualifiedTechnical(technical)).thenReturn(true);
        when(handoverRepository.findFirstByTicket_IdAndDirectionAndStatusOrderByRoundNumberDescRevisionDescCreatedAtDesc(
                "tkt-1", HandoverDirection.INBOUND, HandoverStatus.SUBMITTED)).thenReturn(Optional.of(submitted));

        assertThatThrownBy(() -> service.confirmOrRejectTechnicalReceipt(
                "tkt-1",
                new TechnicalReceiptRequest(1L, false, false, null, "   ", List.of(), List.of(), null)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void submitReturnRejectsFailedRequiredPostCheck() {
        ticket.setWorkflowStage(MaintenanceWorkflowStage.IN_MAINTENANCE);
        MaintenanceReportVersion report = draftReport(1, 1, PostCheckResult.FAIL);
        MaintenanceWorkItem work = completedWorkItem(1);

        when(userResolver.getCurrentUser()).thenReturn(technical);
        when(ticketRepository.findByIdForUpdate("tkt-1")).thenReturn(Optional.of(ticket));
        when(userRepository.findById("mgr-1")).thenReturn(Optional.of(manager));
        when(capabilityResolver.isEligibleReceivingManager(manager)).thenReturn(true);
        when(reportRepository.findFirstByTicket_IdOrderByRevisionDesc("tkt-1")).thenReturn(Optional.of(report));
        when(workItemRepository.findByTicket_IdOrderByRoundNumberAscCreatedAtAsc("tkt-1")).thenReturn(List.of(work));

        assertThatThrownBy(() -> service.submitReturnHandover(
                "tkt-1",
                new SubmitReturnHandoverRequest(1L, "mgr-1", "Ready", null, List.of(), List.of(), "idem-ret-1")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("REQUIRED_POST_CHECK_FAILED");
    }

    @Test
    void technicalCannotSelfAcceptReturn() {
        ticket.setWorkflowStage(MaintenanceWorkflowStage.AWAITING_MANAGER_ACCEPTANCE);
        ticket.setReceivingManager(manager);
        when(userResolver.getCurrentUser()).thenReturn(technical);
        when(ticketRepository.findByIdForUpdate("tkt-1")).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.acceptReturn(
                "tkt-1",
                new AcceptReturnRequest(1L, 1, true, true, "Self approve", "idem-acc-1")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void customerIsDeniedAllMaintenanceAccess() {
        when(userResolver.getCurrentUser()).thenReturn(customer);

        assertThatThrownBy(() -> service.getWorkflowDetail("tkt-1"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void managerRequestReworkOpensNewRoundAndPreservesPreviousReportRevision() {
        ticket.setWorkflowStage(MaintenanceWorkflowStage.AWAITING_MANAGER_ACCEPTANCE);
        ticket.setReceivingManager(manager);
        ticket.setCurrentRound(1);

        MaintenanceReportVersion rev1 = draftReport(1, 1, PostCheckResult.PASS);
        rev1.setStatus(MaintenanceReportStatus.SUBMITTED);
        rev1.setLocked(true);

        MaintenanceHandover returnHandover = new MaintenanceHandover();
        returnHandover.setId("ho-ret-1");
        returnHandover.setTicket(ticket);
        returnHandover.setDirection(HandoverDirection.RETURN);
        returnHandover.setRoundNumber(1);
        returnHandover.setRevision(1);
        returnHandover.setStatus(HandoverStatus.SUBMITTED);
        returnHandover.setReportRevision(1);
        returnHandover.setFromUser(technical);
        returnHandover.setToUser(manager);

        when(userResolver.getCurrentUser()).thenReturn(manager);
        when(ticketRepository.findByIdForUpdate("tkt-1")).thenReturn(Optional.of(ticket));
        when(handoverRepository.findFirstByTicket_IdAndDirectionAndStatusOrderByRoundNumberDescRevisionDescCreatedAtDesc(
                "tkt-1", HandoverDirection.RETURN, HandoverStatus.SUBMITTED)).thenReturn(Optional.of(returnHandover));
        when(reportRepository.findByTicket_IdAndRevision("tkt-1", 1)).thenReturn(Optional.of(rev1));
        when(decisionRepository.save(any(MaintenanceAcceptanceDecision.class)))
                .thenAnswer(inv -> {
                    MaintenanceAcceptanceDecision d = inv.getArgument(0);
                    d.setId("dec-1");
                    return d;
                });

        MaintenanceWorkflowDetailResponse response = service.requestRework(
                "tkt-1",
                new RequestReworkRequest(1L, 1, "Gimbal calibration still drifts", List.of("wi-1"), "idem-rw-1"));

        assertThat(response.stage()).isEqualTo(MaintenanceWorkflowStage.REWORK_REQUIRED);
        assertThat(response.currentRound()).isEqualTo(2);
        assertThat(rev1.isLocked()).isTrue();
        assertThat(rev1.getStatus()).isEqualTo(MaintenanceReportStatus.REWORK_REQUIRED);
        verify(eligibilityGuard).blockDeviceForMaintenance(drone);
        verify(eligibilityGuard, never()).completeAndEvaluateUnlock(any(), any(), any(), any());
    }

    @Test
    void managerAcceptReturnCreatesCheckpointAndIdempotencyPreventsDuplicateAccept() {
        ticket.setWorkflowStage(MaintenanceWorkflowStage.AWAITING_MANAGER_ACCEPTANCE);
        ticket.setReceivingManager(manager);

        MaintenanceReportVersion rev1 = draftReport(1, 1, PostCheckResult.PASS);
        rev1.setStatus(MaintenanceReportStatus.SUBMITTED);
        rev1.setLocked(true);

        MaintenanceHandover returnHandover = new MaintenanceHandover();
        returnHandover.setId("ho-ret-1");
        returnHandover.setTicket(ticket);
        returnHandover.setDirection(HandoverDirection.RETURN);
        returnHandover.setRoundNumber(1);
        returnHandover.setRevision(1);
        returnHandover.setStatus(HandoverStatus.SUBMITTED);
        returnHandover.setReportRevision(1);
        returnHandover.setFromUser(technical);
        returnHandover.setToUser(manager);

        when(userResolver.getCurrentUser()).thenReturn(manager);
        when(ticketRepository.findByIdForUpdate("tkt-1")).thenReturn(Optional.of(ticket));
        when(handoverRepository.findFirstByTicket_IdAndDirectionAndStatusOrderByRoundNumberDescRevisionDescCreatedAtDesc(
                "tkt-1", HandoverDirection.RETURN, HandoverStatus.SUBMITTED)).thenReturn(Optional.of(returnHandover));
        when(reportRepository.findByTicket_IdAndRevision("tkt-1", 1)).thenReturn(Optional.of(rev1));
        when(eligibilityGuard.completeAndEvaluateUnlock(eq(ticket), eq("mgr-1"), any(), any()))
                .thenReturn(new MaintenanceEligibilityGuard.UnlockOutcome(true, true, List.of()));
        when(decisionRepository.save(any(MaintenanceAcceptanceDecision.class)))
                .thenAnswer(inv -> {
                    MaintenanceAcceptanceDecision d = inv.getArgument(0);
                    d.setId("dec-acc-1");
                    return d;
                });

        MaintenanceWorkflowDetailResponse first = service.acceptReturn(
                "tkt-1",
                new AcceptReturnRequest(1L, 1, true, true, "Verified serial and props", "idem-acc-99"));

        assertThat(first.stage()).isEqualTo(MaintenanceWorkflowStage.COMPLETED);
        assertThat(first.legacyStatus()).isEqualTo("CLOSED");
        assertThat(first.acceptedReportRevision()).isEqualTo(1);

        // Retry with same idempotencyKey must return cleanly without creating a second checkpoint/decision.
        MaintenanceAcceptanceDecision existing = new MaintenanceAcceptanceDecision();
        existing.setId("dec-acc-1");
        existing.setTicket(ticket);
        existing.setDecision(AcceptanceDecisionType.ACCEPTED);
        when(decisionRepository.findByIdempotencyKey("idem-acc-99")).thenReturn(Optional.of(existing));

        service.acceptReturn(
                "tkt-1",
                new AcceptReturnRequest(1L, 1, true, true, "Verified serial and props", "idem-acc-99"));

        verify(eligibilityGuard, times(1)).completeAndEvaluateUnlock(any(), any(), any(), any());
    }

    @Test
    void evidenceFromAnotherTicketIsRejected() {
        when(userResolver.getCurrentUser()).thenReturn(manager);
        when(ticketRepository.findById("tkt-1")).thenReturn(Optional.of(ticket));
        when(evidenceRepository.findByIdAndTicket_Id("ev-foreign", "tkt-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getEvidenceForTicket("tkt-1", "ev-foreign"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private User user(String id, String name, RoleCode code) {
        Role role = new Role();
        role.setCode(code);
        User u = new User();
        u.setId(id);
        u.setFullName(name);
        u.setEmail(id + "@example.com");
        u.setIsActive(true);
        u.setRole(role);
        return u;
    }

    private MaintenanceWorkItem completedWorkItem(int round) {
        MaintenanceWorkItem item = new MaintenanceWorkItem();
        item.setId("wi-1");
        item.setTicket(ticket);
        item.setRoundNumber(round);
        item.setComponentArea("Gimbal mount");
        item.setActionType(MaintenanceWorkActionType.CALIBRATE);
        item.setWorkPerformed("Rebalanced and calibrated gimbal");
        item.setStatus(MaintenanceWorkStatus.COMPLETED);
        item.setStartedAt(Instant.now().minusSeconds(1800));
        item.setEndedAt(Instant.now());
        return item;
    }

    private MaintenanceReportVersion draftReport(int round, int revision, PostCheckResult checkResult) {
        MaintenanceReportVersion report = new MaintenanceReportVersion();
        report.setId("rep-" + revision);
        report.setTicket(ticket);
        report.setRoundNumber(round);
        report.setRevision(revision);
        report.setStatus(MaintenanceReportStatus.DRAFT);
        report.setReceivedConditionSummary("Gimbal vibration observed on receipt");
        report.setFaultDiagnosisSummary("Loose damper screw");
        report.setWorkSummary("Tightened mount and calibrated IMU/gimbal");
        report.setAuthoredBy(technical);
        report.setLocked(false);

        MaintenancePostCheck check = new MaintenancePostCheck();
        check.setId("chk-1");
        check.setReportVersion(report);
        check.setCheckName("Static bench hover & gimbal sweep");
        check.setCheckMethod("Manual bench test without simulator");
        check.setResult(checkResult);
        check.setRequired(true);
        check.setEvidenceRequired(false);
        report.setPostChecks(new ArrayList<>(List.of(check)));
        return report;
    }

    // ---- Shared Technical queue ------------------------------------------------------------

    private MaintenanceHandover inboundHandover(HandoverStatus status, User toUser) {
        MaintenanceHandover handover = new MaintenanceHandover();
        handover.setId("ho-in-pool");
        handover.setTicket(ticket);
        handover.setDevice(drone);
        handover.setDirection(HandoverDirection.INBOUND);
        handover.setRoundNumber(1);
        handover.setRevision(1);
        handover.setStatus(status);
        handover.setFromUser(custodian);
        handover.setToUser(toUser);
        handover.setMaintenanceReason("Gimbal vibration");
        handover.setOverallCondition("Frame intact, gimbal loose");
        handover.setLocked(status != HandoverStatus.DRAFT);
        return handover;
    }

    private void pooledTicket() {
        ticket.setAssignedStaff(null);
        ticket.setWorkflowStage(MaintenanceWorkflowStage.AWAITING_TECHNICAL_RECEIPT);
        when(ticketRepository.findByIdForUpdate("tkt-1")).thenReturn(Optional.of(ticket));
    }

    @Test
    void handoverWithoutTechnicalIsPublishedToSharedQueue() {
        ticket.setAssignedStaff(null);
        MaintenanceHandover draft = inboundHandover(HandoverStatus.DRAFT, null);
        when(userResolver.getCurrentUser()).thenReturn(custodian);
        when(ticketRepository.findByIdForUpdate("tkt-1")).thenReturn(Optional.of(ticket));
        when(capabilityResolver.canActAsCustodian(custodian, drone, ticket)).thenReturn(true);
        when(handoverRepository.findFirstByTicket_IdAndDirectionAndStatusOrderByRoundNumberDescRevisionDescCreatedAtDesc(
                "tkt-1", HandoverDirection.INBOUND, HandoverStatus.DRAFT)).thenReturn(Optional.of(draft));

        MaintenanceWorkflowDetailResponse response = service.submitInboundHandover(
                "tkt-1", new SubmitInboundHandoverRequest(1L, "idem-pool-1"));

        assertThat(response.stage()).isEqualTo(MaintenanceWorkflowStage.AWAITING_TECHNICAL_RECEIPT);
        assertThat(ticket.getAssignedStaff()).isNull();
        assertThat(transitionPolicy.isInSharedQueue(ticket)).isTrue();
    }

    @Test
    void qualifiedTechnicalClaimsPooledTicketAndBecomesDesignated() {
        pooledTicket();
        MaintenanceHandover inbound = inboundHandover(HandoverStatus.SUBMITTED, null);
        when(userResolver.getCurrentUser()).thenReturn(technical);
        when(capabilityResolver.isQualifiedTechnical(technical)).thenReturn(true);
        when(ticketRepository.countByAssignedStaff_IdAndWorkflowStageIn(any(), any())).thenReturn(0L);
        when(handoverRepository.findFirstByTicket_IdAndDirectionAndStatusOrderByRoundNumberDescRevisionDescCreatedAtDesc(
                "tkt-1", HandoverDirection.INBOUND, HandoverStatus.SUBMITTED)).thenReturn(Optional.of(inbound));

        MaintenanceWorkflowDetailResponse response = service.claimTicket("tkt-1", new ClaimTicketRequest(1L));

        assertThat(ticket.getAssignedStaff()).isEqualTo(technical);
        assertThat(inbound.getToUser()).isEqualTo(technical);
        assertThat(response.availableActions()).contains(MaintenanceTransitionPolicy.ACTION_CONFIRM_RECEIPT);
    }

    @Test
    void secondTechnicalCannotClaimAlreadyClaimedTicket() {
        pooledTicket();
        ticket.setAssignedStaff(otherTechnical);
        when(userResolver.getCurrentUser()).thenReturn(technical);
        when(capabilityResolver.isQualifiedTechnical(technical)).thenReturn(true);

        assertThatThrownBy(() -> service.claimTicket("tkt-1", new ClaimTicketRequest(1L)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void technicalCannotHoldMoreThanTheActiveLimit() {
        pooledTicket();
        when(userResolver.getCurrentUser()).thenReturn(technical);
        when(capabilityResolver.isQualifiedTechnical(technical)).thenReturn(true);
        when(ticketRepository.countByAssignedStaff_IdAndWorkflowStageIn(any(), any()))
                .thenReturn((long) MaintenanceWorkflowService.MAX_ACTIVE_TICKETS_PER_TECHNICAL);

        assertThatThrownBy(() -> service.claimTicket("tkt-1", new ClaimTicketRequest(1L)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(ticket.getAssignedStaff()).isNull();
    }

    @Test
    void unqualifiedStaffCannotClaim() {
        when(userResolver.getCurrentUser()).thenReturn(custodian);
        when(capabilityResolver.isQualifiedTechnical(custodian)).thenReturn(false);

        assertThatThrownBy(() -> service.claimTicket("tkt-1", new ClaimTicketRequest(1L)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void claimingTechnicalReleasesTicketBackToSharedQueue() {
        ticket.setWorkflowStage(MaintenanceWorkflowStage.AWAITING_TECHNICAL_RECEIPT);
        MaintenanceHandover inbound = inboundHandover(HandoverStatus.SUBMITTED, technical);
        when(userResolver.getCurrentUser()).thenReturn(technical);
        when(ticketRepository.findByIdForUpdate("tkt-1")).thenReturn(Optional.of(ticket));
        when(handoverRepository.findFirstByTicket_IdAndDirectionAndStatusOrderByRoundNumberDescRevisionDescCreatedAtDesc(
                "tkt-1", HandoverDirection.INBOUND, HandoverStatus.SUBMITTED)).thenReturn(Optional.of(inbound));

        service.releaseTicket("tkt-1", new ReleaseTicketRequest(1L, "Đang bận ca khác"));

        assertThat(ticket.getAssignedStaff()).isNull();
        assertThat(inbound.getToUser()).isNull();
        assertThat(transitionPolicy.isInSharedQueue(ticket)).isTrue();
    }

    @Test
    void preflightFailureOpensPooledTicketAndBlocksDrone() {
        when(ticketRepository.findByDeviceId("dev-1")).thenReturn(List.of());
        ArgumentCaptor<MaintenanceHandover> handover = ArgumentCaptor.forClass(MaintenanceHandover.class);

        MaintenanceTicket opened = service.openPreflightFailureTicket(drone, custodian, "Cánh quạt nứt");

        assertThat(opened.getAssignedStaff()).isNull();
        assertThat(opened.getWorkflowStage()).isEqualTo(MaintenanceWorkflowStage.AWAITING_TECHNICAL_RECEIPT);
        assertThat(opened.getReportedBy()).isEqualTo(MaintenanceWorkflowService.PREFLIGHT_GATE_REPORTER);
        assertThat(transitionPolicy.isInSharedQueue(opened)).isTrue();
        verify(handoverRepository).save(handover.capture());
        assertThat(handover.getValue().getStatus()).isEqualTo(HandoverStatus.SUBMITTED);
        assertThat(handover.getValue().getToUser()).isNull();
        assertThat(handover.getValue().getMaintenanceReason()).isEqualTo("Cánh quạt nứt");
        verify(eligibilityGuard).blockDeviceForMaintenance(drone);
    }

    @Test
    void preflightFailureReusesOpenTicketForSameDrone() {
        ticket.setWorkflowStage(MaintenanceWorkflowStage.IN_MAINTENANCE);
        when(ticketRepository.findByDeviceId("dev-1")).thenReturn(List.of(ticket));

        MaintenanceTicket result = service.openPreflightFailureTicket(drone, custodian, "Pin yếu");

        assertThat(result).isSameAs(ticket);
        verify(ticketRepository, never()).saveAndFlush(any(MaintenanceTicket.class));
    }

    @Test
    void severityRankPutsCriticalFirst() {
        assertThat(List.of("LOW", "CRITICAL", "MEDIUM", "HIGH", "UNKNOWN").stream()
                .sorted(java.util.Comparator.comparingInt(MaintenanceWorkflowService::severityRank))
                .toList())
                .containsExactly("CRITICAL", "HIGH", "MEDIUM", "LOW", "UNKNOWN");
    }
}
