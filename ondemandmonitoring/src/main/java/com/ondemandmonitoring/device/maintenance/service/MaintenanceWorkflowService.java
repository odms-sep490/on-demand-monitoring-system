package com.ondemandmonitoring.device.maintenance.service;

import com.ondemandmonitoring.audit.enums.AuditAction;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.device.domain.Device;
import com.ondemandmonitoring.device.domain.MaintenanceTicket;
import com.ondemandmonitoring.device.maintenance.domain.MaintenanceAcceptanceDecision;
import com.ondemandmonitoring.device.maintenance.domain.MaintenanceEvidence;
import com.ondemandmonitoring.device.maintenance.domain.MaintenanceHandover;
import com.ondemandmonitoring.device.maintenance.domain.MaintenanceHandoverItem;
import com.ondemandmonitoring.device.maintenance.domain.MaintenancePartReplacement;
import com.ondemandmonitoring.device.maintenance.domain.MaintenancePostCheck;
import com.ondemandmonitoring.device.maintenance.domain.MaintenanceReportVersion;
import com.ondemandmonitoring.device.maintenance.domain.MaintenanceWorkItem;
import com.ondemandmonitoring.device.maintenance.domain.MaintenanceWorkflowEvent;
import com.ondemandmonitoring.device.maintenance.dto.MaintenanceWorkflowDtos.*;
import com.ondemandmonitoring.device.maintenance.enums.AcceptanceDecisionType;
import com.ondemandmonitoring.device.maintenance.enums.HandoverDirection;
import com.ondemandmonitoring.device.maintenance.enums.HandoverStatus;
import com.ondemandmonitoring.device.maintenance.enums.MaintenanceEvidenceStage;
import com.ondemandmonitoring.device.maintenance.enums.MaintenanceReportStatus;
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
import com.ondemandmonitoring.device.repository.DeviceRepository;
import com.ondemandmonitoring.device.repository.MaintenanceTicketRepository;
import com.ondemandmonitoring.media.service.IMediaObjectStorage;
import com.ondemandmonitoring.media.validation.ImageFileValidator;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.repository.UserRepository;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Orchestrates the end-to-end Drone Maintenance Handover, Execution, Reporting,
 * and Manager Acceptance workflow with pessimistic aggregate locking, optimistic
 * version checks, idempotency protection, and immutable snapshots.
 */
@Service
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class MaintenanceWorkflowService {

    private static final EnumSet<MaintenanceWorkflowStage> TECHNICAL_INBOX_STAGES = EnumSet.of(
            MaintenanceWorkflowStage.AWAITING_TECHNICAL_RECEIPT);

    private static final EnumSet<MaintenanceWorkflowStage> TECHNICAL_ACTIVE_STAGES = EnumSet.of(
            MaintenanceWorkflowStage.RECEIVED,
            MaintenanceWorkflowStage.IN_MAINTENANCE,
            MaintenanceWorkflowStage.WAITING_PARTS,
            MaintenanceWorkflowStage.REWORK_REQUIRED);

    private static final EnumSet<MaintenanceWorkflowStage> MANAGER_ACCEPTANCE_STAGES = EnumSet.of(
            MaintenanceWorkflowStage.AWAITING_MANAGER_ACCEPTANCE);

    private final MaintenanceTicketRepository ticketRepository;
    private final DeviceRepository deviceRepository;
    private final UserRepository userRepository;
    private final MaintenanceHandoverRepository handoverRepository;
    private final MaintenanceWorkItemRepository workItemRepository;
    private final MaintenancePartReplacementRepository partRepository;
    private final MaintenanceReportVersionRepository reportRepository;
    private final MaintenanceAcceptanceDecisionRepository decisionRepository;
    private final MaintenanceEvidenceRepository evidenceRepository;
    private final MaintenanceWorkflowEventRepository eventRepository;
    private final TechnicalCapabilityResolver capabilityResolver;
    private final MaintenanceTransitionPolicy transitionPolicy;
    private final MaintenanceEligibilityGuard eligibilityGuard;
    private final MaintenanceAuditAdapter auditAdapter;
    private final AuthenticatedUserResolver userResolver;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private IMediaObjectStorage mediaObjectStorage;

    @Transactional
    public MaintenanceWorkflowDetailResponse createTicketWithInboundDraft(CreateTicketWithHandoverRequest request) {
        User actor = requireAuthenticatedNonCustomer();
        Device device = deviceRepository.findById(request.deviceId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Device not found: " + request.deviceId()));

        if (!capabilityResolver.canActAsCustodian(actor, device, null)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "Only the current drone custodian or Manager/Admin can initiate a maintenance handover.");
        }

        User technical = optionalQualifiedTechnical(request.technicalUserId());

        Instant now = Instant.now();
        MaintenanceTicket ticket = new MaintenanceTicket();
        ticket.setTicketCode("MNT-" + device.getSerialNumber() + "-"
                + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        ticket.setDevice(device);
        ticket.setAssignedStaff(technical);
        ticket.setHandedOverBy(actor);
        ticket.setReportedBy(actor.getId());
        ticket.setIssueType(request.issueType().trim());
        ticket.setSeverity(request.severity().trim());
        ticket.setDescription(request.description().trim());
        ticket.setWorkflowStage(MaintenanceWorkflowStage.DRAFT_HANDOVER);
        ticket.setStatus(MaintenanceWorkflowStage.DRAFT_HANDOVER.toTicketStatus());
        ticket.setCurrentRound(1);
        ticket.setLegacyRecord(Boolean.FALSE);
        ticket.setOpenedAt(now);
        ticket.setWorkflowUpdatedAt(now);
        MaintenanceTicket savedTicket = ticketRepository.saveAndFlush(ticket);

        eligibilityGuard.blockDeviceForMaintenance(device);

        MaintenanceHandover draft = new MaintenanceHandover();
        draft.setTicket(savedTicket);
        draft.setDevice(device);
        draft.setDirection(HandoverDirection.INBOUND);
        draft.setRoundNumber(1);
        draft.setRevision(1);
        draft.setStatus(HandoverStatus.DRAFT);
        draft.setFromUser(actor);
        draft.setToUser(technical);
        draft.setMaintenanceReason(blankToFallback(request.maintenanceReason(), request.description()));
        draft.setOverallCondition(blankToFallback(request.overallCondition(), "Cần kiểm tra bảo trì"));
        draft.setExistingDamageNotes(trimToNull(request.existingDamageNotes()));
        draft.setActualHandoverAt(request.actualHandoverAt());
        draft.setEvidenceRefsJson(toJson(validateEvidenceIdsForTicket(savedTicket.getId(), request.evidenceIds())));
        draft.setLocked(false);
        replaceHandoverItems(draft, request.accessories());
        handoverRepository.save(draft);

        auditAdapter.record(
                savedTicket,
                actor,
                "CREATE_MAINTENANCE_HANDOVER_DRAFT",
                AuditAction.CREATE,
                null,
                MaintenanceWorkflowStage.DRAFT_HANDOVER,
                1,
                draft.getMaintenanceReason(),
                request.evidenceIds(),
                Map.of("technicalUserId", technicalRef(technical)));

        return buildDetailResponse(savedTicket, actor);
    }

    @Transactional
    public MaintenanceWorkflowDetailResponse saveInboundHandoverDraft(
            String ticketId,
            SaveInboundHandoverRequest request) {
        User actor = requireAuthenticatedNonCustomer();
        MaintenanceTicket ticket = lockTicket(ticketId);
        requireCustodianOrManager(actor, ticket);

        if (request.expectedVersion() != null) {
            transitionPolicy.requireExpectedVersion(ticket, request.expectedVersion());
        }
        transitionPolicy.requireStageIn(
                ticket,
                MaintenanceWorkflowStage.DRAFT_HANDOVER,
                MaintenanceWorkflowStage.RECEIPT_REJECTED);

        User technical = optionalQualifiedTechnical(request.technicalUserId());

        Optional<MaintenanceHandover> existingDraftOpt = handoverRepository
                .findFirstByTicket_IdAndDirectionAndStatusOrderByRoundNumberDescRevisionDescCreatedAtDesc(
                        ticketId, HandoverDirection.INBOUND, HandoverStatus.DRAFT);

        MaintenanceHandover draft;
        if (existingDraftOpt.isPresent() && !existingDraftOpt.get().isLocked()) {
            draft = existingDraftOpt.get();
        } else {
            int nextRevision = handoverRepository
                    .findFirstByTicket_IdAndDirectionOrderByRoundNumberDescRevisionDescCreatedAtDesc(
                            ticketId, HandoverDirection.INBOUND)
                    .map(h -> h.getRevision() + 1)
                    .orElse(1);
            draft = new MaintenanceHandover();
            draft.setTicket(ticket);
            draft.setDevice(ticket.getDevice());
            draft.setDirection(HandoverDirection.INBOUND);
            draft.setRoundNumber(ticket.getCurrentRound() == null ? 1 : ticket.getCurrentRound());
            draft.setRevision(nextRevision);
            draft.setStatus(HandoverStatus.DRAFT);
            draft.setLocked(false);
        }

        List<String> validatedEvidences = validateEvidenceIdsForTicket(ticketId, request.evidenceIds());

        draft.setFromUser(actor);
        draft.setToUser(technical);
        draft.setMaintenanceReason(request.maintenanceReason().trim());
        draft.setOverallCondition(request.overallCondition().trim());
        draft.setExistingDamageNotes(trimToNull(request.existingDamageNotes()));
        draft.setActualHandoverAt(request.actualHandoverAt());
        draft.setEvidenceRefsJson(toJson(validatedEvidences));
        replaceHandoverItems(draft, request.accessories());
        handoverRepository.save(draft);

        MaintenanceWorkflowStage previous = transitionPolicy.effectiveStage(ticket);
        ticket.setAssignedStaff(technical);
        ticket.setHandedOverBy(actor);
        ticket.setWorkflowStage(MaintenanceWorkflowStage.DRAFT_HANDOVER);
        ticket.setStatus(MaintenanceWorkflowStage.DRAFT_HANDOVER.toTicketStatus());
        ticket.setLegacyRecord(Boolean.FALSE);
        ticket.setWorkflowUpdatedAt(Instant.now());
        MaintenanceTicket saved = ticketRepository.saveAndFlush(ticket);

        eligibilityGuard.blockDeviceForMaintenance(saved.getDevice());

        auditAdapter.record(
                saved,
                actor,
                "SAVE_INBOUND_HANDOVER_DRAFT",
                AuditAction.UPDATE,
                previous,
                MaintenanceWorkflowStage.DRAFT_HANDOVER,
                draft.getRevision(),
                draft.getMaintenanceReason(),
                validatedEvidences,
                Map.of("handoverId", draft.getId(), "technicalUserId", technicalRef(technical)));

        return buildDetailResponse(saved, actor);
    }

    @Transactional
    public MaintenanceWorkflowDetailResponse submitInboundHandover(
            String ticketId,
            SubmitInboundHandoverRequest request) {
        User actor = requireAuthenticatedNonCustomer();
        MaintenanceTicket ticket = lockTicket(ticketId);
        requireCustodianOrManager(actor, ticket);

        if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
            Optional<MaintenanceHandover> existing = handoverRepository.findByIdempotencyKey(request.idempotencyKey().trim());
            if (existing.isPresent() && ticketId.equals(existing.get().getTicket().getId())) {
                return buildDetailResponse(ticket, actor);
            }
        }

        transitionPolicy.requireExpectedVersion(ticket, request.expectedVersion());
        transitionPolicy.requireStageIn(
                ticket,
                MaintenanceWorkflowStage.DRAFT_HANDOVER,
                MaintenanceWorkflowStage.RECEIPT_REJECTED);

        MaintenanceHandover draft = handoverRepository
                .findFirstByTicket_IdAndDirectionAndStatusOrderByRoundNumberDescRevisionDescCreatedAtDesc(
                        ticketId, HandoverDirection.INBOUND, HandoverStatus.DRAFT)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "An inbound handover draft must be saved before submitting to Technical."));

        if (draft.isLocked()) {
            throw new ApiException(HttpStatus.CONFLICT, "Inbound handover snapshot is already locked.");
        }
        if (isBlank(draft.getMaintenanceReason()) || isBlank(draft.getOverallCondition())) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Maintenance reason and overall drone condition are required before submitting handover.");
        }
        // No receiver means the handover is published to the shared Technical queue.
        if (draft.getToUser() != null) {
            requireQualifiedTechnical(draft.getToUser().getId());
        }

        Instant now = Instant.now();
        draft.setStatus(HandoverStatus.SUBMITTED);
        draft.setSubmittedAt(now);
        if (draft.getActualHandoverAt() == null) {
            draft.setActualHandoverAt(now);
        }
        draft.setIdempotencyKey(trimToNull(request.idempotencyKey()));
        draft.setLocked(true);
        handoverRepository.save(draft);

        MaintenanceWorkflowStage previous = transitionPolicy.effectiveStage(ticket);
        ticket.setAssignedStaff(draft.getToUser());
        ticket.setHandedOverBy(actor);
        ticket.setWorkflowStage(MaintenanceWorkflowStage.AWAITING_TECHNICAL_RECEIPT);
        ticket.setStatus(MaintenanceWorkflowStage.AWAITING_TECHNICAL_RECEIPT.toTicketStatus());
        ticket.setWorkflowUpdatedAt(now);
        MaintenanceTicket saved = ticketRepository.saveAndFlush(ticket);

        eligibilityGuard.blockDeviceForMaintenance(saved.getDevice());

        auditAdapter.record(
                saved,
                actor,
                "SUBMIT_INBOUND_HANDOVER",
                AuditAction.STATUS_CHANGE,
                previous,
                MaintenanceWorkflowStage.AWAITING_TECHNICAL_RECEIPT,
                draft.getRevision(),
                draft.getMaintenanceReason(),
                parseStringList(draft.getEvidenceRefsJson()),
                Map.of("handoverId", draft.getId(), "technicalUserId", technicalRef(draft.getToUser())));

        return buildDetailResponse(saved, actor);
    }

    /** Maximum tickets one Technical may hold at once (claimed but not yet returned). */
    public static final int MAX_ACTIVE_TICKETS_PER_TECHNICAL = 2;

    private static final EnumSet<MaintenanceWorkflowStage> TECHNICAL_HELD_STAGES = EnumSet.of(
            MaintenanceWorkflowStage.AWAITING_TECHNICAL_RECEIPT,
            MaintenanceWorkflowStage.RECEIVED,
            MaintenanceWorkflowStage.IN_MAINTENANCE,
            MaintenanceWorkflowStage.WAITING_PARTS,
            MaintenanceWorkflowStage.REWORK_REQUIRED);

    /**
     * A qualified Technical takes an unclaimed ticket from the shared queue. The row lock makes
     * concurrent claims serialize, so only the first caller wins.
     */
    @Transactional
    public MaintenanceWorkflowDetailResponse claimTicket(String ticketId, ClaimTicketRequest request) {
        User actor = requireAuthenticatedNonCustomer();
        if (!capabilityResolver.isQualifiedTechnical(actor)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Bạn cần chứng chỉ kỹ thuật còn hiệu lực để nhận việc bảo trì.");
        }
        MaintenanceTicket ticket = lockTicket(ticketId);
        transitionPolicy.requireStageIn(ticket, MaintenanceWorkflowStage.AWAITING_TECHNICAL_RECEIPT);
        if (ticket.getAssignedStaff() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "Ticket này đã có kỹ thuật viên khác nhận.");
        }
        transitionPolicy.requireExpectedVersion(ticket, request.expectedVersion());
        long held = ticketRepository.countByAssignedStaff_IdAndWorkflowStageIn(actor.getId(), TECHNICAL_HELD_STAGES);
        if (held >= MAX_ACTIVE_TICKETS_PER_TECHNICAL) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Bạn đang giữ " + held + " ticket. Hoàn tất bớt trước khi nhận thêm (tối đa "
                            + MAX_ACTIVE_TICKETS_PER_TECHNICAL + ").");
        }

        MaintenanceHandover inbound = submittedInbound(ticketId);
        inbound.setToUser(actor);
        handoverRepository.save(inbound);

        Instant now = Instant.now();
        ticket.setAssignedStaff(actor);
        ticket.setWorkflowUpdatedAt(now);
        MaintenanceTicket saved = ticketRepository.saveAndFlush(ticket);

        auditAdapter.record(saved, actor, "TECHNICAL_CLAIMED_FROM_QUEUE", AuditAction.UPDATE,
                MaintenanceWorkflowStage.AWAITING_TECHNICAL_RECEIPT, MaintenanceWorkflowStage.AWAITING_TECHNICAL_RECEIPT,
                inbound.getRevision(), "Claimed from shared Technical queue", List.of(),
                Map.of("handoverId", inbound.getId(), "technicalUserId", actor.getId()));
        return buildDetailResponse(saved, actor);
    }

    /** The claiming Technical hands an unreceived ticket back to the shared queue. */
    @Transactional
    public MaintenanceWorkflowDetailResponse releaseTicket(String ticketId, ReleaseTicketRequest request) {
        User actor = requireAuthenticatedNonCustomer();
        MaintenanceTicket ticket = lockTicket(ticketId);
        transitionPolicy.requireDesignatedTechnical(actor, ticket);
        transitionPolicy.requireStageIn(ticket, MaintenanceWorkflowStage.AWAITING_TECHNICAL_RECEIPT);
        transitionPolicy.requireExpectedVersion(ticket, request.expectedVersion());

        MaintenanceHandover inbound = submittedInbound(ticketId);
        inbound.setToUser(null);
        handoverRepository.save(inbound);

        ticket.setAssignedStaff(null);
        ticket.setWorkflowUpdatedAt(Instant.now());
        MaintenanceTicket saved = ticketRepository.saveAndFlush(ticket);

        String reason = request.reason().trim();
        auditAdapter.record(saved, actor, "TECHNICAL_RELEASED_TO_QUEUE", AuditAction.UPDATE,
                MaintenanceWorkflowStage.AWAITING_TECHNICAL_RECEIPT, MaintenanceWorkflowStage.AWAITING_TECHNICAL_RECEIPT,
                inbound.getRevision(), reason, List.of(),
                Map.of("handoverId", inbound.getId(), "technicalUserId", actor.getId()));
        return buildDetailResponse(saved, actor);
    }

    /** Reporter marker for tickets opened automatically by a failed preflight check. */
    public static final String PREFLIGHT_GATE_REPORTER = "AUTOMATED_PREFLIGHT_GATE";

    /**
     * Opens a maintenance ticket straight into the shared Technical queue for a drone that failed
     * preflight, so no Manager assignment is needed. Runs in the caller's transaction and returns the
     * existing open ticket instead of creating a duplicate.
     */
    public MaintenanceTicket openPreflightFailureTicket(Device device, User reporter, String failureSummary) {
        Optional<MaintenanceTicket> existing = ticketRepository.findByDeviceId(device.getId()).stream()
                .filter(t -> t.getWorkflowStage() != null && !t.getWorkflowStage().isTerminal())
                .findFirst();
        if (existing.isPresent()) {
            eligibilityGuard.blockDeviceForMaintenance(device);
            return existing.get();
        }
        String reason = isBlank(failureSummary) ? "Không đạt kiểm tra trước bay" : failureSummary.trim();
        if (reason.length() > 1000) reason = reason.substring(0, 1000);
        Instant now = Instant.now();

        MaintenanceTicket ticket = new MaintenanceTicket();
        ticket.setTicketCode("MNT-" + device.getSerialNumber() + "-"
                + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        ticket.setDevice(device);
        ticket.setAssignedStaff(null);
        ticket.setHandedOverBy(reporter);
        ticket.setReportedBy(PREFLIGHT_GATE_REPORTER);
        ticket.setIssueType("PREFLIGHT_HARDWARE_FAIL");
        ticket.setSeverity("HIGH");
        ticket.setDescription("Không đạt kiểm tra trước bay: " + reason);
        ticket.setWorkflowStage(MaintenanceWorkflowStage.AWAITING_TECHNICAL_RECEIPT);
        ticket.setStatus(MaintenanceWorkflowStage.AWAITING_TECHNICAL_RECEIPT.toTicketStatus());
        ticket.setCurrentRound(1);
        ticket.setLegacyRecord(Boolean.FALSE);
        ticket.setOpenedAt(now);
        ticket.setWorkflowUpdatedAt(now);
        MaintenanceTicket saved = ticketRepository.saveAndFlush(ticket);

        MaintenanceHandover inbound = new MaintenanceHandover();
        inbound.setTicket(saved);
        inbound.setDevice(device);
        inbound.setDirection(HandoverDirection.INBOUND);
        inbound.setRoundNumber(1);
        inbound.setRevision(1);
        inbound.setStatus(HandoverStatus.SUBMITTED);
        inbound.setFromUser(reporter);
        inbound.setToUser(null);
        inbound.setMaintenanceReason(reason);
        inbound.setOverallCondition("Không đạt kiểm tra trước bay, cần kỹ thuật kiểm tra");
        inbound.setActualHandoverAt(now);
        inbound.setSubmittedAt(now);
        inbound.setEvidenceRefsJson(toJson(List.of()));
        inbound.setLocked(true);
        handoverRepository.save(inbound);

        eligibilityGuard.blockDeviceForMaintenance(device);
        auditAdapter.record(saved, reporter, "PREFLIGHT_FAILURE_TICKET_OPENED", AuditAction.CREATE,
                null, MaintenanceWorkflowStage.AWAITING_TECHNICAL_RECEIPT, 1, reason, List.of(),
                Map.of("technicalUserId", technicalRef(null), "source", PREFLIGHT_GATE_REPORTER));
        return saved;
    }

    private MaintenanceHandover submittedInbound(String ticketId) {
        return handoverRepository
                .findFirstByTicket_IdAndDirectionAndStatusOrderByRoundNumberDescRevisionDescCreatedAtDesc(
                        ticketId, HandoverDirection.INBOUND, HandoverStatus.SUBMITTED)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.CONFLICT,
                        "No submitted inbound handover found awaiting Technical receipt."));
    }

    @Transactional
    public MaintenanceWorkflowDetailResponse confirmOrRejectTechnicalReceipt(
            String ticketId,
            TechnicalReceiptRequest request) {
        User actor = requireAuthenticatedNonCustomer();
        MaintenanceTicket ticket = lockTicket(ticketId);

        if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
            Optional<MaintenanceHandover> idempotent = handoverRepository.findByIdempotencyKey(request.idempotencyKey().trim());
            if (idempotent.isPresent() && ticketId.equals(idempotent.get().getTicket().getId())) {
                return buildDetailResponse(ticket, actor);
            }
        }

        transitionPolicy.requireDesignatedTechnical(actor, ticket);
        if (!capabilityResolver.isQualifiedTechnical(actor)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "Actor does not hold an active Technical qualification.");
        }
        transitionPolicy.requireExpectedVersion(ticket, request.expectedVersion());
        transitionPolicy.requireStageIn(ticket, MaintenanceWorkflowStage.AWAITING_TECHNICAL_RECEIPT);

        MaintenanceHandover inbound = handoverRepository
                .findFirstByTicket_IdAndDirectionAndStatusOrderByRoundNumberDescRevisionDescCreatedAtDesc(
                        ticketId, HandoverDirection.INBOUND, HandoverStatus.SUBMITTED)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.CONFLICT,
                        "No submitted inbound handover found awaiting Technical receipt."));

        Instant now = Instant.now();
        List<String> receiptEvidenceIds = validateEvidenceIdsForTicket(ticketId, request.receiptEvidenceIds());
        applyItemReceipts(inbound, request.itemReceipts());

        MaintenanceWorkflowStage nextStage;
        String auditEventAction;
        String reason;

        if (Boolean.TRUE.equals(request.accepted())) {
            if (Boolean.TRUE.equals(request.hasDiscrepancy())) {
                if (isBlank(request.discrepancyNotes())) {
                    throw new ApiException(
                            HttpStatus.BAD_REQUEST,
                            "Discrepancy description is required when confirming receipt with discrepancies.");
                }
                inbound.setStatus(HandoverStatus.RECEIVED_WITH_DISCREPANCY);
                inbound.setDiscrepancyNotes(request.discrepancyNotes().trim());
                reason = inbound.getDiscrepancyNotes();
                auditEventAction = "TECHNICAL_RECEIVED_WITH_DISCREPANCY";
            } else {
                inbound.setStatus(HandoverStatus.RECEIVED);
                reason = "Confirmed full receipt of drone and accessories.";
                auditEventAction = "TECHNICAL_RECEIVED_FULL";
            }
            nextStage = MaintenanceWorkflowStage.RECEIVED;
        } else {
            if (isBlank(request.rejectionReason())) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "Rejection reason is required when rejecting handover receipt.");
            }
            inbound.setStatus(HandoverStatus.REJECTED);
            inbound.setRejectionReason(request.rejectionReason().trim());
            reason = inbound.getRejectionReason();
            auditEventAction = "TECHNICAL_REJECTED_RECEIPT";
            nextStage = MaintenanceWorkflowStage.RECEIPT_REJECTED;
        }

        // Backend-authoritative receiver identity and confirmation timestamp.
        inbound.setConfirmedBy(actor);
        inbound.setConfirmedAt(now);
        inbound.setReceiptEvidenceRefsJson(toJson(receiptEvidenceIds));
        if (trimToNull(request.idempotencyKey()) != null && inbound.getIdempotencyKey() == null) {
            inbound.setIdempotencyKey(request.idempotencyKey().trim());
        }
        handoverRepository.save(inbound);

        MaintenanceWorkflowStage previous = transitionPolicy.effectiveStage(ticket);
        ticket.setWorkflowStage(nextStage);
        ticket.setStatus(nextStage.toTicketStatus());
        ticket.setWorkflowUpdatedAt(now);
        MaintenanceTicket saved = ticketRepository.saveAndFlush(ticket);

        auditAdapter.record(
                saved,
                actor,
                auditEventAction,
                AuditAction.STATUS_CHANGE,
                previous,
                nextStage,
                inbound.getRevision(),
                reason,
                receiptEvidenceIds,
                Map.of("handoverId", inbound.getId(), "accepted", request.accepted()));

        return buildDetailResponse(saved, actor);
    }

    @Transactional
    public MaintenanceWorkflowDetailResponse startWork(String ticketId, StartWorkRequest request) {
        User actor = requireAuthenticatedNonCustomer();
        MaintenanceTicket ticket = lockTicket(ticketId);
        transitionPolicy.requireDesignatedTechnical(actor, ticket);
        transitionPolicy.requireExpectedVersion(ticket, request.expectedVersion());
        transitionPolicy.requireStageIn(
                ticket,
                MaintenanceWorkflowStage.RECEIVED,
                MaintenanceWorkflowStage.REWORK_REQUIRED);

        MaintenanceWorkflowStage previous = transitionPolicy.effectiveStage(ticket);
        Instant now = Instant.now();
        ticket.setWorkflowStage(MaintenanceWorkflowStage.IN_MAINTENANCE);
        ticket.setStatus(MaintenanceWorkflowStage.IN_MAINTENANCE.toTicketStatus());
        ticket.setWaitingReason(null);
        ticket.setWaitingPartsNeeded(null);
        ticket.setWaitingExpectedAt(null);
        ticket.setWorkflowUpdatedAt(now);
        MaintenanceTicket saved = ticketRepository.saveAndFlush(ticket);

        auditAdapter.record(
                saved,
                actor,
                "START_MAINTENANCE_WORK",
                AuditAction.STATUS_CHANGE,
                previous,
                MaintenanceWorkflowStage.IN_MAINTENANCE,
                null,
                trimToNull(request.notes()),
                null,
                Map.of("round", saved.getCurrentRound()));

        return buildDetailResponse(saved, actor);
    }

    @Transactional
    public MaintenanceWorkflowDetailResponse pauseWaitingParts(String ticketId, WaitPartsRequest request) {
        User actor = requireAuthenticatedNonCustomer();
        MaintenanceTicket ticket = lockTicket(ticketId);
        transitionPolicy.requireDesignatedTechnical(actor, ticket);
        transitionPolicy.requireExpectedVersion(ticket, request.expectedVersion());
        transitionPolicy.requireStageIn(ticket, MaintenanceWorkflowStage.IN_MAINTENANCE);

        MaintenanceWorkflowStage previous = transitionPolicy.effectiveStage(ticket);
        Instant now = Instant.now();
        ticket.setWorkflowStage(MaintenanceWorkflowStage.WAITING_PARTS);
        ticket.setStatus(MaintenanceWorkflowStage.WAITING_PARTS.toTicketStatus());
        ticket.setWaitingReason(request.reason().trim());
        ticket.setWaitingPartsNeeded(request.partsNeeded().trim());
        ticket.setWaitingExpectedAt(request.expectedAt());
        ticket.setWorkflowUpdatedAt(now);
        MaintenanceTicket saved = ticketRepository.saveAndFlush(ticket);

        auditAdapter.record(
                saved,
                actor,
                "PAUSE_WAITING_PARTS",
                AuditAction.STATUS_CHANGE,
                previous,
                MaintenanceWorkflowStage.WAITING_PARTS,
                null,
                request.reason().trim(),
                null,
                Map.of("partsNeeded", request.partsNeeded().trim()));

        return buildDetailResponse(saved, actor);
    }

    @Transactional
    public MaintenanceWorkflowDetailResponse resumeWork(String ticketId, ResumeWorkRequest request) {
        User actor = requireAuthenticatedNonCustomer();
        MaintenanceTicket ticket = lockTicket(ticketId);
        transitionPolicy.requireDesignatedTechnical(actor, ticket);
        transitionPolicy.requireExpectedVersion(ticket, request.expectedVersion());
        transitionPolicy.requireStageIn(ticket, MaintenanceWorkflowStage.WAITING_PARTS);

        MaintenanceWorkflowStage previous = transitionPolicy.effectiveStage(ticket);
        Instant now = Instant.now();
        ticket.setWorkflowStage(MaintenanceWorkflowStage.IN_MAINTENANCE);
        ticket.setStatus(MaintenanceWorkflowStage.IN_MAINTENANCE.toTicketStatus());
        ticket.setWaitingReason(null);
        ticket.setWaitingPartsNeeded(null);
        ticket.setWaitingExpectedAt(null);
        ticket.setWorkflowUpdatedAt(now);
        MaintenanceTicket saved = ticketRepository.saveAndFlush(ticket);

        auditAdapter.record(
                saved,
                actor,
                "RESUME_MAINTENANCE_WORK",
                AuditAction.STATUS_CHANGE,
                previous,
                MaintenanceWorkflowStage.IN_MAINTENANCE,
                null,
                trimToNull(request.notes()),
                null,
                null);

        return buildDetailResponse(saved, actor);
    }

    @Transactional
    public MaintenanceWorkflowDetailResponse addWorkItem(String ticketId, WorkItemRequest request) {
        User actor = requireAuthenticatedNonCustomer();
        MaintenanceTicket ticket = lockTicket(ticketId);
        transitionPolicy.requireDesignatedTechnical(actor, ticket);
        transitionPolicy.requireExpectedVersion(ticket, request.expectedVersion());
        transitionPolicy.requireStageIn(
                ticket,
                MaintenanceWorkflowStage.IN_MAINTENANCE,
                MaintenanceWorkflowStage.WAITING_PARTS,
                MaintenanceWorkflowStage.REWORK_REQUIRED);
        validateTimestamps(request.startedAt(), request.endedAt());

        List<String> validatedEvidences = validateEvidenceIdsForTicket(ticketId, request.evidenceIds());

        MaintenanceWorkItem item = new MaintenanceWorkItem();
        item.setTicket(ticket);
        item.setRoundNumber(ticket.getCurrentRound() == null ? 1 : ticket.getCurrentRound());
        item.setComponentArea(request.componentArea().trim());
        item.setSymptom(trimToNull(request.symptom()));
        item.setDiagnosis(trimToNull(request.diagnosis()));
        item.setActionType(request.actionType());
        item.setWorkPerformed(trimToNull(request.workPerformed()));
        item.setPerformedBy(actor);
        item.setStartedAt(request.startedAt());
        item.setEndedAt(request.endedAt());
        item.setStatus(request.status());
        item.setResultSummary(trimToNull(request.resultSummary()));
        item.setEvidenceRefsJson(toJson(validatedEvidences));
        workItemRepository.save(item);

        ticket.setWorkflowUpdatedAt(Instant.now());
        MaintenanceTicket saved = ticketRepository.saveAndFlush(ticket);

        auditAdapter.record(
                saved,
                actor,
                "ADD_WORK_ITEM",
                AuditAction.UPDATE,
                transitionPolicy.effectiveStage(saved),
                transitionPolicy.effectiveStage(saved),
                null,
                item.getComponentArea() + " (" + item.getActionType() + ")",
                validatedEvidences,
                Map.of("workItemId", item.getId()));

        return buildDetailResponse(saved, actor);
    }

    @Transactional
    public MaintenanceWorkflowDetailResponse updateWorkItem(
            String ticketId,
            String workItemId,
            WorkItemRequest request) {
        User actor = requireAuthenticatedNonCustomer();
        MaintenanceTicket ticket = lockTicket(ticketId);
        transitionPolicy.requireDesignatedTechnical(actor, ticket);
        transitionPolicy.requireExpectedVersion(ticket, request.expectedVersion());
        transitionPolicy.requireStageIn(
                ticket,
                MaintenanceWorkflowStage.IN_MAINTENANCE,
                MaintenanceWorkflowStage.WAITING_PARTS,
                MaintenanceWorkflowStage.REWORK_REQUIRED);
        validateTimestamps(request.startedAt(), request.endedAt());

        MaintenanceWorkItem item = workItemRepository.findByIdAndTicket_Id(workItemId, ticketId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Work item not found: " + workItemId));

        if (!Objects.equals(item.getRoundNumber(), ticket.getCurrentRound())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Historical work items from previous rounds are immutable; add a new work item in the current round.");
        }

        List<String> validatedEvidences = validateEvidenceIdsForTicket(ticketId, request.evidenceIds());

        item.setComponentArea(request.componentArea().trim());
        item.setSymptom(trimToNull(request.symptom()));
        item.setDiagnosis(trimToNull(request.diagnosis()));
        item.setActionType(request.actionType());
        item.setWorkPerformed(trimToNull(request.workPerformed()));
        item.setPerformedBy(actor);
        item.setStartedAt(request.startedAt());
        item.setEndedAt(request.endedAt());
        item.setStatus(request.status());
        item.setResultSummary(trimToNull(request.resultSummary()));
        item.setEvidenceRefsJson(toJson(validatedEvidences));
        workItemRepository.save(item);

        ticket.setWorkflowUpdatedAt(Instant.now());
        MaintenanceTicket saved = ticketRepository.saveAndFlush(ticket);

        auditAdapter.record(
                saved,
                actor,
                "UPDATE_WORK_ITEM",
                AuditAction.UPDATE,
                transitionPolicy.effectiveStage(saved),
                transitionPolicy.effectiveStage(saved),
                null,
                item.getComponentArea(),
                validatedEvidences,
                Map.of("workItemId", item.getId()));

        return buildDetailResponse(saved, actor);
    }

    @Transactional
    public MaintenanceWorkflowDetailResponse addPartReplacement(
            String ticketId,
            PartReplacementRequest request) {
        User actor = requireAuthenticatedNonCustomer();
        MaintenanceTicket ticket = lockTicket(ticketId);
        transitionPolicy.requireDesignatedTechnical(actor, ticket);
        transitionPolicy.requireExpectedVersion(ticket, request.expectedVersion());
        transitionPolicy.requireStageIn(
                ticket,
                MaintenanceWorkflowStage.IN_MAINTENANCE,
                MaintenanceWorkflowStage.WAITING_PARTS,
                MaintenanceWorkflowStage.REWORK_REQUIRED);

        List<String> validatedEvidences = validateEvidenceIdsForTicket(ticketId, request.evidenceIds());

        MaintenancePartReplacement part = new MaintenancePartReplacement();
        part.setTicket(ticket);
        part.setRoundNumber(ticket.getCurrentRound() == null ? 1 : ticket.getCurrentRound());
        part.setPartName(request.partName().trim());
        part.setPartNumber(trimToNull(request.partNumber()));
        part.setOldSerial(trimToNull(request.oldSerial()));
        part.setNewSerial(trimToNull(request.newSerial()));
        part.setQuantity(request.quantity());
        part.setUnit(request.unit().trim());
        part.setReplacementReason(request.replacementReason().trim());
        part.setRemovedPartCondition(trimToNull(request.removedPartCondition()));
        part.setCostAmount(request.costAmount());
        part.setCurrency(request.costAmount() != null ? blankToFallback(request.currency(), "VND") : null);
        part.setEvidenceRefsJson(toJson(validatedEvidences));
        part.setRecordedBy(actor);
        partRepository.save(part);

        ticket.setWorkflowUpdatedAt(Instant.now());
        MaintenanceTicket saved = ticketRepository.saveAndFlush(ticket);

        auditAdapter.record(
                saved,
                actor,
                "RECORD_PART_REPLACEMENT",
                AuditAction.UPDATE,
                transitionPolicy.effectiveStage(saved),
                transitionPolicy.effectiveStage(saved),
                null,
                part.getPartName() + " x" + part.getQuantity() + " " + part.getUnit(),
                validatedEvidences,
                Map.of("partReplacementId", part.getId()));

        return buildDetailResponse(saved, actor);
    }

    @Transactional
    public MaintenanceWorkflowDetailResponse saveReportDraft(
            String ticketId,
            SaveReportDraftRequest request) {
        User actor = requireAuthenticatedNonCustomer();
        MaintenanceTicket ticket = lockTicket(ticketId);
        transitionPolicy.requireDesignatedTechnical(actor, ticket);
        transitionPolicy.requireExpectedVersion(ticket, request.expectedVersion());
        transitionPolicy.requireStageIn(
                ticket,
                MaintenanceWorkflowStage.IN_MAINTENANCE,
                MaintenanceWorkflowStage.WAITING_PARTS,
                MaintenanceWorkflowStage.REWORK_REQUIRED);

        int currentRound = ticket.getCurrentRound() == null ? 1 : ticket.getCurrentRound();
        Optional<MaintenanceReportVersion> latestOpt = reportRepository.findFirstByTicket_IdOrderByRevisionDesc(ticketId);

        MaintenanceReportVersion report;
        if (latestOpt.isPresent()
                && !latestOpt.get().isLocked()
                && latestOpt.get().getStatus() == MaintenanceReportStatus.DRAFT
                && Objects.equals(latestOpt.get().getRoundNumber(), currentRound)) {
            report = latestOpt.get();
        } else {
            int nextRevision = latestOpt.map(r -> r.getRevision() + 1).orElse(1);
            report = new MaintenanceReportVersion();
            report.setTicket(ticket);
            report.setRoundNumber(currentRound);
            report.setRevision(nextRevision);
            report.setStatus(MaintenanceReportStatus.DRAFT);
            report.setLocked(false);
        }

        List<String> beforeEvidences = validateEvidenceIdsForTicket(ticketId, request.beforeEvidenceIds());
        List<String> afterEvidences = validateEvidenceIdsForTicket(ticketId, request.afterEvidenceIds());

        report.setReceivedConditionSummary(trimToNull(request.receivedConditionSummary()));
        report.setFaultDiagnosisSummary(trimToNull(request.faultDiagnosisSummary()));
        report.setWorkSummary(trimToNull(request.workSummary()));
        report.setPartsSummary(trimToNull(request.partsSummary()));
        report.setPostCheckSummary(trimToNull(request.postCheckSummary()));
        report.setRemainingIssues(trimToNull(request.remainingIssues()));
        report.setUsageLimitations(trimToNull(request.usageLimitations()));
        report.setFollowUpRecommendations(trimToNull(request.followUpRecommendations()));
        report.setBeforeEvidenceRefsJson(toJson(beforeEvidences));
        report.setAfterEvidenceRefsJson(toJson(afterEvidences));
        report.setAuthoredBy(actor);

        report.getPostChecks().clear();
        if (request.postChecks() != null) {
            for (PostCheckInput input : request.postChecks()) {
                if (input.result() == PostCheckResult.NOT_APPLICABLE && isBlank(input.notApplicableReason())) {
                    throw new ApiException(
                            HttpStatus.BAD_REQUEST,
                            "Reason is required when post-maintenance check '" + input.checkName() + "' is NOT_APPLICABLE.");
                }
                List<String> checkEvidences = validateEvidenceIdsForTicket(ticketId, input.evidenceIds());
                MaintenancePostCheck check = new MaintenancePostCheck();
                check.setReportVersion(report);
                check.setCheckName(input.checkName().trim());
                check.setCheckMethod(input.checkMethod().trim());
                check.setResult(input.result());
                check.setNotes(trimToNull(input.notes()));
                check.setNotApplicableReason(trimToNull(input.notApplicableReason()));
                check.setRequired(input.required());
                check.setEvidenceRequired(input.evidenceRequired());
                check.setEvidenceRefsJson(toJson(checkEvidences));
                report.getPostChecks().add(check);
            }
        }
        reportRepository.save(report);

        ticket.setWorkflowUpdatedAt(Instant.now());
        MaintenanceTicket saved = ticketRepository.saveAndFlush(ticket);

        auditAdapter.record(
                saved,
                actor,
                "SAVE_REPORT_DRAFT",
                AuditAction.UPDATE,
                transitionPolicy.effectiveStage(saved),
                transitionPolicy.effectiveStage(saved),
                report.getRevision(),
                "Saved maintenance report draft rev " + report.getRevision(),
                afterEvidences,
                Map.of("reportVersionId", report.getId(), "revision", report.getRevision()));

        return buildDetailResponse(saved, actor);
    }

    @Transactional
    public MaintenanceWorkflowDetailResponse submitReturnHandover(
            String ticketId,
            SubmitReturnHandoverRequest request) {
        User actor = requireAuthenticatedNonCustomer();
        MaintenanceTicket ticket = lockTicket(ticketId);

        if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
            Optional<MaintenanceHandover> existing = handoverRepository.findByIdempotencyKey(request.idempotencyKey().trim());
            if (existing.isPresent() && ticketId.equals(existing.get().getTicket().getId())) {
                return buildDetailResponse(ticket, actor);
            }
        }

        transitionPolicy.requireDesignatedTechnical(actor, ticket);
        transitionPolicy.requireExpectedVersion(ticket, request.expectedVersion());
        transitionPolicy.requireStageIn(
                ticket,
                MaintenanceWorkflowStage.IN_MAINTENANCE,
                MaintenanceWorkflowStage.REWORK_REQUIRED);

        User manager = userRepository.findById(request.managerUserId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Manager user not found: " + request.managerUserId()));
        if (!capabilityResolver.isEligibleReceivingManager(manager)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Selected receiving user is not an active Manager/Admin.");
        }
        if (actor.getId().equals(manager.getId())) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "Technical cannot submit a return handover to themselves.");
        }

        MaintenanceReportVersion latestReport = reportRepository
                .findFirstByTicket_IdOrderByRevisionDesc(ticketId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "A structured maintenance report must be saved before submitting return handover."));

        int completedWorkItems = (int) workItemRepository.findByTicket_IdOrderByRoundNumberAscCreatedAtAsc(ticketId)
                .stream()
                .filter(w -> w.getStatus() == MaintenanceWorkStatus.COMPLETED)
                .count();

        List<String> blockers = transitionPolicy.computeReturnSubmissionBlockers(latestReport, completedWorkItems);
        if (!blockers.isEmpty()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Cannot submit return handover until blockers are resolved: " + String.join(", ", blockers));
        }

        Instant now = Instant.now();
        latestReport.setStatus(MaintenanceReportStatus.SUBMITTED);
        latestReport.setSubmittedAt(now);
        latestReport.setLocked(true);
        reportRepository.save(latestReport);

        List<String> returnEvidenceIds = validateEvidenceIdsForTicket(ticketId, request.evidenceIds());

        int nextReturnRevision = handoverRepository
                .findFirstByTicket_IdAndDirectionOrderByRoundNumberDescRevisionDescCreatedAtDesc(
                        ticketId, HandoverDirection.RETURN)
                .map(h -> h.getRevision() + 1)
                .orElse(1);

        MaintenanceHandover returnHandover = new MaintenanceHandover();
        returnHandover.setTicket(ticket);
        returnHandover.setDevice(ticket.getDevice());
        returnHandover.setDirection(HandoverDirection.RETURN);
        returnHandover.setRoundNumber(ticket.getCurrentRound() == null ? 1 : ticket.getCurrentRound());
        returnHandover.setRevision(nextReturnRevision);
        returnHandover.setStatus(HandoverStatus.SUBMITTED);
        returnHandover.setFromUser(actor);
        returnHandover.setToUser(manager);
        returnHandover.setOverallCondition(request.postMaintenanceCondition().trim());
        returnHandover.setUnfinishedWorkNotes(trimToNull(request.unfinishedWorkNotes()));
        returnHandover.setActualHandoverAt(now);
        returnHandover.setSubmittedAt(now);
        returnHandover.setReportRevision(latestReport.getRevision());
        returnHandover.setEvidenceRefsJson(toJson(returnEvidenceIds));
        returnHandover.setIdempotencyKey(trimToNull(request.idempotencyKey()));
        returnHandover.setLocked(true);
        replaceHandoverItems(returnHandover, request.returnedAccessories());
        handoverRepository.save(returnHandover);

        MaintenanceWorkflowStage previous = transitionPolicy.effectiveStage(ticket);
        ticket.setReceivingManager(manager);
        ticket.setWorkflowStage(MaintenanceWorkflowStage.AWAITING_MANAGER_ACCEPTANCE);
        ticket.setStatus(MaintenanceWorkflowStage.AWAITING_MANAGER_ACCEPTANCE.toTicketStatus());
        ticket.setResolutionNotes(latestReport.getWorkSummary());
        ticket.setResolvedAt(now);
        ticket.setWorkflowUpdatedAt(now);
        MaintenanceTicket saved = ticketRepository.saveAndFlush(ticket);

        // Keep device blocked until Manager explicitly accepts return.
        eligibilityGuard.blockDeviceForMaintenance(saved.getDevice());

        auditAdapter.record(
                saved,
                actor,
                "SUBMIT_RETURN_HANDOVER",
                AuditAction.STATUS_CHANGE,
                previous,
                MaintenanceWorkflowStage.AWAITING_MANAGER_ACCEPTANCE,
                latestReport.getRevision(),
                "Submitted report rev " + latestReport.getRevision() + " to Manager " + manager.getFullName(),
                returnEvidenceIds,
                Map.of(
                        "returnHandoverId", returnHandover.getId(),
                        "reportRevision", latestReport.getRevision(),
                        "receivingManagerId", manager.getId()));

        return buildDetailResponse(saved, actor);
    }

    @Transactional
    public MaintenanceWorkflowDetailResponse acceptReturn(
            String ticketId,
            AcceptReturnRequest request) {
        User actor = requireAuthenticatedNonCustomer();
        MaintenanceTicket ticket = lockTicket(ticketId);

        if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
            Optional<MaintenanceAcceptanceDecision> existing = decisionRepository
                    .findByIdempotencyKey(request.idempotencyKey().trim());
            if (existing.isPresent() && ticketId.equals(existing.get().getTicket().getId())) {
                return buildDetailResponse(ticket, actor);
            }
        }

        transitionPolicy.requireReceivingManager(actor, ticket);
        transitionPolicy.requireExpectedVersion(ticket, request.expectedVersion());
        transitionPolicy.requireStageIn(ticket, MaintenanceWorkflowStage.AWAITING_MANAGER_ACCEPTANCE);

        if (!Boolean.TRUE.equals(request.deviceMatchConfirmed())
                || !Boolean.TRUE.equals(request.accessoriesMatchConfirmed())) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Manager must confirm both drone/serial verification and accessories verification to accept return.");
        }

        MaintenanceHandover returnHandover = handoverRepository
                .findFirstByTicket_IdAndDirectionAndStatusOrderByRoundNumberDescRevisionDescCreatedAtDesc(
                        ticketId, HandoverDirection.RETURN, HandoverStatus.SUBMITTED)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.CONFLICT,
                        "No submitted return handover is awaiting Manager acceptance."));

        if (!Objects.equals(returnHandover.getReportRevision(), request.reportRevision())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Report revision mismatch: submitted revision is " + returnHandover.getReportRevision()
                            + ", requested revision is " + request.reportRevision() + ".");
        }

        MaintenanceReportVersion reportVersion = reportRepository
                .findByTicket_IdAndRevision(ticketId, request.reportRevision())
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "Submitted report revision not found: " + request.reportRevision()));

        boolean hasFailedRequiredCheck = reportVersion.getPostChecks().stream()
                .anyMatch(check -> check.isRequired() && check.getResult() == PostCheckResult.FAIL);
        if (hasFailedRequiredCheck) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Cannot accept return while a required post-maintenance check is FAIL. Request rework instead.");
        }

        Instant now = Instant.now();
        returnHandover.setStatus(HandoverStatus.ACCEPTED);
        returnHandover.setConfirmedBy(actor);
        returnHandover.setConfirmedAt(now);
        handoverRepository.save(returnHandover);

        reportVersion.setStatus(MaintenanceReportStatus.ACCEPTED);
        reportVersion.setLocked(true);
        reportRepository.save(reportVersion);

        MaintenanceWorkflowStage previous = transitionPolicy.effectiveStage(ticket);
        ticket.setWorkflowStage(MaintenanceWorkflowStage.COMPLETED);
        ticket.setStatus(MaintenanceWorkflowStage.COMPLETED.toTicketStatus());
        ticket.setAcceptedReportRevision(reportVersion.getRevision());
        ticket.setClosedAt(now);
        ticket.setWorkflowUpdatedAt(now);
        MaintenanceTicket saved = ticketRepository.saveAndFlush(ticket);

        MaintenanceEligibilityGuard.UnlockOutcome outcome = eligibilityGuard.completeAndEvaluateUnlock(
                saved,
                actor.getId(),
                now,
                reportVersion.getWorkSummary());

        MaintenanceAcceptanceDecision decision = new MaintenanceAcceptanceDecision();
        decision.setTicket(saved);
        decision.setReturnHandover(returnHandover);
        decision.setReportVersion(reportVersion);
        decision.setRoundNumber(returnHandover.getRoundNumber());
        decision.setReportRevision(reportVersion.getRevision());
        decision.setDecision(AcceptanceDecisionType.ACCEPTED);
        decision.setDecidedBy(actor);
        decision.setDecidedAt(now);
        decision.setDeviceMatchConfirmed(true);
        decision.setAccessoriesMatchConfirmed(true);
        decision.setVerificationNotes(trimToNull(request.verificationNotes()));
        decision.setCheckpointCreated(outcome.checkpointCreated());
        decision.setDeviceUnlocked(outcome.deviceUnlocked());
        decision.setRemainingBlockersJson(toJson(outcome.remainingBlockers()));
        decision.setIdempotencyKey(trimToNull(request.idempotencyKey()));
        decisionRepository.save(decision);

        auditAdapter.record(
                saved,
                actor,
                "MANAGER_ACCEPTED_RETURN",
                AuditAction.APPROVE,
                previous,
                MaintenanceWorkflowStage.COMPLETED,
                reportVersion.getRevision(),
                trimToNull(request.verificationNotes()),
                null,
                Map.of(
                        "decisionId", decision.getId(),
                        "checkpointCreated", outcome.checkpointCreated(),
                        "deviceUnlocked", outcome.deviceUnlocked(),
                        "remainingBlockers", outcome.remainingBlockers()));

        return buildDetailResponse(saved, actor);
    }

    @Transactional
    public MaintenanceWorkflowDetailResponse requestRework(
            String ticketId,
            RequestReworkRequest request) {
        User actor = requireAuthenticatedNonCustomer();
        MaintenanceTicket ticket = lockTicket(ticketId);

        if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
            Optional<MaintenanceAcceptanceDecision> existing = decisionRepository
                    .findByIdempotencyKey(request.idempotencyKey().trim());
            if (existing.isPresent() && ticketId.equals(existing.get().getTicket().getId())) {
                return buildDetailResponse(ticket, actor);
            }
        }

        transitionPolicy.requireReceivingManager(actor, ticket);
        transitionPolicy.requireExpectedVersion(ticket, request.expectedVersion());
        transitionPolicy.requireStageIn(ticket, MaintenanceWorkflowStage.AWAITING_MANAGER_ACCEPTANCE);

        MaintenanceHandover returnHandover = handoverRepository
                .findFirstByTicket_IdAndDirectionAndStatusOrderByRoundNumberDescRevisionDescCreatedAtDesc(
                        ticketId, HandoverDirection.RETURN, HandoverStatus.SUBMITTED)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.CONFLICT,
                        "No submitted return handover is awaiting Manager review."));

        if (!Objects.equals(returnHandover.getReportRevision(), request.reportRevision())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Report revision mismatch: submitted revision is " + returnHandover.getReportRevision()
                            + ", requested revision is " + request.reportRevision() + ".");
        }

        MaintenanceReportVersion reportVersion = reportRepository
                .findByTicket_IdAndRevision(ticketId, request.reportRevision())
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "Submitted report revision not found: " + request.reportRevision()));

        Instant now = Instant.now();
        returnHandover.setStatus(HandoverStatus.REWORK_REQUESTED);
        returnHandover.setConfirmedBy(actor);
        returnHandover.setConfirmedAt(now);
        returnHandover.setRejectionReason(request.reason().trim());
        handoverRepository.save(returnHandover);

        // Preserve previous report revision immutably; Technical must author a new revision in the next round.
        reportVersion.setStatus(MaintenanceReportStatus.REWORK_REQUIRED);
        reportVersion.setLocked(true);
        reportRepository.save(reportVersion);

        int previousRound = ticket.getCurrentRound() == null ? 1 : ticket.getCurrentRound();
        MaintenanceWorkflowStage previous = transitionPolicy.effectiveStage(ticket);
        ticket.setWorkflowStage(MaintenanceWorkflowStage.REWORK_REQUIRED);
        ticket.setStatus(MaintenanceWorkflowStage.REWORK_REQUIRED.toTicketStatus());
        ticket.setCurrentRound(previousRound + 1);
        ticket.setWorkflowUpdatedAt(now);
        MaintenanceTicket saved = ticketRepository.saveAndFlush(ticket);

        eligibilityGuard.blockDeviceForMaintenance(saved.getDevice());

        MaintenanceAcceptanceDecision decision = new MaintenanceAcceptanceDecision();
        decision.setTicket(saved);
        decision.setReturnHandover(returnHandover);
        decision.setReportVersion(reportVersion);
        decision.setRoundNumber(previousRound);
        decision.setReportRevision(reportVersion.getRevision());
        decision.setDecision(AcceptanceDecisionType.REWORK_REQUIRED);
        decision.setDecidedBy(actor);
        decision.setDecidedAt(now);
        decision.setDeviceMatchConfirmed(false);
        decision.setAccessoriesMatchConfirmed(false);
        decision.setReworkReason(request.reason().trim());
        decision.setFailedWorkItemIdsJson(toJson(request.failedWorkItemIds()));
        decision.setCheckpointCreated(false);
        decision.setDeviceUnlocked(false);
        decision.setRemainingBlockersJson(toJson(List.of("REWORK_REQUIRED")));
        decision.setIdempotencyKey(trimToNull(request.idempotencyKey()));
        decisionRepository.save(decision);

        auditAdapter.record(
                saved,
                actor,
                "MANAGER_REQUESTED_REWORK",
                AuditAction.STATUS_CHANGE,
                previous,
                MaintenanceWorkflowStage.REWORK_REQUIRED,
                reportVersion.getRevision(),
                request.reason().trim(),
                null,
                Map.of(
                        "decisionId", decision.getId(),
                        "nextRound", saved.getCurrentRound(),
                        "failedWorkItemIds", request.failedWorkItemIds() == null ? List.of() : request.failedWorkItemIds()));

        return buildDetailResponse(saved, actor);
    }

    @Transactional
    public MaintenanceWorkflowDetailResponse cancelWorkflow(String ticketId, CancelWorkflowRequest request) {
        User actor = requireAuthenticatedNonCustomer();
        MaintenanceTicket ticket = lockTicket(ticketId);
        requireCustodianOrManager(actor, ticket);
        transitionPolicy.requireExpectedVersion(ticket, request.expectedVersion());
        transitionPolicy.requireStageIn(
                ticket,
                MaintenanceWorkflowStage.DRAFT_HANDOVER,
                MaintenanceWorkflowStage.RECEIPT_REJECTED);

        MaintenanceWorkflowStage previous = transitionPolicy.effectiveStage(ticket);
        Instant now = Instant.now();
        ticket.setWorkflowStage(MaintenanceWorkflowStage.CANCELLED);
        ticket.setStatus(MaintenanceWorkflowStage.CANCELLED.toTicketStatus());
        ticket.setResolutionNotes("Cancelled: " + request.reason().trim());
        ticket.setClosedAt(now);
        ticket.setWorkflowUpdatedAt(now);
        MaintenanceTicket saved = ticketRepository.saveAndFlush(ticket);

        auditAdapter.record(
                saved,
                actor,
                "CANCEL_MAINTENANCE_WORKFLOW",
                AuditAction.STATUS_CHANGE,
                previous,
                MaintenanceWorkflowStage.CANCELLED,
                null,
                request.reason().trim(),
                null,
                null);

        return buildDetailResponse(saved, actor);
    }

    @Transactional
    public EvidenceResponse uploadEvidence(
            String ticketId,
            MaintenanceEvidenceStage stage,
            String referenceId,
            String caption,
            MultipartFile file) {
        User actor = requireAuthenticatedNonCustomer();
        MaintenanceTicket ticket = findTicket(ticketId);
        requireTicketAccess(actor, ticket);

        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Evidence file is required.");
        }
        String contentType = ImageFileValidator.validate(file);
        String extension = switch (contentType) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> ".jpg";
        };
        String storageKey = "maintenance-evidence/" + ticketId + "/" + UUID.randomUUID() + extension;
        String bucket = null;
        if (mediaObjectStorage != null) {
            bucket = mediaObjectStorage.bucket();
            try (var input = file.getInputStream()) {
                mediaObjectStorage.put(storageKey, contentType, file.getSize(), input, "maintenance-evidence");
            } catch (IOException | RuntimeException ex) {
                log.warn("Maintenance evidence upload failed for ticket {}", ticketId, ex);
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Failed to upload evidence file to storage.");
            }
        }

        MaintenanceEvidence evidence = new MaintenanceEvidence();
        evidence.setTicket(ticket);
        evidence.setStage(stage);
        evidence.setReferenceId(trimToNull(referenceId));
        evidence.setStorageKey(storageKey);
        evidence.setStorageBucket(bucket);
        evidence.setFileName(file.getOriginalFilename() != null ? file.getOriginalFilename() : "evidence" + extension);
        evidence.setContentType(contentType);
        evidence.setFileSizeBytes(file.getSize());
        evidence.setCaption(trimToNull(caption));
        evidence.setUploadedBy(actor);
        evidence.setUploadedAt(Instant.now());
        MaintenanceEvidence saved = evidenceRepository.save(evidence);

        auditAdapter.record(
                ticket,
                actor,
                "UPLOAD_EVIDENCE",
                AuditAction.CREATE,
                transitionPolicy.effectiveStage(ticket),
                transitionPolicy.effectiveStage(ticket),
                null,
                saved.getFileName(),
                List.of(saved.getId()),
                Map.of("stage", stage.name()));

        return toEvidenceResponse(saved);
    }

    @Transactional
    public EvidenceResponse registerEvidenceReference(
            String ticketId,
            RegisterEvidenceReferenceRequest request) {
        User actor = requireAuthenticatedNonCustomer();
        MaintenanceTicket ticket = findTicket(ticketId);
        requireTicketAccess(actor, ticket);

        if (request.storageKey().startsWith("data:") || request.storageKey().length() > 700) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Inline base64 data URLs are not permitted. Provide a valid media storage key.");
        }

        MaintenanceEvidence evidence = new MaintenanceEvidence();
        evidence.setTicket(ticket);
        evidence.setStage(request.stage());
        evidence.setReferenceId(trimToNull(request.referenceId()));
        evidence.setStorageKey(request.storageKey().trim());
        evidence.setStorageBucket(trimToNull(request.storageBucket()));
        evidence.setFileName(request.fileName().trim());
        evidence.setContentType(request.contentType().trim());
        evidence.setFileSizeBytes(request.fileSizeBytes());
        evidence.setCaption(trimToNull(request.caption()));
        evidence.setUploadedBy(actor);
        evidence.setUploadedAt(Instant.now());
        MaintenanceEvidence saved = evidenceRepository.save(evidence);

        return toEvidenceResponse(saved);
    }

    @Transactional(readOnly = true)
    public EvidenceResponse getEvidenceForTicket(String ticketId, String evidenceId) {
        User actor = requireAuthenticatedNonCustomer();
        MaintenanceTicket ticket = findTicket(ticketId);
        requireTicketAccess(actor, ticket);
        MaintenanceEvidence evidence = evidenceRepository.findByIdAndTicket_Id(evidenceId, ticketId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "Evidence does not belong to this maintenance ticket."));
        return toEvidenceResponse(evidence);
    }

    @Transactional(readOnly = true)
    public MaintenanceWorkflowDetailResponse getWorkflowDetail(String ticketId) {
        User actor = requireAuthenticatedNonCustomer();
        MaintenanceTicket ticket = findTicket(ticketId);
        requireTicketAccess(actor, ticket);
        return buildDetailResponse(ticket, actor);
    }

    @Transactional(readOnly = true)
    public List<MaintenanceQueueItemResponse> listTechnicalQueue(String queueType) {
        User actor = requireAuthenticatedNonCustomer();
        if (actor.getRole().getCode() != RoleCode.STAFF && actor.getRole().getCode() != RoleCode.ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Technical queue is available to Technical staff.");
        }
        if ("POOL".equalsIgnoreCase(queueType)) {
            return ticketRepository
                    .findByAssignedStaffIsNullAndWorkflowStageOrderByWorkflowUpdatedAtAsc(
                            MaintenanceWorkflowStage.AWAITING_TECHNICAL_RECEIPT)
                    .stream()
                    // Most severe first, then oldest first (repository order is preserved by the stable sort).
                    .sorted(java.util.Comparator.comparingInt(t -> severityRank(t.getSeverity())))
                    .map(ticket -> toQueueItem(ticket, actor))
                    .toList();
        }
        EnumSet<MaintenanceWorkflowStage> stages = "AWAITING_RECEIPT".equalsIgnoreCase(queueType)
                ? TECHNICAL_INBOX_STAGES
                : "IN_PROGRESS".equalsIgnoreCase(queueType)
                        ? TECHNICAL_ACTIVE_STAGES
                        : EnumSet.of(
                                MaintenanceWorkflowStage.AWAITING_TECHNICAL_RECEIPT,
                                MaintenanceWorkflowStage.RECEIVED,
                                MaintenanceWorkflowStage.IN_MAINTENANCE,
                                MaintenanceWorkflowStage.WAITING_PARTS,
                                MaintenanceWorkflowStage.REWORK_REQUIRED,
                                MaintenanceWorkflowStage.AWAITING_MANAGER_ACCEPTANCE,
                                MaintenanceWorkflowStage.COMPLETED);

        List<MaintenanceTicket> tickets = actor.getRole().getCode() == RoleCode.ADMIN
                ? ticketRepository.findByWorkflowStageInOrderByWorkflowUpdatedAtDesc(stages)
                : ticketRepository.findByAssignedStaff_IdAndWorkflowStageInOrderByWorkflowUpdatedAtDesc(
                        actor.getId(), stages);

        return tickets.stream()
                .map(ticket -> toQueueItem(ticket, actor))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MaintenanceQueueItemResponse> listManagerAcceptanceQueue(boolean includeAll) {
        User actor = requireAuthenticatedNonCustomer();
        if (!capabilityResolver.isEligibleReceivingManager(actor)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Manager acceptance queue requires Manager or Admin role.");
        }

        List<MaintenanceTicket> tickets;
        if (includeAll) {
            tickets = ticketRepository.findAll().stream()
                    .filter(t -> t.getWorkflowStage() != null || !Boolean.TRUE.equals(t.getLegacyRecord()))
                    .toList();
        } else if (actor.getRole().getCode() == RoleCode.ADMIN) {
            tickets = ticketRepository.findByWorkflowStageInOrderByWorkflowUpdatedAtDesc(MANAGER_ACCEPTANCE_STAGES);
        } else {
            tickets = ticketRepository.findByReceivingManager_IdAndWorkflowStageInOrderByWorkflowUpdatedAtDesc(
                    actor.getId(), MANAGER_ACCEPTANCE_STAGES);
        }

        return tickets.stream()
                .map(ticket -> toQueueItem(ticket, actor))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<QualifiedTechnicalOption> listQualifiedTechnicals() {
        requireAuthenticatedNonCustomer();
        return capabilityResolver.listQualifiedTechnicals();
    }

    @Transactional(readOnly = true)
    public List<ActorSummary> listReceivingManagers() {
        requireAuthenticatedNonCustomer();
        return capabilityResolver.listReceivingManagers();
    }

    private User requireAuthenticatedNonCustomer() {
        User user = userResolver.getCurrentUser();
        if (user == null || user.getRole() == null || user.getRole().getCode() == RoleCode.CUSTOMER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Customers cannot access internal maintenance records.");
        }
        return user;
    }

    private User requireQualifiedTechnical(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Technical user not found: " + userId));
        if (!capabilityResolver.isQualifiedTechnical(user)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Selected user does not hold an active Technical qualification (TECHNICAL license or MAINTAINER role).");
        }
        return user;
    }

    private User optionalQualifiedTechnical(String userId) {
        return isBlank(userId) ? null : requireQualifiedTechnical(userId.trim());
    }

    private static String technicalRef(User technical) {
        return technical == null ? "SHARED_QUEUE" : technical.getId();
    }

    public static int severityRank(String severity) {
        if (severity == null) return 4;
        return switch (severity.trim().toUpperCase()) {
            case "CRITICAL" -> 0;
            case "HIGH" -> 1;
            case "MEDIUM" -> 2;
            case "LOW" -> 3;
            default -> 4;
        };
    }

    private MaintenanceTicket lockTicket(String ticketId) {
        return ticketRepository.findByIdForUpdate(ticketId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Maintenance ticket not found: " + ticketId));
    }

    private MaintenanceTicket findTicket(String ticketId) {
        return ticketRepository.findById(ticketId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Maintenance ticket not found: " + ticketId));
    }

    private void requireCustodianOrManager(User actor, MaintenanceTicket ticket) {
        if (!capabilityResolver.canActAsCustodian(actor, ticket.getDevice(), ticket)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "Only the current drone custodian or Manager/Admin can manage inbound handover.");
        }
    }

    private void requireTicketAccess(User actor, MaintenanceTicket ticket) {
        boolean isCustodian = capabilityResolver.canActAsCustodian(actor, ticket.getDevice(), ticket);
        if (!transitionPolicy.canViewTicket(actor, ticket, isCustodian)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You do not have access to this maintenance ticket.");
        }
    }

    private List<String> validateEvidenceIdsForTicket(String ticketId, List<String> evidenceIds) {
        if (evidenceIds == null || evidenceIds.isEmpty()) {
            return List.of();
        }
        List<String> valid = new ArrayList<>();
        for (String id : evidenceIds) {
            if (id == null || id.isBlank()) {
                continue;
            }
            String trimmed = id.trim();
            boolean belongs = evidenceRepository.findByIdAndTicket_Id(trimmed, ticketId).isPresent();
            if (!belongs) {
                throw new ApiException(
                        HttpStatus.FORBIDDEN,
                        "Evidence '" + trimmed + "' does not belong to maintenance ticket " + ticketId + ".");
            }
            valid.add(trimmed);
        }
        return valid;
    }

    private void replaceHandoverItems(MaintenanceHandover handover, List<HandoverItemInput> inputs) {
        handover.getItems().clear();
        if (inputs == null) {
            return;
        }
        for (HandoverItemInput input : inputs) {
            MaintenanceHandoverItem item = new MaintenanceHandoverItem();
            item.setHandover(handover);
            item.setItemName(input.itemName().trim());
            item.setExpectedQuantity(input.expectedQuantity());
            item.setConditionNote(trimToNull(input.conditionNote()));
            handover.getItems().add(item);
        }
    }

    private void applyItemReceipts(MaintenanceHandover inbound, List<HandoverItemReceiptInput> receipts) {
        if (receipts == null || receipts.isEmpty()) {
            for (MaintenanceHandoverItem item : inbound.getItems()) {
                if (item.getReceivedQuantity() == null) {
                    item.setReceivedQuantity(item.getExpectedQuantity());
                }
            }
            return;
        }
        Map<String, MaintenanceHandoverItem> byId = new HashMap<>();
        for (MaintenanceHandoverItem item : inbound.getItems()) {
            byId.put(item.getId(), item);
        }
        for (HandoverItemReceiptInput receipt : receipts) {
            MaintenanceHandoverItem item = byId.get(receipt.itemId());
            if (item == null) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "Handover accessory item not found on inbound handover: " + receipt.itemId());
            }
            item.setReceivedQuantity(receipt.receivedQuantity());
            item.setActualConditionNote(trimToNull(receipt.actualConditionNote()));
            item.setDiscrepancyNote(trimToNull(receipt.discrepancyNote()));
        }
    }

    private void validateTimestamps(Instant startedAt, Instant endedAt) {
        if (startedAt != null && endedAt != null && endedAt.isBefore(startedAt)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Work item endedAt cannot be earlier than startedAt.");
        }
    }

    private MaintenanceQueueItemResponse toQueueItem(MaintenanceTicket ticket, User actor) {
        boolean isCustodian = capabilityResolver.canActAsCustodian(actor, ticket.getDevice(), ticket);
        return new MaintenanceQueueItemResponse(
                ticket.getId(),
                ticket.getTicketCode(),
                ticket.getStatus(),
                transitionPolicy.effectiveStage(ticket),
                ticket.getVersion(),
                ticket.getCurrentRound() == null ? 1 : ticket.getCurrentRound(),
                Boolean.TRUE.equals(ticket.getLegacyRecord()),
                ticket.getIssueType(),
                ticket.getSeverity(),
                ticket.getDescription(),
                toDeviceSummary(ticket.getDevice()),
                TechnicalCapabilityResolver.toActorSummary(ticket.getHandedOverBy()),
                TechnicalCapabilityResolver.toActorSummary(ticket.getAssignedStaff()),
                TechnicalCapabilityResolver.toActorSummary(ticket.getReceivingManager()),
                ticket.getOpenedAt(),
                ticket.getWorkflowUpdatedAt() != null ? ticket.getWorkflowUpdatedAt() : ticket.getOpenedAt(),
                transitionPolicy.computeAvailableActions(actor, ticket, isCustodian));
    }

    private MaintenanceWorkflowDetailResponse buildDetailResponse(MaintenanceTicket ticket, User actor) {
        boolean isCustodian = capabilityResolver.canActAsCustodian(actor, ticket.getDevice(), ticket);
        List<MaintenanceHandover> handovers = handoverRepository
                .findByTicket_IdOrderByRoundNumberDescRevisionDescCreatedAtDesc(ticket.getId());
        List<MaintenanceWorkItem> workItems = workItemRepository
                .findByTicket_IdOrderByRoundNumberAscCreatedAtAsc(ticket.getId());
        List<MaintenancePartReplacement> parts = partRepository
                .findByTicket_IdOrderByRoundNumberAscCreatedAtAsc(ticket.getId());
        List<MaintenanceReportVersion> reports = reportRepository
                .findByTicket_IdOrderByRevisionDesc(ticket.getId());
        List<MaintenanceAcceptanceDecision> decisions = decisionRepository
                .findByTicket_IdOrderByDecidedAtDesc(ticket.getId());
        List<MaintenanceEvidence> evidences = evidenceRepository
                .findByTicket_IdOrderByUploadedAtDesc(ticket.getId());
        List<MaintenanceWorkflowEvent> events = eventRepository
                .findByTicket_IdOrderByOccurredAtAsc(ticket.getId());

        MaintenanceWorkflowStage stage = transitionPolicy.effectiveStage(ticket);
        List<String> blockers = List.of();
        if (stage == MaintenanceWorkflowStage.IN_MAINTENANCE
                || stage == MaintenanceWorkflowStage.WAITING_PARTS
                || stage == MaintenanceWorkflowStage.REWORK_REQUIRED) {
            int completedWork = (int) workItems.stream()
                    .filter(w -> w.getStatus() == MaintenanceWorkStatus.COMPLETED)
                    .count();
            blockers = transitionPolicy.computeReturnSubmissionBlockers(
                    reports.isEmpty() ? null : reports.getFirst(),
                    completedWork);
        }

        return new MaintenanceWorkflowDetailResponse(
                ticket.getId(),
                ticket.getTicketCode(),
                ticket.getStatus(),
                stage,
                ticket.getVersion(),
                ticket.getCurrentRound() == null ? 1 : ticket.getCurrentRound(),
                Boolean.TRUE.equals(ticket.getLegacyRecord()),
                ticket.getIssueType(),
                ticket.getSeverity(),
                ticket.getDescription(),
                ticket.getResolutionNotes(),
                ticket.getWaitingReason(),
                ticket.getWaitingPartsNeeded(),
                ticket.getWaitingExpectedAt(),
                ticket.getAcceptedReportRevision(),
                ticket.getOpenedAt(),
                ticket.getResolvedAt(),
                ticket.getClosedAt(),
                ticket.getWorkflowUpdatedAt(),
                toDeviceSummary(ticket.getDevice()),
                TechnicalCapabilityResolver.toActorSummary(ticket.getHandedOverBy()),
                TechnicalCapabilityResolver.toActorSummary(ticket.getAssignedStaff()),
                TechnicalCapabilityResolver.toActorSummary(ticket.getReceivingManager()),
                transitionPolicy.computeAvailableActions(actor, ticket, isCustodian),
                blockers,
                handovers.stream().map(this::toHandoverResponse).toList(),
                workItems.stream().map(this::toWorkItemResponse).toList(),
                parts.stream().map(this::toPartResponse).toList(),
                reports.stream().map(this::toReportResponse).toList(),
                decisions.stream().map(this::toDecisionResponse).toList(),
                evidences.stream().map(this::toEvidenceResponse).toList(),
                events.stream().map(this::toEventResponse).toList());
    }

    private DeviceSummary toDeviceSummary(Device device) {
        if (device == null) {
            return null;
        }
        String modelName = device.getDeviceModel() != null ? device.getDeviceModel().getModelName() : null;
        String status = device.getStatus() != null ? device.getStatus().name() : null;
        return new DeviceSummary(
                device.getId(),
                device.getName(),
                device.getSerialNumber(),
                device.getDeviceCode(),
                modelName,
                status);
    }

    private HandoverResponse toHandoverResponse(MaintenanceHandover handover) {
        List<HandoverItemResponse> items = handover.getItems().stream()
                .map(i -> new HandoverItemResponse(
                        i.getId(),
                        i.getItemName(),
                        i.getExpectedQuantity(),
                        i.getReceivedQuantity(),
                        i.getConditionNote(),
                        i.getActualConditionNote(),
                        i.getDiscrepancyNote()))
                .toList();
        return new HandoverResponse(
                handover.getId(),
                handover.getDirection(),
                handover.getRoundNumber(),
                handover.getRevision(),
                handover.getStatus(),
                TechnicalCapabilityResolver.toActorSummary(handover.getFromUser()),
                TechnicalCapabilityResolver.toActorSummary(handover.getToUser()),
                handover.getMaintenanceReason(),
                handover.getOverallCondition(),
                handover.getExistingDamageNotes(),
                handover.getUnfinishedWorkNotes(),
                handover.getActualHandoverAt(),
                handover.getSubmittedAt(),
                handover.getConfirmedAt(),
                TechnicalCapabilityResolver.toActorSummary(handover.getConfirmedBy()),
                handover.getDiscrepancyNotes(),
                handover.getRejectionReason(),
                handover.getReportRevision(),
                parseStringList(handover.getEvidenceRefsJson()),
                parseStringList(handover.getReceiptEvidenceRefsJson()),
                handover.isLocked(),
                items);
    }

    private WorkItemResponse toWorkItemResponse(MaintenanceWorkItem item) {
        return new WorkItemResponse(
                item.getId(),
                item.getRoundNumber(),
                item.getComponentArea(),
                item.getSymptom(),
                item.getDiagnosis(),
                item.getActionType(),
                item.getWorkPerformed(),
                TechnicalCapabilityResolver.toActorSummary(item.getPerformedBy()),
                item.getStartedAt(),
                item.getEndedAt(),
                item.getStatus(),
                item.getResultSummary(),
                parseStringList(item.getEvidenceRefsJson()));
    }

    private PartReplacementResponse toPartResponse(MaintenancePartReplacement part) {
        return new PartReplacementResponse(
                part.getId(),
                part.getRoundNumber(),
                part.getPartName(),
                part.getPartNumber(),
                part.getOldSerial(),
                part.getNewSerial(),
                part.getQuantity(),
                part.getUnit(),
                part.getReplacementReason(),
                part.getRemovedPartCondition(),
                part.getCostAmount(),
                part.getCurrency(),
                parseStringList(part.getEvidenceRefsJson()),
                TechnicalCapabilityResolver.toActorSummary(part.getRecordedBy()));
    }

    private ReportVersionResponse toReportResponse(MaintenanceReportVersion report) {
        List<PostCheckResponse> checks = report.getPostChecks().stream()
                .map(c -> new PostCheckResponse(
                        c.getId(),
                        c.getCheckName(),
                        c.getCheckMethod(),
                        c.getResult(),
                        c.getNotes(),
                        c.getNotApplicableReason(),
                        c.isRequired(),
                        c.isEvidenceRequired(),
                        parseStringList(c.getEvidenceRefsJson())))
                .toList();
        return new ReportVersionResponse(
                report.getId(),
                report.getRoundNumber(),
                report.getRevision(),
                report.getStatus(),
                report.getReceivedConditionSummary(),
                report.getFaultDiagnosisSummary(),
                report.getWorkSummary(),
                report.getPartsSummary(),
                report.getPostCheckSummary(),
                report.getRemainingIssues(),
                report.getUsageLimitations(),
                report.getFollowUpRecommendations(),
                parseStringList(report.getBeforeEvidenceRefsJson()),
                parseStringList(report.getAfterEvidenceRefsJson()),
                TechnicalCapabilityResolver.toActorSummary(report.getAuthoredBy()),
                report.getSubmittedAt(),
                report.isLocked(),
                checks);
    }

    private AcceptanceDecisionResponse toDecisionResponse(MaintenanceAcceptanceDecision decision) {
        return new AcceptanceDecisionResponse(
                decision.getId(),
                decision.getReturnHandover().getId(),
                decision.getReportVersion().getId(),
                decision.getRoundNumber(),
                decision.getReportRevision(),
                decision.getDecision(),
                TechnicalCapabilityResolver.toActorSummary(decision.getDecidedBy()),
                decision.getDecidedAt(),
                decision.isDeviceMatchConfirmed(),
                decision.isAccessoriesMatchConfirmed(),
                decision.getVerificationNotes(),
                decision.getReworkReason(),
                parseStringList(decision.getFailedWorkItemIdsJson()),
                decision.isCheckpointCreated(),
                decision.isDeviceUnlocked(),
                parseStringList(decision.getRemainingBlockersJson()));
    }

    private EvidenceResponse toEvidenceResponse(MaintenanceEvidence evidence) {
        String downloadUrl = null;
        if (mediaObjectStorage != null
                && evidence.getStorageBucket() != null
                && !evidence.getStorageBucket().isBlank()) {
            try {
                downloadUrl = mediaObjectStorage.createPresignedDownloadUrl(
                        evidence.getStorageBucket(),
                        evidence.getStorageKey(),
                        evidence.getFileName());
            } catch (RuntimeException ex) {
                log.debug("Could not presign maintenance evidence {}", evidence.getId(), ex);
            }
        }
        return new EvidenceResponse(
                evidence.getId(),
                evidence.getStage(),
                evidence.getReferenceId(),
                evidence.getStorageKey(),
                evidence.getFileName(),
                evidence.getContentType(),
                evidence.getFileSizeBytes(),
                evidence.getCaption(),
                TechnicalCapabilityResolver.toActorSummary(evidence.getUploadedBy()),
                evidence.getUploadedAt(),
                downloadUrl);
    }

    private WorkflowEventResponse toEventResponse(MaintenanceWorkflowEvent event) {
        return new WorkflowEventResponse(
                event.getId(),
                event.getAction(),
                event.getFromStage(),
                event.getToStage(),
                event.getRoundNumber(),
                event.getRevision(),
                TechnicalCapabilityResolver.toActorSummary(event.getActor()),
                event.getReason(),
                parseStringList(event.getEvidenceRefsJson()),
                event.getOccurredAt());
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return null;
        }
    }

    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception ex) {
            return List.of();
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String blankToFallback(String value, String fallback) {
        String trimmed = trimToNull(value);
        return trimmed != null ? trimmed : fallback;
    }
}
