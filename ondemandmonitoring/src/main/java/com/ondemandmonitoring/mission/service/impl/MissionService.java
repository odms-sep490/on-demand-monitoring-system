package com.ondemandmonitoring.mission.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.api.PageResponse;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.delivery.service.IDeliveryWorkflowService;
import com.ondemandmonitoring.device.domain.Device;
import com.ondemandmonitoring.device.enums.DeviceStatus;
import com.ondemandmonitoring.device.repository.DeviceRepository;
import com.ondemandmonitoring.device.repository.DeviceTelemetryRepository;
import com.ondemandmonitoring.devicecheck.dto.response.PreDeviceCheckResponse;
import com.ondemandmonitoring.devicecheck.repository.PersistedPostDeviceCheckRepository;
import com.ondemandmonitoring.devicecheck.repository.PersistedPreDeviceCheckRepository;
import com.ondemandmonitoring.devicecheck.enums.DeviceCheckStatus;
import com.ondemandmonitoring.devicecheck.enums.PreDeviceItemStatus;
import com.ondemandmonitoring.devicecheck.service.IPreDeviceCheckCompletionService;
import com.ondemandmonitoring.devicecheck.service.IPersistedPostDeviceCheckService;
import com.ondemandmonitoring.mission.domain.ControlHandover;
import com.ondemandmonitoring.mission.domain.DeviceConnection;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.domain.MissionPlan;
import com.ondemandmonitoring.mission.dto.request.*;
import com.ondemandmonitoring.mission.dto.response.MissionPlanResponse;
import com.ondemandmonitoring.mission.dto.response.MissionResponse;
import com.ondemandmonitoring.mission.dto.response.MissionResourceReplacementResponse;
import com.ondemandmonitoring.order.enums.ExecutionPlanStatus;
import com.ondemandmonitoring.mission.dto.response.MissionTelemetryReadinessResponse;
import com.ondemandmonitoring.mission.enums.*;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.enums.OrderStatus;
import com.ondemandmonitoring.mission.repository.ControlHandoverRepository;
import com.ondemandmonitoring.mission.repository.DeviceConnectionRepository;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.mission.mapper.MissionMapper;
import com.ondemandmonitoring.mission.service.IDeviceConnectionService;
import com.ondemandmonitoring.mission.service.IMissionResultService;
import com.ondemandmonitoring.mission.service.IMissionChecklistExecutionService;
import com.ondemandmonitoring.mission.service.IMissionChecklistMaterializationService;
import com.ondemandmonitoring.mission.service.IMissionReworkContextSnapshotService;
import com.ondemandmonitoring.mission.service.IMissionService;
import com.ondemandmonitoring.flightpermit.service.IFlightPermitService;
import com.ondemandmonitoring.flightpermit.domain.FlightPermit;
import com.ondemandmonitoring.order.repository.OrderChecklistItemRepository;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.finance.enums.QuoteStatus;
import com.ondemandmonitoring.finance.repository.InvoiceRepository;
import com.ondemandmonitoring.finance.service.IMissionPaymentEligibilityService;
import com.ondemandmonitoring.finance.service.IFinalPaymentNotificationService;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.IStaffDirectoryService;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import com.ondemandmonitoring.userschedule.domain.UserSchedule;
import com.ondemandmonitoring.userschedule.enums.UserScheduleStatus;
import com.ondemandmonitoring.userschedule.enums.UserScheduleType;
import com.ondemandmonitoring.userschedule.repository.UserScheduleRepository;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.experimental.NonFinal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ThreadLocalRandom;

import com.ondemandmonitoring.device.domain.MaintenanceTicket;
import com.ondemandmonitoring.device.repository.MaintenanceTicketRepository;
import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.weather.domain.MissionWeatherCheck;
import com.ondemandmonitoring.weather.repository.MissionWeatherCheckRepository;

/**
 * Implementation of {@link IMissionService} for mission lifecycle management.
 * Enterprise pattern: Maps entities to DTOs within @Transactional scope to
 * guarantee safety against LazyInitializationException.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class MissionService implements IMissionService {

    // Each operational step has a distinct actor and no role fallback.
    static final Set<MissionStaffRole> REQUIRED_CREW_ROLES = Set.of(
            MissionStaffRole.PILOT, MissionStaffRole.OPERATOR,
            MissionStaffRole.MAINTAINER, MissionStaffRole.INSPECTOR);

    MissionRepository missionRepository;
    @Autowired @NonFinal
    MissionChecklistAssignmentRepository missionChecklistAssignmentRepository;
    DeviceRepository deviceRepository;
    MissionMapper missionMapper;

    // Supporting audit & work order repositories
    MissionPlanRepository missionPlanRepository;
    DeviceConnectionRepository deviceConnectionRepository;
    ControlHandoverRepository controlHandoverRepository;
    PersistedPostDeviceCheckRepository postDeviceCheckRepository;
    @Autowired @NonFinal
    PersistedPreDeviceCheckRepository preDeviceCheckRepository;
    @Autowired @NonFinal
    MissionWeatherCheckRepository missionWeatherCheckRepository;
    MaintenanceTicketRepository maintenanceTicketRepository;
    OrderRepository orderRepository;
    com.ondemandmonitoring.warehouse.repository.PreferredTimeRepository preferredTimeRepository;
    InvoiceRepository invoiceRepository;
    IMissionPaymentEligibilityService missionPaymentEligibilityService;
    IDeviceConnectionService deviceConnectionService;
    IMissionResultService missionResultService;
    IPreDeviceCheckCompletionService preDeviceCheckCompletionService;
    IPersistedPostDeviceCheckService persistedPostDeviceCheckService;
    IStaffDirectoryService staffDirectory;
    AuthenticatedUserResolver authenticatedUserResolver;
    UserScheduleRepository userScheduleRepository;
    MissionDeviceAssignmentRepository missionDeviceAssignmentRepository;
    MissionStaffAssignmentRepository missionStaffAssignmentRepository;
    ResourceTimeLockRepository resourceTimeLockRepository;
    IMissionChecklistExecutionService checklistExecutionService;

    IMissionChecklistMaterializationService checklistMaterializationService;
    @Autowired @NonFinal
    IMissionReworkContextSnapshotService reworkContextSnapshots;
    com.ondemandmonitoring.mission.service.IMissionChecklistAssignmentService checklistAssignmentService;
    com.ondemandmonitoring.mission.service.IResourceReservationService resources;
    DeviceTelemetryRepository telemetryRepository;
    IDeliveryWorkflowService deliveryWorkflowService;
    ChecklistAssignmentStatusSync checklistAssignmentStatusSync;
    OrderChecklistItemRepository orderChecklistItemRepository;
    IFlightPermitService flightPermitService;

    MissionChecklistExecutionRepository missionChecklistExecutionRepository;
    @Autowired @NonFinal
    IFinalPaymentNotificationService finalPaymentNotifications;
    @Autowired @NonFinal
    com.ondemandmonitoring.delivery.repository.ResultRevisionCaseRepository resultRevisionCases;
    @Autowired @NonFinal
    com.ondemandmonitoring.delivery.repository.ResultRevisionCaseItemRepository resultRevisionCaseItems;
    // =========================================================================
    // Query Methods
    // =========================================================================

    @Override
    @Transactional
    @PreAuthorize("@missionAuthorizationService.canManageMissions()")
    public MissionResponse rescheduleMission(String missionId, MissionScheduleUpdateRequest request) {
        Mission mission = missionRepository.findByIdWithOrder(missionId)
                .orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
        if (!Set.of(MissionStatus.PLANNING, MissionStatus.PENDING_PAYMENT,
                MissionStatus.PENDING_CONFIRMATION, MissionStatus.READY)
                .contains(mission.getStatus())) {
            throw new ApiException(ErrorCode.MISSION_STATUS_INVALID,
                    "Only a mission that has not entered preflight can be rescheduled");
        }
        if (!request.customerConfirmed()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Customer confirmation is required before rescheduling");
        }

        Order order = mission.getOrder();
        Instant newStart = request.scheduledStartAt();
        Instant newEnd = request.scheduledEndAt();
        ZoneId businessZone = ZoneId.of("Asia/Ho_Chi_Minh");
        LocalDate newDate = newStart.atZone(businessZone).toLocalDate();
        LocalDate endDate = newEnd.atZone(businessZone).toLocalDate();
        if (!newStart.isBefore(newEnd) || !newDate.equals(endDate)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "The new mission schedule must start and end on the same day");
        }

        if (mission.getPurpose() == MissionPurpose.REWORK
                && request.scope() == MissionRescheduleScope.ORDER_AND_MISSION) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Reschedule the rework Mission without changing the original Order preferences");
        }
        if (request.scope() == MissionRescheduleScope.ORDER_AND_MISSION) {
            if (request.orderPreferredDateFrom() == null || request.orderPreferredDateTo() == null
                    || request.orderPreferredTimeId() == null || request.orderPreferredTimeId().isBlank()) {
                throw new ApiException(ErrorCode.INVALID_REQUEST,
                        "New Order preferred dates and time slot are required");
            }
            if (request.orderPreferredDateTo().isBefore(request.orderPreferredDateFrom())) {
                throw new ApiException(ErrorCode.INVALID_REQUEST,
                        "Order preferred end date cannot be before start date");
            }
            if (newDate.isBefore(request.orderPreferredDateFrom())
                    || newDate.isAfter(request.orderPreferredDateTo())) {
                throw new ApiException(ErrorCode.INVALID_REQUEST,
                        "The new Mission date must be within the new Order preferred dates");
            }
            var preferredTime = preferredTimeRepository.findById(request.orderPreferredTimeId())
                    .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                            "Preferred time slot not found"));
            order.setPreferredDateFrom(request.orderPreferredDateFrom());
            order.setPreferredDateTo(request.orderPreferredDateTo());
            order.setPreferredTime(preferredTime);
            if (order.getResultDeadline() != null
                    && order.getResultDeadline().isBefore(request.orderPreferredDateTo())) {
                order.setResultDeadline(request.orderPreferredDateTo());
            }
        }

        validatePlanningSchedule(newStart, newEnd, order, mission.getPurpose());
        resources.revalidateSchedule(mission, newStart, newEnd);
        mission.setScheduledStartAt(newStart);
        mission.setScheduledEndAt(newEnd);
        mission.setLastRescheduleReason(request.reason());
        mission.setLastRescheduleNote(blankToNull(request.note()));
        mission.setLastRescheduledAt(Instant.now());
        mission.setScheduleRevision((mission.getScheduleRevision() == null ? 0L : mission.getScheduleRevision()) + 1L);

        if (request.scope() == MissionRescheduleScope.ORDER_AND_MISSION) {
            orderRepository.save(order);
        }
        return missionMapper.toResponse(missionRepository.save(mission));
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("@missionAuthorizationService.canManageMissions()")
    public PageResponse<MissionResponse> searchStaffMissions(
            MissionStatus status, Instant from, Instant toExclusive, Pageable pageable) {
        Specification<Mission> criteria = (root, query, builder) -> {
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
            if (status != null)
                predicates.add(builder.equal(root.get("status"), status));
            if (from != null)
                predicates.add(builder.greaterThanOrEqualTo(root.get("scheduledStartAt"), from));
            if (toExclusive != null)
                predicates.add(builder.lessThan(root.get("scheduledStartAt"), toExclusive));
            return builder.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
        return PageResponse.from(missionRepository.findAll(criteria, pageable).map(missionMapper::toResponse));
    }

    @Override
    @Transactional
    @PreAuthorize("@missionAuthorizationService.canViewMission(#missionId)")
    public MissionResponse getByIdResponse(String missionId) {
        Mission mission = getMissionByIdOrThrow(missionId);
        return missionMapper.toResponse(mission);
    }
        @Override
    @Transactional(readOnly = true)
    @PreAuthorize("@missionAuthorizationService.canManageMissions()")
    public List<MissionResponse> getAllMissions() {
        return missionRepository.findAll().stream()
                .map(missionMapper::toResponse)
                .toList();
    }
    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("@missionAuthorizationService.canViewMissionByCode(#missionCode)")
    public MissionResponse getByCodeResponse(String missionCode) {
        Mission mission = missionRepository.findByMissionCode(missionCode)
                .orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND,
                        "Mission not found: " + missionCode));
        return missionMapper.toResponse(mission);
    }

    @Override
    @Transactional
    @PreAuthorize("@missionAuthorizationService.canManageMissions()")
    public MissionResponse createMission(String orderId, MissionCreateRequest request) {
        return createMissionInternal(orderId, request, false);
    }

    @Override
    @Transactional
    @PreAuthorize("@missionAuthorizationService.canManageMissions()")
    public MissionResponse createAdditionalMission(String orderId, MissionCreateRequest request) {
        return createMissionInternal(orderId, request, true);
    }

    @Override
    @Transactional
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public MissionResponse createResultRevisionReworkMission(String caseId,
            CreateResultRevisionReworkMissionRequest request) {
        if (request == null || request.clientRequestId() == null || request.caseItemIds() == null
                || request.caseItemIds().isEmpty() || request.caseItemIds().size() > 100)
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        var caseSnapshot = resultRevisionCases.findById(caseId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        Order order = orderRepository.findByIdForUpdate(caseSnapshot.getOrder().getId())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        var revisionCase = resultRevisionCases.findByIdForUpdate(caseId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        String fingerprint = reworkFingerprint(caseId, request);
        var replay = missionRepository.findByReworkRequestId(request.clientRequestId());
        if (replay.isPresent()) {
            Mission prior = replay.get();
            if (!MissionPurpose.REWORK.equals(prior.getPurpose()) || !caseId.equals(prior.getResultRevisionCaseId())
                    || !fingerprint.equals(prior.getReworkRequestFingerprint()))
                throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            return missionMapper.toResponse(prior);
        }
        if (!revisionCase.isActive()
                || revisionCase.getStatus() != com.ondemandmonitoring.delivery.enums.ResultRevisionCaseStatus.OPEN
                || !java.util.Objects.equals(revisionCase.getVersion(), request.expectedCaseVersion()))
            throw new ApiException(ErrorCode.RESULT_REVIEW_REFRESH_REQUIRED);

        if (!order.getId().equals(revisionCase.getOrder().getId()))
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        if (order.getOrderStatus() != OrderStatus.COMPLETED
                || order.getExecutionPlan().getStatus() != ExecutionPlanStatus.FINALIZED)
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Rework is only available for a completed Order");
        var invoice = invoiceRepository.findByOrderId(order.getId())
                .orElseThrow(() -> new ApiException(ErrorCode.INVOICE_NOT_FOUND));
        if (!missionPaymentEligibilityService.isOriginalFinalPaymentConfirmed(invoice))
            throw new ApiException(ErrorCode.INVOICE_NOT_PAID,
                    "The original Order must be fully paid before a rework Mission can be created");

        var requestedIds = new java.util.LinkedHashSet<>(request.caseItemIds());
        if (requestedIds.size() != request.caseItemIds().size())
            throw new ApiException(ErrorCode.CHECKLIST_ASSIGNMENT_DUPLICATE);
        var caseItems = resultRevisionCaseItems.findByResultRevisionCaseIdAndIdInOrderByCreatedAtAsc(caseId, requestedIds);
        if (caseItems.size() != requestedIds.size()) throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        for (var item : caseItems) {
            if (item.getScopeDecision() != com.ondemandmonitoring.delivery.enums.ResultRevisionScope.IN_SCOPE
                    || item.getStrategy() != com.ondemandmonitoring.delivery.enums.ResultRevisionStrategy.REWORK
                    || item.getStatus() != com.ondemandmonitoring.delivery.enums.ResultRevisionCaseItemStatus.IN_PROGRESS)
                throw new ApiException(ErrorCode.INVALID_REQUEST,
                        "Every selected checklist item must be in scope and approved for rework");
            if (item.getAssignedReworkMissionId() != null) {
                Mission previous = missionRepository.findById(item.getAssignedReworkMissionId()).orElse(null);
                if (previous != null && !Set.of(MissionStatus.FAILED, MissionStatus.CANCELLED)
                        .contains(previous.getStatus()))
                    throw new ApiException(ErrorCode.INVALID_DELIVERY_STATE,
                            "The previous rework Mission must be reviewed before another attempt can be created");
            }
        }

        validatePlanningSchedule(request.scheduledStartAt(), request.scheduledEndAt(), order, MissionPurpose.REWORK);
        String scopeLabel = Normalizer.normalize(request.scopeLabel().strip().replaceAll("\\s+", " "), Normalizer.Form.NFC);
        Mission mission = newPlanningMission(order, request.scheduledStartAt(), request.scheduledEndAt(),
                scopeLabel, MissionPurpose.REWORK);
        mission.setResultRevisionCaseId(caseId);
        mission.setReworkRequestId(request.clientRequestId());
        mission.setReworkRequestFingerprint(fingerprint);
        Mission saved = missionRepository.saveAndFlush(mission);
        caseItems.forEach(item -> {
            item.setAssignedReworkMissionId(saved.getId());
            item.setStatus(com.ondemandmonitoring.delivery.enums.ResultRevisionCaseItemStatus.IN_PROGRESS);
        });
        resultRevisionCaseItems.saveAllAndFlush(caseItems);
        assignChecklist(saved, order, caseItems.stream()
                .map(item -> item.getOrderChecklistItem().getId()).toList());
        reworkContextSnapshots.capture(saved, caseItems);
        return missionMapper.toResponse(saved);
    }

    private String reworkFingerprint(String caseId, CreateResultRevisionReworkMissionRequest request) {
        try {
            String canonical = caseId + "\\n" + request.expectedCaseVersion() + "\\n" + request.scopeLabel().strip()
                    + "\\n" + request.scheduledStartAt() + "\\n" + request.scheduledEndAt() + "\\n"
                    + request.caseItemIds().stream().sorted().collect(java.util.stream.Collectors.joining("\\n"));
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to fingerprint rework Mission creation", exception);
        }
    }
    
    private MissionResponse createMissionInternal(String orderId, MissionCreateRequest request, boolean additional) {
        String scopeLabel = Normalizer.normalize(
                request.getScopeLabel().strip().replaceAll("\\s+", " "), Normalizer.Form.NFC);
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found: " + orderId));
        if (additional) {
            if (order.getExecutionPlan().getStatus() != ExecutionPlanStatus.FINALIZED)
                throw new ApiException(ErrorCode.EXECUTION_PLAN_NOT_FINALIZED);
            if (order.getOrderStatus() != OrderStatus.APPROVED && order.getOrderStatus() != OrderStatus.IN_PROGRESS)
                throw new ApiException(ErrorCode.INVALID_REQUEST, "Order must be APPROVED or IN_PROGRESS");
        } else if (order.getExecutionPlan().getStatus() != ExecutionPlanStatus.DRAFT) {
            throw new ApiException(ErrorCode.EXECUTION_PLAN_NOT_DRAFT);
        }
        validatePlanningSchedule(request.getScheduledStartAt(), request.getScheduledEndAt(), order);
        
        var invoice = invoiceRepository.findByOrderId(order.getId())
                .filter(value -> value.getQuote().getStatus() == QuoteStatus.ACCEPTED_BY_CUSTOMER)
                .orElseThrow(() -> new ApiException(ErrorCode.INVOICE_NOT_PAYABLE,
                        "An accepted quote and invoice are required before mission creation"));
        if (!missionPaymentEligibilityService.isDepositSatisfied(invoice)) {
            throw new ApiException(ErrorCode.INVOICE_NOT_PAID,
                    "The required deposit must be paid before mission planning can begin");
        }
        Mission mission = newPlanningMission(order, request.getScheduledStartAt(), request.getScheduledEndAt(),
                scopeLabel, MissionPurpose.ORIGINAL);
        Mission saved = missionRepository.saveAndFlush(mission);
        assignChecklist(saved, order, request.getChecklistIds());
        // Planning creation never materializes executions; the canonical plan finalizer owns that boundary.
        return missionMapper.toResponse(saved);
    }

    /** Original and rework Missions share the same planning lifecycle and permit initialization. */
    private Mission newPlanningMission(Order order, Instant scheduledStart, Instant scheduledEnd,
            String scopeLabel, MissionPurpose purpose) {
        Mission mission = new Mission();
        mission.setMissionCode(generateMissionCode());
        mission.setPurpose(purpose);
        mission.setStatus(MissionStatus.PLANNING);
        mission.setOrder(order);
        FlightPermit.pendingFor(mission);
        mission.setScheduledStartAt(scheduledStart);
        mission.setScheduledEndAt(scheduledEnd);
        mission.setScopeLabel(scopeLabel);
        return mission;
    }

    @Override
    @Transactional
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public MissionResponse confirmMissionPlanning(String missionId) {
        var order = getMissionOrder(missionId);
        var mission = getMissionForOrder(missionId, order);
        if (order.getExecutionPlan().getStatus() != ExecutionPlanStatus.FINALIZED)
            throw new ApiException(ErrorCode.EXECUTION_PLAN_NOT_FINALIZED);
        if (mission.getPurpose() == MissionPurpose.REWORK)
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Finalize the rework plan before proceeding to flight permits");
        requireOpenOrder(order);
        if (mission.getStatus() == MissionStatus.PENDING_PAYMENT) {
            return missionMapper.toResponse(mission);
        }
        requireRevisionAvailable(order);
        requirePlanningReady(mission);
        var selected = missionChecklistAssignmentRepository.findByMissionIdAndRemovedAtIsNullOrderByDisplayOrderAscIdAsc(missionId);
        if (selected.isEmpty()) throw new ApiException(ErrorCode.CHECKLIST_COVERAGE_INCOMPLETE);
        // Check identity and exclusive checklist ownership across the entire Order.
        var calculated = new ExecutionPlanCoverageService().evaluate(orderChecklistItemRepository.findAllByOrderIdInOrderByDisplayOrderAscIdAsc(List.of(order.getId())),
                missionRepository.findAllByOrderIdOrderByScheduledStartAtAscIdAsc(order.getId()), missionChecklistAssignmentRepository.findActiveByOrderId(order.getId()));
        if (!calculated.coverageReady() || calculated.repeated() > 0)
            throw new ApiException(ErrorCode.CHECKLIST_COVERAGE_INCOMPLETE);
        checklistMaterializationService.materialize(mission);
        mission.setStatus(MissionStatus.PENDING_PAYMENT);
        activateAssignedResources(missionId);
        order.getExecutionPlan().setRevision(order.getExecutionPlan().getRevision() + 1);
        orderRepository.saveAndFlush(order);
        return missionMapper.toResponse(missionRepository.saveAndFlush(mission));
    }

    @Override
    @Transactional
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public void finalizeReworkPlan(String caseId,
            com.ondemandmonitoring.mission.dto.request.ExecutionPlanFinalizeRequest request) {
        if (request == null || request.expectedPlanRevision() == null || request.expectedPlanRevision() < 0)
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        var snapshot = resultRevisionCases.findById(caseId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        var order = orderRepository.findByIdForUpdate(snapshot.getOrder().getId())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        var revisionCase = resultRevisionCases.findByIdForUpdate(caseId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!revisionCase.isActive()
                || revisionCase.getStatus() != com.ondemandmonitoring.delivery.enums.ResultRevisionCaseStatus.OPEN
                || order.getOrderStatus() != OrderStatus.COMPLETED
                || order.getExecutionPlan().getStatus() != ExecutionPlanStatus.FINALIZED)
            throw new ApiException(ErrorCode.INVALID_DELIVERY_STATE);
        if (order.getExecutionPlan().getRevision() != request.expectedPlanRevision())
            throw new ApiException(ErrorCode.EXECUTION_PLAN_REVISION_CONFLICT);
        var related = missionRepository.findAllByOrderIdForUpdateOrderById(order.getId()).stream()
                .filter(m -> m.getPurpose() == MissionPurpose.REWORK && caseId.equals(m.getResultRevisionCaseId())
                        && m.getStatus() != MissionStatus.FAILED && m.getStatus() != MissionStatus.CANCELLED)
                .toList();
        var caseItems = resultRevisionCaseItems.findByResultRevisionCaseIdOrderByCreatedAtAsc(caseId).stream()
                .filter(item -> item.getScopeDecision() == com.ondemandmonitoring.delivery.enums.ResultRevisionScope.IN_SCOPE
                        && item.getStrategy() == com.ondemandmonitoring.delivery.enums.ResultRevisionStrategy.REWORK)
                .toList();
        if (caseItems.isEmpty() || caseItems.stream().anyMatch(item -> item.getAssignedReworkMissionId() == null
                || related.stream().noneMatch(m -> m.getId().equals(item.getAssignedReworkMissionId()))))
            throw new ApiException(ErrorCode.CHECKLIST_COVERAGE_INCOMPLETE);
        var drafts = related.stream().filter(m -> m.getStatus() == MissionStatus.PLANNING).toList();
        if (drafts.isEmpty()) return;
        // All confirmations share this transaction: one invalid Mission rolls back the entire plan.
        for (var mission : drafts) finalizeReworkMission(order, mission);
    }

    private MissionResponse finalizeReworkMission(Order order, Mission mission) {
        if (order.getOrderStatus() != OrderStatus.COMPLETED || mission.getResultRevisionCaseId() == null)
            throw new ApiException(ErrorCode.INVALID_DELIVERY_STATE);
        if (mission.getStatus() == MissionStatus.PENDING_CONFIRMATION
                || mission.getStatus() == MissionStatus.READY) return missionMapper.toResponse(mission);
        if (mission.getStatus() != MissionStatus.PLANNING || mission.getCancellationAt() != null)
            throw new ApiException(ErrorCode.MISSION_STATUS_INVALID);
        var assigned = missionChecklistAssignmentRepository
                .findByMissionIdAndRemovedAtIsNullOrderByDisplayOrderAscIdAsc(mission.getId());
        if (assigned.isEmpty()) throw new ApiException(ErrorCode.CHECKLIST_COVERAGE_INCOMPLETE);
        var linkedCaseItems = resultRevisionCaseItems
                .findByResultRevisionCaseIdAndOrderChecklistItemIdInOrderByCreatedAtAsc(
                        mission.getResultRevisionCaseId(), assigned.stream()
                                .map(com.ondemandmonitoring.mission.domain.MissionChecklistAssignment::getOrderChecklistItemId).toList());
        if (linkedCaseItems.size() != assigned.size() || linkedCaseItems.stream().anyMatch(item ->
                item.getScopeDecision() != com.ondemandmonitoring.delivery.enums.ResultRevisionScope.IN_SCOPE
                || item.getStrategy() != com.ondemandmonitoring.delivery.enums.ResultRevisionStrategy.REWORK
                || !mission.getId().equals(item.getAssignedReworkMissionId())))
            throw new ApiException(ErrorCode.CHECKLIST_EXECUTION_INTEGRITY_INVALID);
        requirePlanningReady(mission);
        var invoice = invoiceRepository.findByOrderId(order.getId())
                .orElseThrow(() -> new ApiException(ErrorCode.INVOICE_NOT_FOUND));
        if (!missionPaymentEligibilityService.isOriginalFinalPaymentConfirmed(invoice))
            throw new ApiException(ErrorCode.INVOICE_NOT_PAID);
        if (!missionPaymentEligibilityService.isResourceAssignmentReady(mission))
            throw new ApiException(ErrorCode.FINAL_PAYMENT_NOT_ALLOWED,
                    "Rework crew, equipment, schedule or resource reservations are no longer valid");

        checklistMaterializationService.materialize(mission);
        mission.setStatus(MissionStatus.PENDING_CONFIRMATION);
        activateAssignedResources(mission.getId());
        order.getExecutionPlan().setRevision(Math.addExact(order.getExecutionPlan().getRevision(), 1));
        orderRepository.saveAndFlush(order);
        return missionMapper.toResponse(missionRepository.saveAndFlush(mission));
    }

    private void activateAssignedResources(String missionId) {
        missionStaffAssignmentRepository.findByMissionId(missionId).forEach(assignment -> {
            if (assignment.getReleasedAt() == null) {
                if (assignment.getResponseStatus() == MissionStaffAssignmentStatus.PLANNING)
                    assignment.setResponseStatus(MissionStaffAssignmentStatus.PENDING);
                assignment.setIsCurrent(true);
            }
        });
        missionDeviceAssignmentRepository.findByMissionId(missionId).forEach(assignment -> {
            if (assignment.getReleasedAt() == null) {
                if (assignment.getCheckupStatus() == MissionDeviceCheckStatus.PLANNING)
                    assignment.setCheckupStatus(MissionDeviceCheckStatus.PENDING);
                assignment.setIsCurrent(true);
            }
        });
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<MissionResponse> transitionToPendingConfirmation(String orderId) {
        orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found: " + orderId));
        List<Mission> missions = missionRepository.findAllByOrderIdForUpdateOrderById(orderId);
        if (missions.isEmpty()) {
            throw new ApiException(ErrorCode.MISSION_NOT_FOUND, "Order has no Mission: " + orderId);
        }
        var invoice = invoiceRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.INVOICE_NOT_FOUND));
        if (!missionPaymentEligibilityService.isFinalPaymentConfirmed(invoice)) {
            throw new ApiException(ErrorCode.INVOICE_NOT_PAID,
                    "Final payment must be fully settled before Mission activation");
        }
        Set<MissionStatus> alreadyActivatedOrTerminal = Set.of(
                MissionStatus.PENDING_CONFIRMATION, MissionStatus.READY, MissionStatus.IN_PROGRESS,
                MissionStatus.AWAITING_REVIEW, MissionStatus.PENDING_REVIEW,
                MissionStatus.COMPLETED, MissionStatus.FAILED, MissionStatus.CANCELLED);
        // Rework missions are created only after the original Order has been fully paid.
        // A retried original-payment callback must never try to activate them through the
        // original PENDING_PAYMENT transition.
        List<Mission> originalMissions = missions.stream()
                .filter(mission -> mission.getPurpose() != MissionPurpose.REWORK)
                .toList();
        originalMissions.stream()
                .filter(mission -> mission.getStatus() != MissionStatus.PENDING_PAYMENT)
                .filter(mission -> !alreadyActivatedOrTerminal.contains(mission.getStatus()))
                .findFirst()
                .ifPresent(mission -> requireStatus(mission, MissionStatus.PENDING_PAYMENT));
        originalMissions.stream()
                .filter(mission -> mission.getStatus() == MissionStatus.PENDING_PAYMENT)
                .forEach(mission -> mission.setStatus(MissionStatus.PENDING_CONFIRMATION));
        return missionRepository.saveAllAndFlush(originalMissions).stream()
                .map(missionMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional
    @PreAuthorize("@missionAuthorizationService.canManageMissions()")
    public MissionResponse cancelMission(String missionId, String reason) {
        Mission mission = missionRepository.findById(missionId)
                .orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
        requireStatus(mission, MissionStatus.READY);

        Instant cancelledAt = Instant.now();
        String releaseReason = "MISSION_CANCELLED";

        resources.release(missionId, releaseReason);

        Device device = getCurrentDevice(missionId);
        if (device != null && device.getStatus() == DeviceStatus.RESERVED) {
            device.setStatus(DeviceStatus.AVAILABLE);
            deviceRepository.save(device);
        }

        List<MissionDeviceAssignment> deviceAssignments = missionDeviceAssignmentRepository.findByMissionId(missionId);
        for (MissionDeviceAssignment assignment : deviceAssignments) {
            if (!Boolean.TRUE.equals(assignment.getIsCurrent()) || assignment.getReleasedAt() != null) {
                continue;
            }
            assignment.setReleasedAt(cancelledAt);
            assignment.setReleaseReason(releaseReason);
        }
        if (!deviceAssignments.isEmpty()) {
            missionDeviceAssignmentRepository.saveAll(deviceAssignments);
        }

        List<MissionStaffAssignment> staffAssignments = missionStaffAssignmentRepository.findByMissionId(missionId);
        for (MissionStaffAssignment assignment : staffAssignments) {
            if (!Boolean.TRUE.equals(assignment.getIsCurrent()) || assignment.getReleasedAt() != null) {
                continue;
            }
            if (assignment.getRespondedAt() == null) {
                assignment.setRespondedAt(cancelledAt);
            }
            assignment.setReleasedAt(cancelledAt);
            assignment.setReleaseReason(releaseReason);
        }
        if (!staffAssignments.isEmpty()) {
            missionStaffAssignmentRepository.saveAll(staffAssignments);
        }

        mission.setStatus(MissionStatus.CANCELLED);
        mission.setCancellationAt(cancelledAt);
        mission.setCancelledBy(authenticatedUserResolver.getCurrentUserId());
        mission.setCancellationReason(reason == null || reason.isBlank() ? null : reason.strip());

        if (checklistAssignmentStatusSync != null) {
            checklistAssignmentStatusSync.syncForMission(mission);
        }

        return missionMapper.toResponse(missionRepository.saveAndFlush(mission));
    }

    @Override
    @Transactional
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public void cancelDraftMission(String missionId) {
        var orderId = missionRepository.findOrderIdByMissionId(missionId)
                .orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
        var order = orderRepository.findByIdForUpdate(orderId).or(() -> orderRepository.findById(orderId))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        var mission = missionRepository.findByIdForUpdate(missionId).or(() -> missionRepository.findById(missionId))
                .orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
        if (mission.getStatus() == MissionStatus.CANCELLED) return;
        if (order.getExecutionPlan().getStatus() != ExecutionPlanStatus.DRAFT)
            throw new ApiException(ErrorCode.EXECUTION_PLAN_NOT_DRAFT);
        if (mission.getStatus() != MissionStatus.PLANNING || mission.getActualStartAt() != null
                || !missionChecklistExecutionRepository.findOrderedByMissionId(missionId).isEmpty())
            throw new ApiException(ErrorCode.MISSION_STATUS_INVALID, "Only unconfirmed planning Missions can be cancelled");
        requireRevisionAvailable(order);
        Instant cancelledAt = Instant.now();
        String actorId = authenticatedUserResolver.getCurrentUserId();
        String cancellationReason = "Draft mission cancelled";

        resources.release(missionId, "MISSION_CANCELLED");
        var deviceAssignments = missionDeviceAssignmentRepository.findByMissionId(missionId).stream()
                .filter(assignment -> Boolean.TRUE.equals(assignment.getIsCurrent())
                        && assignment.getReleasedAt() == null)
                .toList();
        for (MissionDeviceAssignment assignment : deviceAssignments) {
            assignment.setIsCurrent(false);
            assignment.setStatus(MissionDeviceAssignmentStatus.RELEASED);
            assignment.setReleasedAt(cancelledAt);
            assignment.setReleaseReason("MISSION_CANCELLED");
            if (assignment.getDevice().getStatus() == DeviceStatus.RESERVED) {
                assignment.getDevice().setStatus(DeviceStatus.AVAILABLE);
                deviceRepository.save(assignment.getDevice());
            }
        }
        missionDeviceAssignmentRepository.saveAll(deviceAssignments);

        var staffAssignments = missionStaffAssignmentRepository.findByMissionId(missionId).stream()
                .filter(assignment -> Boolean.TRUE.equals(assignment.getIsCurrent())
                        && assignment.getReleasedAt() == null)
                .toList();
        for (MissionStaffAssignment assignment : staffAssignments) {
            if (assignment.getRespondedAt() == null) assignment.setRespondedAt(cancelledAt);
            assignment.setIsCurrent(false);
            assignment.setReleasedAt(cancelledAt);
            assignment.setReleaseReason("MISSION_CANCELLED");
        }
        missionStaffAssignmentRepository.saveAll(staffAssignments);

        var missionSchedules = userScheduleRepository.findByReferenceId(missionId).stream()
                .filter(schedule -> schedule.getScheduleType() == UserScheduleType.MISSION
                        && schedule.getStatus() != UserScheduleStatus.COMPLETED)
                .toList();
        missionSchedules.forEach(schedule -> schedule.setStatus(UserScheduleStatus.CANCELLED));
        userScheduleRepository.saveAll(missionSchedules);

        var otherOwners = new java.util.HashSet<String>();
        missionChecklistAssignmentRepository.findActiveByOrderId(order.getId()).stream()
                .filter(a -> !missionId.equals(a.getMissionId()) && ExecutionPlanCoverageService.effective(a.getMission()))
                .forEach(a -> otherOwners.add(a.getOrderChecklistItemId()));
        var checklistAssignments = missionChecklistAssignmentRepository
                .findByMissionIdAndRemovedAtIsNullOrderByDisplayOrderAscIdAsc(missionId);
        checklistAssignments.stream()
                .filter(assignment -> !otherOwners.contains(assignment.getOrderChecklistItemId()))
                .forEach(assignment -> assignment.getOrderChecklistItem().setAssignmentStatus(
                        com.ondemandmonitoring.order.enums.ChecklistAssignmentStatus.UNASSIGNED));
        orderChecklistItemRepository.saveAll(checklistAssignments.stream()
                .filter(assignment -> !otherOwners.contains(assignment.getOrderChecklistItemId()))
                .map(MissionChecklistAssignment::getOrderChecklistItem)
                .toList());
        checklistAssignments.forEach(assignment -> {
            assignment.setRemovedAt(cancelledAt);
            assignment.setRemovedBy(actorId);
            assignment.setRemovalReason(cancellationReason);
        });
        missionChecklistAssignmentRepository.saveAll(checklistAssignments);

        mission.setStatus(MissionStatus.CANCELLED);
        mission.setCancellationAt(cancelledAt);
        mission.setCancelledBy(actorId);
        mission.setCancellationReason(cancellationReason);
        if (checklistAssignmentStatusSync != null) checklistAssignmentStatusSync.syncForMission(mission);
        order.getExecutionPlan().setRevision(order.getExecutionPlan().getRevision() + 1);
        missionRepository.saveAndFlush(mission);
        orderRepository.saveAndFlush(order);
    }

    private Order getMissionOrder(String missionId) {
        var id = missionRepository.findOrderIdByMissionId(missionId).orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
        return orderRepository.findById(id).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private Mission getMissionForOrder(String missionId, Order order) {
        var mission = missionRepository.findById(missionId).orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
        if (!order.getId().equals(mission.getOrder().getId())) throw new ApiException(ErrorCode.CHECKLIST_ASSIGNMENT_CROSS_ORDER);
        return mission;
    }

    private void requireOpenOrder(Order order) {
        if (order.getOrderStatus() != OrderStatus.APPROVED && order.getOrderStatus() != OrderStatus.IN_PROGRESS)
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Order must be APPROVED or IN_PROGRESS");
    }

    private void requireRevisionAvailable(Order order) {
        if (order.getExecutionPlan().getRevision() == Long.MAX_VALUE) throw new ApiException(ErrorCode.EXECUTION_PLAN_REVISION_CONFLICT);
    }

    private void requirePlanningReady(Mission mission) {
        requirePlanningReady(mission, missionStaffAssignmentRepository.findByMissionId(mission.getId()),
                missionDeviceAssignmentRepository.findByMissionId(mission.getId()));
    }

    static void requirePlanningReady(Mission mission, List<MissionStaffAssignment> crew, List<MissionDeviceAssignment> equipment) {
        if (mission.getStatus() != MissionStatus.PLANNING || mission.getCancellationAt() != null)
            throw new ApiException(ErrorCode.MISSION_STATUS_INVALID, "Mission must be PLANNING");
        var roles = java.util.EnumSet.noneOf(MissionStaffRole.class);
        crew.stream()
                .filter(a -> a.getReleasedAt() == null && a.getStaff() != null
                        && a.getResponseStatus() != MissionStaffAssignmentStatus.REJECTED)
                .map(a -> a.getAssignedRole()).filter(java.util.Objects::nonNull).forEach(roles::add);
        if (!roles.containsAll(java.util.EnumSet.of(MissionStaffRole.PILOT, MissionStaffRole.OPERATOR,
                MissionStaffRole.MAINTAINER, MissionStaffRole.INSPECTOR)))
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Mission " + mission.getId() + " requires PILOT, OPERATOR, MAINTAINER and INSPECTOR");
        boolean main = equipment.stream()
                .anyMatch(a -> a.getReleasedAt() == null && a.getDevice() != null && a.getDeviceRole() == DeviceRole.MAIN);
        if (!main) throw new ApiException(ErrorCode.INVALID_REQUEST,
                "Mission " + mission.getId() + " requires at least one current MAIN device");
    }

    /** A Mission must own at least one checklist item; assignment reuses the canonical replace flow. */
    private void assignChecklist(Mission mission, Order order, List<String> checklistIds) {
        if (checklistIds == null || checklistIds.isEmpty())
            throw new ApiException(ErrorCode.INVALID_REQUEST, "A Mission must have at least one checklist item");
        var items = checklistIds.stream()
                .map(id -> new com.ondemandmonitoring.mission.dto.request.ChecklistAssignmentSelectionRequest.Item(id))
                .toList();
        checklistAssignmentService.replace(mission.getId(),
                new com.ondemandmonitoring.mission.dto.request.ChecklistAssignmentSelectionRequest(
                        order.getExecutionPlan().getRevision(), items));
    }

    private void validatePlanningSchedule(Instant start, Instant end, Order order) {
        validatePlanningSchedule(start, end, order, MissionPurpose.ORIGINAL);
    }

    private void validatePlanningSchedule(Instant start, Instant end, Order order, MissionPurpose purpose) {
        if (start == null || end == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Scheduled start time and scheduled end time must be specified");
        }
        if (!start.isBefore(end)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Scheduled start time must be before scheduled end time");
        }

        ZoneId businessZone = ZoneId.of("Asia/Ho_Chi_Minh");
        LocalDate startDate = start.atZone(businessZone).toLocalDate();
        LocalDate endDate = end.atZone(businessZone).toLocalDate();
        if (!startDate.equals(endDate)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Scheduled start time and scheduled end time must be on the same day");
        }

        // Rework is arranged after the original results were reviewed. Its new
        // schedule must not reuse the original Order's expired date/time window.
        if (purpose == MissionPurpose.REWORK) {
            if (!start.isAfter(Instant.now())) {
                throw new ApiException(ErrorCode.INVALID_REQUEST,
                        "The rework Mission must be scheduled in the future");
            }
            return;
        }
        if (order != null) {
            if (order.getPreferredDateFrom() != null && startDate.isBefore(order.getPreferredDateFrom())) {
                throw new ApiException(ErrorCode.INVALID_REQUEST,
                        "Scheduled date must be within customer preferred dates: from " + order.getPreferredDateFrom());
            }
            if (order.getPreferredDateTo() != null && startDate.isAfter(order.getPreferredDateTo())) {
                throw new ApiException(ErrorCode.INVALID_REQUEST,
                        "Scheduled date must be within customer preferred dates: to " + order.getPreferredDateTo());
            }

            if (order.getPreferredTime() != null) {
                LocalTime prefStartTime = order.getPreferredTime().getStartTime();
                LocalTime prefEndTime = order.getPreferredTime().getEndTime();
                if (prefStartTime != null && prefEndTime != null) {
                    Instant customerSlotStart = startDate.atTime(prefStartTime).atZone(businessZone).toInstant();
                    Instant customerSlotEnd = startDate.atTime(prefEndTime).atZone(businessZone).toInstant();

                    if (start.isBefore(customerSlotStart) || end.isAfter(customerSlotEnd)) {
                        throw new ApiException(ErrorCode.INVALID_REQUEST,
                                "Mission scheduled time must be within customer preferred time slot: "
                                        + prefStartTime + " - " + prefEndTime);
                    }
                }
            }
        }
    }


    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("@missionAuthorizationService.canViewStaffMissions(#staffId)")
    public List<MissionResponse> getByStaffId(String staffId) {
        List<MissionStatus> activeStatuses = List.of(MissionStatus.values());
        return missionRepository.findByStaffIdAndStatusIn(staffId, activeStatuses)
                .stream()
                .sorted(java.util.Comparator.comparing(
                        Mission::getScheduledStartAt,
                        java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                .map(missionMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('STAFF')")
    public List<MissionResponse> getCurrentStaffMissions() {
        return getByStaffId(currentStaffId());
    }


    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("@missionAuthorizationService.canManageMissions()")
    public List<MissionResponse> getPendingAssignmentMissions() {
        return missionRepository.findByStatusIn(List.of(MissionStatus.PLANNING))
                .stream()
                .sorted(java.util.Comparator.comparing(
                        Mission::getCreatedAt,
                        java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                .map(missionMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("@missionAuthorizationService.canViewMission(#missionId)")
    public MissionPlanResponse getMissionPlan(String missionId) {
        Mission mission = getMissionByIdOrThrow(missionId);
        MissionPlan plan = missionPlanRepository.findByMissionId(mission.getId())
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "Mission plan not found for mission: " + missionId));
        return missionMapper.toPlanResponse(plan);
    }

    @Override
    @Transactional(readOnly = true)
    public Mission findById(String missionId) {
        return getMissionByIdOrThrow(missionId);
    }

    // =========================================================================
    // F2 – Manager Assignment (Flow 2)
    // =========================================================================

    @Override
    @Transactional
    @PreAuthorize("@missionAuthorizationService.canRespondToMission(#missionId)")
    public MissionResponse acceptCurrentStaffMission(String missionId) {
        return acceptMission(missionId, currentStaffId());
    }

    @Override
    @Transactional
    @PreAuthorize("@missionAuthorizationService.canRespondToMission(#missionId)")
    public MissionResponse rejectCurrentStaffMission(String missionId, String reason) {
        return rejectMission(missionId, currentStaffId(), reason);
    }

    @Override
    @Transactional
    @PreAuthorize("@missionAuthorizationService.canOperatePayload(#missionId) or @missionAuthorizationService.canControlFlight(#missionId)")
    public MissionResponse handoverCurrentStaffControl(String missionId) {
        return handoverControl(missionId, currentStaffId());
    }



    @Override
    @Transactional
    @PreAuthorize("@missionAuthorizationService.canManageMissions()")
    public MissionResponse assignDevice(String missionId, AssignDeviceRequest request) {
        Mission mission = getPlanningResourceMission(missionId);
        String resolvedMissionId = mission.getId();
        String deviceId = request.getDeviceId();
        DeviceRole deviceRole = request.getDeviceRole();
        boolean planFinalized = MissionRuntimeGate.isStructurallyFinalized(mission);
        DeviceRole role = (deviceRole != null) ? deviceRole : DeviceRole.MAIN;
        boolean replacingFailedMainDevice = planFinalized
                && role == DeviceRole.MAIN
                && missionDeviceAssignmentRepository
                        .findAllByMissionIdAndDeviceRoleAndIsCurrentTrueOrderByCreatedAtDesc(
                                resolvedMissionId, DeviceRole.MAIN)
                        .stream()
                        .anyMatch(assignment -> assignment.getReleasedAt() == null
                                && assignment.getStatus() == MissionDeviceAssignmentStatus.FAILED);
        if (mission.getStatus() != MissionStatus.PLANNING && mission.getStatus() != MissionStatus.READY
                && !replacingFailedMainDevice) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "A device can only be assigned during planning or to replace a failed preflight device.");
        }

        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(
                        () -> new ApiException(ErrorCode.DEVICE_NOT_FOUND, "Device not found with id: " + deviceId));

        if (device.getStatus() == DeviceStatus.MAINTENANCE) {
            throw new ApiException(ErrorCode.DEVICE_NOT_AVAILABLE,
                    "Device is under MAINTENANCE and cannot be assigned to a mission.");
        }

        Instant missionStart = mission.getScheduledStartAt();
        Instant missionEnd = mission.getScheduledEndAt();

        if (missionStart == null || missionEnd == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Mission schedule start and end times must be set before assigning devices.");
        }

        resources.reserve(mission, deviceId, false);

        Instant now = Instant.now();
        var currentSameDevice = missionDeviceAssignmentRepository
                .findAllByMissionIdAndDeviceIdAndIsCurrentTrueOrderByCreatedAtDesc(resolvedMissionId, deviceId);
        boolean alreadyAssigned = !planFinalized
                ? missionDeviceAssignmentRepository.findByMissionId(resolvedMissionId).stream()
                    .anyMatch(a -> a.getReleasedAt() == null && a.getDevice().getId().equals(deviceId))
                : !currentSameDevice.isEmpty();
        if (alreadyAssigned) {
            if (replacingFailedMainDevice) {
                currentSameDevice.stream()
                        .filter(assignment -> assignment.getDeviceRole() == DeviceRole.MAIN
                                && assignment.getStatus() == MissionDeviceAssignmentStatus.FAILED)
                        .forEach(assignment -> {
                            assignment.setStatus(MissionDeviceAssignmentStatus.ASSIGNED);
                            assignment.setCheckupStatus(MissionDeviceCheckStatus.PENDING);
                            assignment.setFailureNotes(null);
                        });
                missionDeviceAssignmentRepository.saveAll(currentSameDevice);
                refreshResourceReadiness(mission);
            }
        }
        if (alreadyAssigned) {
            return missionMapper.toResponse(missionRepository.save(mission));
        }

        if (role == DeviceRole.MAIN) {
            List<MissionDeviceAssignment> currentMainAssignments = !planFinalized
                    ? missionDeviceAssignmentRepository.findByMissionId(resolvedMissionId).stream()
                        .filter(a -> a.getReleasedAt() == null && a.getDeviceRole() == DeviceRole.MAIN).toList()
                    : missionDeviceAssignmentRepository.findAllByMissionIdAndDeviceRoleAndIsCurrentTrueOrderByCreatedAtDesc(
                            resolvedMissionId, DeviceRole.MAIN);
            for (MissionDeviceAssignment assignment : currentMainAssignments) {
                if (planFinalized) {
                    assignment.setIsCurrent(false);
                    assignment.setReleasedAt(now);
                    assignment.setReleaseReason("Replaced by device assignment " + deviceId);
                }
                resources.releaseResource(resolvedMissionId, assignment.getDevice().getId(), "DEVICE_REPLACED");
            }
            if (!currentMainAssignments.isEmpty() && !planFinalized) {
                missionDeviceAssignmentRepository.deleteAll(currentMainAssignments);
                missionDeviceAssignmentRepository.flush();
            }
        }

        MissionDeviceAssignment newAssignment = MissionDeviceAssignment.builder()
                .mission(mission)
                .device(device)
                .deviceRole(role)
                .isCurrent(planFinalized)
                .status(MissionDeviceAssignmentStatus.ASSIGNED)
                .checkupStatus(planFinalized
                        ? MissionDeviceCheckStatus.PENDING : MissionDeviceCheckStatus.PLANNING)
                .postcheckStatus(null)
                .verifiedBy(null)
                .verifiedAt(null)
                .failureNotes(null)
                .assignedAt(now)
                .build();

        missionDeviceAssignmentRepository.save(newAssignment);
        refreshResourceReadiness(mission);
        Mission saved = missionRepository.save(mission);
        return missionMapper.toResponse(saved);
    }

    @Override
    @Transactional
    @PreAuthorize("@missionAuthorizationService.canManageMissions()")
    public MissionResponse removeDevice(String missionId, String assignmentId) {
        Mission mission = getPlanningResourceMission(missionId);
        String resolvedMissionId = mission.getId();
        boolean planFinalized = MissionRuntimeGate.isStructurallyFinalized(mission);

        if (mission.getStatus() != MissionStatus.PLANNING && mission.getStatus() != MissionStatus.READY) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Cannot remove device in current mission status");
        }

        Instant now = Instant.now();
        MissionDeviceAssignment assignment = missionDeviceAssignmentRepository.findById(assignmentId)
                .filter(a -> resolvedMissionId.equals(a.getMission().getId()))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Device assignment not found"));

        if (assignment.getReleasedAt() == null) {
            String deviceId = assignment.getDevice().getId();
            if (planFinalized) {
                assignment.setIsCurrent(false);
                assignment.setReleasedAt(now);
                assignment.setReleaseReason("REMOVED_BY_MANAGER");
                missionDeviceAssignmentRepository.saveAndFlush(assignment);
            } else {
                missionDeviceAssignmentRepository.delete(assignment);
            }
            boolean deviceStillAssigned = missionDeviceAssignmentRepository.findByMissionId(resolvedMissionId).stream()
                    .anyMatch(a -> deviceId.equals(a.getDevice().getId())
                            && Boolean.TRUE.equals(a.getIsCurrent()) && a.getReleasedAt() == null);
            if (!deviceStillAssigned) resources.releaseResource(resolvedMissionId, deviceId, "REMOVED_BY_MANAGER");
        }

        refreshResourceReadiness(mission);
        Mission saved = missionRepository.save(mission);
        return missionMapper.toResponse(saved);
    }

    @Override
    @Transactional
    @PreAuthorize("@missionAuthorizationService.canManageMissions()")
    public MissionResponse replaceDevice(String missionId, String assignmentId, ReplaceDeviceRequest request) {
        Mission mission = getReplaceableResourceMission(missionId);
        List<MissionDeviceAssignment> currentAssignments = missionDeviceAssignmentRepository
                .findByMissionId(mission.getId());
        MissionDeviceAssignment previous = currentAssignments.stream()
                .filter(assignment -> assignmentId.equals(assignment.getId())
                        && Boolean.TRUE.equals(assignment.getIsCurrent())
                        && assignment.getReleasedAt() == null)
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Current device assignment not found"));

        String oldDeviceId = previous.getDevice().getId();
        String newDeviceId = request.getDeviceId().trim();
        String reason = request.getReason().trim();
        if (oldDeviceId.equals(newDeviceId)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Replacement device must be different from the current device");
        }
        boolean alreadyAssigned = currentAssignments.stream()
                .anyMatch(assignment -> Boolean.TRUE.equals(assignment.getIsCurrent())
                        && assignment.getReleasedAt() == null
                        && newDeviceId.equals(assignment.getDevice().getId()));
        if (alreadyAssigned) {
            throw new ApiException(ErrorCode.RESOURCE_ALREADY_EXISTS,
                    "Device is already assigned to this mission");
        }

        Device replacementDevice = deviceRepository.findById(newDeviceId)
                .orElseThrow(() -> new ApiException(ErrorCode.DEVICE_NOT_FOUND,
                        "Device not found with id: " + newDeviceId));
        if (replacementDevice.getStatus() == DeviceStatus.MAINTENANCE) {
            throw new ApiException(ErrorCode.DEVICE_NOT_AVAILABLE,
                    "Device is under MAINTENANCE and cannot be assigned to a mission.");
        }

        resources.lockResources(List.of(oldDeviceId, newDeviceId));
        resources.reserve(mission, newDeviceId, false);

        Instant now = Instant.now();
        previous.setIsCurrent(false);
        previous.setReleasedAt(now);
        previous.setReleaseReason(reason);
        // Flush the old MAIN row first because the database permits only one
        // current MAIN assignment per mission.
        missionDeviceAssignmentRepository.saveAndFlush(previous);

        resources.releaseResource(mission.getId(), oldDeviceId, "REPLACED");

        MissionDeviceAssignment replacement = missionDeviceAssignmentRepository.save(
                MissionDeviceAssignment.builder()
                        .mission(mission)
                        .device(replacementDevice)
                        .deviceRole(previous.getDeviceRole())
                        .status(MissionDeviceAssignmentStatus.ASSIGNED)
                        .checkupStatus(MissionDeviceCheckStatus.PENDING)
                        .isCurrent(true)
                        .assignedAt(now)
                        .build());
        previous.setReplacementAssignment(replacement);
        missionDeviceAssignmentRepository.save(previous);

        refreshResourceReadiness(mission);
        return missionMapper.toResponse(missionRepository.save(mission));
    }

    @Override
    @Transactional
    @PreAuthorize("@missionAuthorizationService.canControlFlight(#missionId) or @missionAuthorizationService.canOperatePayload(#missionId)")
    public MissionResponse requestDeviceReplacement(String missionId, RequestDeviceReplacementRequest request) {
        Mission mission = missionRepository.findByIdForUpdate(missionId)
                .orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
        if (!Set.of(MissionStatus.PENDING_CONFIRMATION, MissionStatus.READY).contains(mission.getStatus())) {
            throw new ApiException(ErrorCode.MISSION_STATUS_INVALID,
                    "A drone replacement can only be requested during preflight");
        }

        MissionDeviceAssignment assignment = missionDeviceAssignmentRepository
                .findAllByMissionIdAndDeviceRoleAndIsCurrentTrueOrderByCreatedAtDesc(missionId, DeviceRole.MAIN)
                .stream()
                .filter(candidate -> candidate.getReleasedAt() == null)
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Current main device assignment not found"));
        boolean hasFailedCheckForAssignedDrone = assignment.getDevice() != null
                && preDeviceCheckRepository.findFirstByMissionIdOrderByCreatedAtDesc(missionId)
                        .filter(run -> run.getDeviceConnection() != null
                                && run.getDeviceConnection().getDevice() != null
                                && java.util.Objects.equals(run.getDeviceConnection().getDevice().getId(),
                                        assignment.getDevice().getId()))
                        .map(run -> run.getFailedChecks() != null && run.getFailedChecks() > 0)
                        .orElse(false);
        if (!hasFailedCheckForAssignedDrone) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "A drone replacement request requires a failed preflight check");
        }

        // Older persisted runs may still have the failed item stored as optional.
        // The operator UI treats an explicit failed inspection as flight-blocking,
        // so use that current run as the source of truth for this request.
        assignment.setStatus(MissionDeviceAssignmentStatus.FAILED);
        assignment.setCheckupStatus(MissionDeviceCheckStatus.FAILED);
        if (assignment.getReplacementRequestedAt() == null) {
            assignment.setReplacementRequestedAt(Instant.now());
            assignment.setReplacementRequestReason(request.reason().trim());
            assignment.setReplacementRequestedBy(authenticatedUserResolver.getCurrentUserId());
            missionDeviceAssignmentRepository.save(assignment);
        }
        return missionMapper.toResponse(mission);
    }


    @Override
    @Transactional
    @PreAuthorize("@missionAuthorizationService.canManageMissions()")
    public MissionResponse assignStaff(String missionId, AssignStaffRequest request) {
        Mission mission = getPlanningResourceMission(missionId);
        String resolvedMissionId = mission.getId();
        String staffId = request.getStaffId();
        List<MissionStaffRole> assignedRoles = request.getAssignedRoles() == null
                ? List.of()
                : request.getAssignedRoles().stream()
                        .map(this::resolveStaffRole)
                        .distinct()
                        .toList();
        if (assignedRoles.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "At least one staff role is required");
        }

        User staff = staffDirectory.requireActiveStaff(staffId);

        Instant missionStart = mission.getScheduledStartAt();
        Instant missionEnd = mission.getScheduledEndAt();

        if (missionStart == null || missionEnd == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Mission schedule start and end times must be set before assigning staff.");
        }

        resources.reserve(mission, staffId, true);

        Set<MissionStaffRole> existingRoles = missionStaffAssignmentRepository.findByMissionId(resolvedMissionId).stream()
                .filter(assignment -> assignment.getReleasedAt() == null
                        && assignment.getStaff().getId().equals(staffId))
                .map(MissionStaffAssignment::getAssignedRole)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        List<MissionStaffRole> rolesToAssign = assignedRoles.stream()
                .filter(role -> !existingRoles.contains(role))
                .toList();
        if (rolesToAssign.isEmpty()) {
            return missionMapper.toResponse(missionRepository.save(mission));
        }

        boolean confirmed = MissionRuntimeGate.isStructurallyFinalized(mission);
        Instant assignedAt = Instant.now();
        missionStaffAssignmentRepository.saveAll(rolesToAssign.stream()
                .map(role -> MissionStaffAssignment.builder()
                        .mission(mission)
                        .staff(staff)
                        .assignedRole(role)
                        .responseStatus(confirmed
                                ? MissionStaffAssignmentStatus.PENDING
                                : MissionStaffAssignmentStatus.PLANNING)
                        .isCurrent(confirmed)
                        .assignedAt(assignedAt)
                        .build())
                .toList());

        if (confirmed) {
            boolean hasActiveMissionSchedule = userScheduleRepository.findByReferenceId(resolvedMissionId).stream()
                    .anyMatch(schedule -> schedule.getStaff() != null
                            && staffId.equals(schedule.getStaff().getId())
                            && schedule.getScheduleType() == UserScheduleType.MISSION
                            && schedule.getStatus() != UserScheduleStatus.CANCELLED
                            && schedule.getStatus() != UserScheduleStatus.COMPLETED);
            if (!hasActiveMissionSchedule) {
                UserSchedule staffSchedule = UserSchedule.builder()
                        .staff(staff)
                        .startTime(missionStart)
                        .endTime(missionEnd)
                        .scheduleType(UserScheduleType.MISSION)
                        .referenceId(mission.getId())
                        .status(UserScheduleStatus.SCHEDULED)
                        .notes("Assigned to mission " + mission.getMissionCode() + " as " + assignedRoles)
                        .build();
                userScheduleRepository.save(staffSchedule);
            }
        }

        Mission saved = missionRepository.save(mission);
        refreshResourceReadiness(saved);
        return missionMapper.toResponse(saved);
    }

    @Override
    @Transactional
    @PreAuthorize("@missionAuthorizationService.canManageMissions()")
    public MissionResponse removeStaff(String missionId, String assignmentId) {
        Mission mission = getPlanningResourceMission(missionId);
        String resolvedMissionId = mission.getId();
        Instant now = Instant.now();
        MissionStaffAssignment assignment = missionStaffAssignmentRepository.findById(assignmentId)
                .filter(a -> resolvedMissionId.equals(a.getMission().getId()))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Staff assignment not found"));
        if (assignment.getReleasedAt() == null) {
            String staffId = assignment.getStaff().getId();
            if (MissionRuntimeGate.isStructurallyFinalized(mission)) {
                assignment.setIsCurrent(false);
                assignment.setReleasedAt(now);
                assignment.setReleaseReason("REMOVED_BY_MANAGER");
                missionStaffAssignmentRepository.saveAndFlush(assignment);
            } else {
                missionStaffAssignmentRepository.delete(assignment);
                missionStaffAssignmentRepository.flush();
            }
            boolean staffStillAssigned = missionStaffAssignmentRepository.findByMissionId(resolvedMissionId).stream()
                    .anyMatch(a -> staffId.equals(a.getStaff().getId())
                            && Boolean.TRUE.equals(a.getIsCurrent()) && a.getReleasedAt() == null);
            if (!staffStillAssigned) {
                resources.releaseResource(resolvedMissionId, staffId, "REMOVED_DURING_PLANNING");
                userScheduleRepository.findByReferenceId(resolvedMissionId).stream()
                        .filter(schedule -> schedule.getStaff() != null
                                && staffId.equals(schedule.getStaff().getId())
                                && schedule.getStatus() != UserScheduleStatus.COMPLETED)
                        .forEach(schedule -> schedule.setStatus(UserScheduleStatus.CANCELLED));
            }
        }
        refreshResourceReadiness(mission);
        Mission saved = missionRepository.save(mission);
        return missionMapper.toResponse(saved);
    }

    @Override
    @Transactional
    @PreAuthorize("@missionAuthorizationService.canManageMissions()")
    public MissionResponse replaceStaff(String missionId, String staffId, ReplaceStaffRequest request) {
        Mission mission = getReplaceableResourceMission(missionId);
        String oldStaffId = staffId.trim();
        String newStaffId = request.getStaffId().trim();
        String reason = request.getReason().trim();
        List<MissionStaffAssignment> currentAssignments = missionStaffAssignmentRepository
                .findByMissionId(mission.getId());
        List<MissionStaffAssignment> previousAssignments = currentAssignments.stream()
                .filter(assignment -> Boolean.TRUE.equals(assignment.getIsCurrent())
                        && assignment.getReleasedAt() == null
                        && oldStaffId.equals(assignment.getStaff().getId()))
                .toList();
        if (previousAssignments.isEmpty()) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                    "Current staff assignment not found for staff: " + oldStaffId);
        }

        if (oldStaffId.equals(newStaffId)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Replacement staff must be different from the current staff");
        }
        Set<MissionStaffRole> replacedRoles = previousAssignments.stream()
                .map(MissionStaffAssignment::getAssignedRole)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        if (replacedRoles.isEmpty() || replacedRoles.size() != previousAssignments.size()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Current staff role assignments are invalid");
        }
        boolean alreadyAssignedForRole = currentAssignments.stream()
                .anyMatch(assignment -> Boolean.TRUE.equals(assignment.getIsCurrent())
                        && assignment.getReleasedAt() == null
                        && newStaffId.equals(assignment.getStaff().getId())
                        && replacedRoles.contains(assignment.getAssignedRole()));
        if (alreadyAssignedForRole) {
            throw new ApiException(ErrorCode.RESOURCE_ALREADY_EXISTS,
                    "Replacement staff already has one of the roles being replaced");
        }

        User replacementStaff = staffDirectory.requireActiveStaff(newStaffId);
        resources.lockResources(List.of(oldStaffId, newStaffId));
        resources.reserve(mission, newStaffId, true);

        Instant now = Instant.now();
        List<MissionStaffAssignment> replacements = missionStaffAssignmentRepository.saveAll(
                replacedRoles.stream()
                        .map(role -> MissionStaffAssignment.builder()
                                .mission(mission)
                                .staff(replacementStaff)
                                .assignedRole(role)
                                .responseStatus(MissionStaffAssignmentStatus.PENDING)
                                .isCurrent(true)
                                .assignedAt(now)
                                .build())
                        .toList());
        Map<MissionStaffRole, MissionStaffAssignment> replacementByRole = replacements.stream()
                .collect(java.util.stream.Collectors.toMap(
                        MissionStaffAssignment::getAssignedRole,
                        assignment -> assignment));

        previousAssignments.forEach(previous -> {
            previous.setIsCurrent(false);
            previous.setReleasedAt(now);
            previous.setReleaseReason(reason);
            previous.setReplacementAssignment(replacementByRole.get(previous.getAssignedRole()));
        });
        missionStaffAssignmentRepository.saveAll(previousAssignments);

        resources.releaseResource(mission.getId(), oldStaffId, "REPLACED");

        refreshResourceReadiness(mission);
        return missionMapper.toResponse(missionRepository.save(mission));
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("@missionAuthorizationService.canViewMission(#missionId)")
    public List<MissionResourceReplacementResponse> getResourceReplacementHistory(String missionId) {
        Mission mission = getMissionByIdOrThrow(missionId);
        List<MissionResourceReplacementResponse> history = new ArrayList<>();

        for (MissionDeviceAssignment previous : missionDeviceAssignmentRepository.findByMissionId(mission.getId())) {
            MissionDeviceAssignment replacement = previous.getReplacementAssignment();
            if (replacement == null) continue;
            history.add(MissionResourceReplacementResponse.builder()
                    .resourceType("DEVICE")
                    .resourceRole(previous.getDeviceRole() == null ? null : previous.getDeviceRole().name())
                    .previousAssignmentId(previous.getId())
                    .previousResourceId(previous.getDevice().getId())
                    .previousResourceName(previous.getDevice().getName())
                    .previousResourceCode(previous.getDevice().getDeviceCode())
                    .replacementAssignmentId(replacement.getId())
                    .replacementResourceId(replacement.getDevice().getId())
                    .replacementResourceName(replacement.getDevice().getName())
                    .replacementResourceCode(replacement.getDevice().getDeviceCode())
                    .reason(previous.getReleaseReason())
                    .replacedAt(previous.getReleasedAt())
                    .build());
        }
        for (MissionStaffAssignment previous : missionStaffAssignmentRepository.findByMissionId(mission.getId())) {
            MissionStaffAssignment replacement = previous.getReplacementAssignment();
            if (replacement == null) continue;
            history.add(MissionResourceReplacementResponse.builder()
                    .resourceType("STAFF")
                    .resourceRole(previous.getAssignedRole() == null ? null : previous.getAssignedRole().name())
                    .previousAssignmentId(previous.getId())
                    .previousResourceId(previous.getStaff().getId())
                    .previousResourceName(previous.getStaff().getFullName())
                    .previousResourceCode(previous.getStaff().getEmail())
                    .replacementAssignmentId(replacement.getId())
                    .replacementResourceId(replacement.getStaff().getId())
                    .replacementResourceName(replacement.getStaff().getFullName())
                    .replacementResourceCode(replacement.getStaff().getEmail())
                    .reason(previous.getReleaseReason())
                    .replacedAt(previous.getReleasedAt())
                    .build());
        }
        history.sort(Comparator.comparing(MissionResourceReplacementResponse::getReplacedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return history;
    }
    
    @Override
    @Transactional
    @PreAuthorize("@missionAuthorizationService.canManageMissions()")
    public MissionResponse updateMission(String missionId, MissionUpdateRequest request) {
        String orderId = missionRepository.findOrderIdByMissionId(missionId)
                .orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
        Order order = orderRepository.findByIdForUpdate(orderId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        Mission mission = missionRepository.findByIdForUpdate(missionId).orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
        boolean rework = mission.getPurpose() == MissionPurpose.REWORK;
        if (!orderId.equals(mission.getOrder().getId())) throw new ApiException(ErrorCode.CHECKLIST_ASSIGNMENT_CROSS_ORDER);
        if ((order.getExecutionPlan().getStatus() != ExecutionPlanStatus.DRAFT
                && order.getExecutionPlan().getStatus() != ExecutionPlanStatus.FINALIZED)
                || mission.getStatus() != MissionStatus.PLANNING
                || (MissionRuntimeGate.isStructurallyFinalized(mission) && !rework))
            throw new ApiException(ErrorCode.MISSION_STATUS_INVALID,
                    "Only unfinalized planning Missions can be updated");
        if (!rework && order.getOrderStatus() != OrderStatus.APPROVED && order.getOrderStatus() != OrderStatus.IN_PROGRESS)
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        if (request == null) throw new ApiException(ErrorCode.INVALID_REQUEST);
        if (rework) {
            if (mission.getResultRevisionCaseId() == null)
                throw new ApiException(ErrorCode.INVALID_DELIVERY_STATE);
            var revisionCase = resultRevisionCases.findById(mission.getResultRevisionCaseId())
                    .filter(value -> value.isActive() && orderId.equals(value.getOrder().getId()))
                    .orElseThrow(() -> new ApiException(ErrorCode.INVALID_DELIVERY_STATE));
            if (order.getOrderStatus() != OrderStatus.COMPLETED
                    || revisionCase.getStatus() != com.ondemandmonitoring.delivery.enums.ResultRevisionCaseStatus.OPEN
                    || mission.getCancellationAt() != null
                    || !missionChecklistExecutionRepository.findOrderedByMissionId(missionId).isEmpty())
                throw new ApiException(ErrorCode.MISSION_PLANNING_ONLY);
            if (request.getChecklistIds() != null)
                throw new ApiException(ErrorCode.INVALID_REQUEST,
                        "The approved rework checklist scope cannot be changed through schedule editing");
        }

        Instant previousStart = mission.getScheduledStartAt();
        Instant previousEnd = mission.getScheduledEndAt();

        Instant newStart = request.getScheduledStartAt() != null ? request.getScheduledStartAt() : previousStart;
        Instant newEnd = request.getScheduledEndAt() != null ? request.getScheduledEndAt() : previousEnd;

        validatePlanningSchedule(newStart, newEnd, mission.getOrder(), mission.getPurpose());
        resources.revalidateSchedule(mission, newStart, newEnd);

        mission.setScheduledStartAt(newStart);
        mission.setScheduledEndAt(newEnd);

        Mission saved = missionRepository.save(mission);
        if (request.getChecklistIds() != null) assignChecklist(saved, order, request.getChecklistIds());

        return missionMapper.toResponse(saved);
    }

    private MissionStaffRole resolveStaffRole(String role) {
        if (role == null || role.isBlank()) {
            return MissionStaffRole.PILOT;
        }
        try {
            return MissionStaffRole.valueOf(role.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Invalid staff role: " + role);
        }
    }

    @Override
    @Transactional
    @PreAuthorize("@missionAuthorizationService.canRespondToMission(#missionId,#staffId)")
    public MissionResponse rejectMission(String missionId, String staffId, String reason) {
        Mission mission = getRuntimeMissionOrThrow(missionId);
        String resolvedMissionId = mission.getId();
        requireCrewConfirmationStatus(mission);
        List<MissionStaffAssignment> assignments =
                requireCurrentStaffAssignments(resolvedMissionId, staffId);

        Instant now = Instant.now();
        assignments.forEach(assignment -> {
            if (assignment.getResponseStatus() != MissionStaffAssignmentStatus.PENDING) return;
            assignment.setResponseStatus(MissionStaffAssignmentStatus.REJECTED);
            assignment.setDeclineReason(reason);
            assignment.setRespondedAt(now);
        });
        missionStaffAssignmentRepository.saveAll(assignments);

        refreshResourceReadiness(mission);
        Mission saved = missionRepository.save(mission);
        return missionMapper.toResponse(saved);
    }

    @Override
    @Transactional
    @PreAuthorize("@missionAuthorizationService.canRespondToMission(#missionId,#staffId)")
    public MissionResponse acceptMission(String missionId, String staffId) {
        Mission mission = getRuntimeMissionOrThrow(missionId);
        String resolvedMissionId = mission.getId();
        requireCrewConfirmationStatus(mission);
        List<MissionStaffAssignment> assignments =
                requireCurrentStaffAssignments(resolvedMissionId, staffId);

        Instant now = Instant.now();
        assignments.forEach(assignment -> {
            if (assignment.getResponseStatus() != MissionStaffAssignmentStatus.PENDING) return;
            assignment.setResponseStatus(MissionStaffAssignmentStatus.ACCEPTED);
            assignment.setRespondedAt(now);
        });
        missionStaffAssignmentRepository.saveAll(assignments);
        refreshResourceReadiness(mission);
        return missionMapper.toResponse(missionRepository.save(mission));
    }

    // =========================================================================
    // F3.2 – GCS Pairing & Pre-flight Gate
    // =========================================================================

    @Override
    @Transactional
    @PreAuthorize("@missionAuthorizationService.canOperatePayload(#missionId)")
    public MissionResponse connectGcs(String missionId) {
        return deviceConnectionService.connectGcs(getRuntimeMissionOrThrow(missionId).getId());
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("@missionAuthorizationService.canViewMission(#missionId)")
    public MissionTelemetryReadinessResponse getTelemetryReadiness(String missionId) {
        // A projection must not take the runtime mutation lock or establish a session.
        Mission mission = getMissionByIdOrThrow(missionId);
        var assignment = missionDeviceAssignmentRepository
                .findAllByMissionIdAndIsCurrentTrueOrderByCreatedAtDesc(missionId).stream()
                .filter(a -> a.getReleasedAt() == null && a.getDevice() != null)
                .filter(a -> a.getDeviceRole() == DeviceRole.MAIN)
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Mission has no current main device assignment"));
        String deviceId = assignment.getDevice().getId();
        if (!MissionRuntimeGate.isStructurallyFinalized(mission)
                || !Set.of(MissionStatus.PENDING_CONFIRMATION, MissionStatus.READY, MissionStatus.IN_PROGRESS)
                        .contains(mission.getStatus())) {
            return new MissionTelemetryReadinessResponse(deviceId, false, null);
        }
        checklistExecutionService.requireMaterialized(mission);
        var session = deviceConnectionRepository
                .findTopByMissionIdAndConnectionStatusOrderByConnectedAtDesc(missionId, "CONNECTED")
                .filter(c -> Boolean.TRUE.equals(c.getTelemetryActive()) && c.getDisconnectedAt() == null)
                .filter(c -> c.getDeviceAssignment() != null && assignment.getId().equals(c.getDeviceAssignment().getId()))
                .filter(c -> c.getStaffAssignment() != null && Boolean.TRUE.equals(c.getStaffAssignment().getIsCurrent())
                        && c.getStaffAssignment().getReleasedAt() == null
                        && c.getStaffAssignment().getResponseStatus() == MissionStaffAssignmentStatus.ACCEPTED);
        var telemetry = session.flatMap(c -> telemetryRepository.findTopByDeviceConnectionIdOrderByRecordedAtDesc(c.getId()));
        Instant recordedAt = telemetry.map(com.ondemandmonitoring.device.domain.DeviceTelemetry::getRecordedAt).orElse(null);
        // Preserve the original preflight-readiness freshness contract (10 seconds).
        boolean ready = telemetry.map(t -> Boolean.TRUE.equals(t.getConnected())).orElse(false)
                && recordedAt != null
                && java.time.Duration.between(recordedAt, Instant.now()).abs().compareTo(java.time.Duration.ofSeconds(10)) <= 0;
        return new MissionTelemetryReadinessResponse(deviceId, ready, recordedAt);
    }

    @Override
    @Transactional
    @PreAuthorize("@missionAuthorizationService.canOperatePayload(#missionId)")
    public MissionResponse disconnectGcs(String missionId, String disconnectReason) {
        return deviceConnectionService.disconnectGcs(getRuntimeMissionOrThrow(missionId).getId(), disconnectReason);
    }

    @Override
    @Transactional
    @PreAuthorize("@missionAuthorizationService.canOperatePayload(#missionId)")
    public MissionResponse handleGcsSessionLost(String missionId, String reason) {
        return deviceConnectionService.handleGcsSessionLost(getRuntimeMissionOrThrow(missionId).getId(), reason);
    }

    @Override
    @Transactional
    @PreAuthorize("@missionAuthorizationService.canOperatePayload(#missionId)")
    public PreDeviceCheckResponse runPreDeviceCheck(String missionId, String deviceId) {
        Mission mission = getRuntimeMissionOrThrow(missionId);
        if (mission.getStatus() == MissionStatus.READY) {
            // The preflight page may submit completion again when continuing to handover.
            // Preserve READY_TO_FLY and return the already-passed result idempotently.
            return preDeviceCheckCompletionService.complete(mission.getId(), deviceId);
        }
        missionDeviceAssignmentRepository.findAllByMissionIdAndDeviceIdAndIsCurrentTrueOrderByCreatedAtDesc(missionId, deviceId)
                .forEach(assignment -> {
                    assignment.setCheckupStatus(MissionDeviceCheckStatus.CHECKING);
                    assignment.setStatus(MissionDeviceAssignmentStatus.PREFLIGHT_CHECKING);
                });
        PreDeviceCheckResponse result = preDeviceCheckCompletionService.complete(missionId, deviceId);
        missionDeviceAssignmentRepository.findAllByMissionIdAndDeviceIdAndIsCurrentTrueOrderByCreatedAtDesc(missionId, deviceId)
                .forEach(assignment -> assignment.setCheckupStatus(Boolean.TRUE.equals(result.getOverallPassed())
                        ? MissionDeviceCheckStatus.PASSED : MissionDeviceCheckStatus.FAILED));
        missionRepository.findById(missionId).ifPresent(this::refreshResourceReadiness);
        return result;
    }








    private void requireFeasiblePlan(MissionPlan plan) {
        requireFeasiblePlan(plan, false);
    }

    private MissionStaffAssignment requireCurrentStaffAssignment(String missionId, String staffId) {
        List<MissionStaffAssignment> acceptedAssignments = requireCurrentStaffAssignments(missionId, staffId).stream()
                .filter(entry -> entry.getResponseStatus() == MissionStaffAssignmentStatus.ACCEPTED)
                .toList();
        return acceptedAssignments.stream()
                .filter(entry -> entry.getAssignedRole() == MissionStaffRole.OPERATOR)
                .findFirst()
                .or(() -> acceptedAssignments.stream().findFirst())
                .orElseThrow(() -> new ApiException(ErrorCode.ACCESS_DENIED,
                        "Staff is not assigned to mission " + missionId));
    }

    private List<MissionStaffAssignment> requireCurrentStaffAssignments(String missionId, String staffId) {
        List<MissionStaffAssignment> assignments = missionStaffAssignmentRepository
                .findAllByMissionIdAndStaffIdAndIsCurrentTrueOrderByAssignedAtDesc(missionId, staffId);
        if (assignments.isEmpty()) {
            throw new ApiException(ErrorCode.ACCESS_DENIED,
                    "Staff is not assigned to mission " + missionId);
        }
        return assignments;
    }

    private void requireCrewConfirmationStatus(Mission mission) {
        if (mission.getStatus() != MissionStatus.PENDING_CONFIRMATION) {
            throw new ApiException(ErrorCode.MISSION_STATUS_INVALID,
                    "Mission must be awaiting staff confirmation, current: "
                            + mission.getStatus());
        }
    }

    private boolean hasRequiredCrewAssigned(String missionId) {
        Set<MissionStaffRole> roles = missionStaffAssignmentRepository.findAllByMissionIdAndIsCurrentTrue(missionId)
                .stream()
                .filter(this::isUsablePlanningCrew)
                .map(MissionStaffAssignment::getAssignedRole)
                .collect(java.util.stream.Collectors.toSet());
        return roles.containsAll(REQUIRED_CREW_ROLES);
    }

    private void refreshResourceReadiness(Mission mission) {
        if (mission.getStatus() == MissionStatus.READY && !isReadyForHandover(mission)) {
            mission.setStatus(MissionStatus.PENDING_CONFIRMATION);
        }
    }

    private boolean isReadyForHandover(Mission mission) {
        List<MissionStaffAssignment> crew = missionStaffAssignmentRepository.findByMissionId(mission.getId()).stream()
                .filter(this::isUsablePlanningCrew).toList();
        List<MissionDeviceAssignment> devices = missionDeviceAssignmentRepository.findByMissionId(mission.getId()).stream()
                .filter(a -> Boolean.TRUE.equals(a.getIsCurrent()) && a.getReleasedAt() == null && a.getDevice() != null)
                .toList();
        Set<MissionStaffRole> acceptedRoles = crew.stream()
                .filter(a -> a.getResponseStatus() == MissionStaffAssignmentStatus.ACCEPTED)
                .map(MissionStaffAssignment::getAssignedRole).collect(java.util.stream.Collectors.toSet());
        boolean mainDeviceReady = devices.stream().anyMatch(assignment ->
                assignment.getDeviceRole() == DeviceRole.MAIN
                        && assignment.getStatus() == MissionDeviceAssignmentStatus.READY_TO_FLY
                        && assignment.getCheckupStatus() == MissionDeviceCheckStatus.PASSED);
        return acceptedRoles.containsAll(REQUIRED_CREW_ROLES)
                && crew.stream().allMatch(a -> a.getResponseStatus() == MissionStaffAssignmentStatus.ACCEPTED)
                && !devices.isEmpty()
                && devices.stream().allMatch(a -> a.getCheckupStatus() == MissionDeviceCheckStatus.PASSED)
                && mainDeviceReady;
    }

    private boolean isUsablePlanningCrew(MissionStaffAssignment assignment) {
        return Boolean.TRUE.equals(assignment.getIsCurrent()) && assignment.getReleasedAt() == null
                && assignment.getStaff() != null && Boolean.TRUE.equals(assignment.getStaff().getIsActive());
    }

    private void requireFeasiblePlan(MissionPlan plan, boolean allowRuntimePreflightBatteryFallback) {
        FeasibilityStatus status = plan.getFeasibilityStatus();
        if (status == FeasibilityStatus.FEASIBLE) {
            return;
        }
        if (allowRuntimePreflightBatteryFallback && status == FeasibilityStatus.BATTERY_DATA_UNAVAILABLE) {
            return;
        }
        if (status == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Mission plan feasibility could not be determined.");
        }
        String message = switch (status) {
            case BATTERY_DATA_UNAVAILABLE ->
                "Device battery telemetry is unavailable. Connect the device and refresh telemetry before pre-device.";
            case INSUFFICIENT_BATTERY -> "Device battery is insufficient for this mission and its safety reserve.";
            case INVALID_TARGET -> "Mission target is invalid for route planning.";
            case NO_SAFE_ROUTE -> "No safe route could be generated for this mission.";
            default -> "Mission plan feasibility could not be determined.";
        };
        throw new ApiException(ErrorCode.INVALID_REQUEST, message);
    }

    @Override
    @Transactional
    @PreAuthorize("(@missionAuthorizationService.canOperatePayload(#missionId) or @missionAuthorizationService.canControlFlight(#missionId)) and @missionAuthorizationService.canViewStaffMissions(#staffId)")
    public MissionResponse handoverControl(String missionId, String staffId) {
        Mission mission = getRuntimeMissionOrThrow(missionId);
        MissionRuntimeGate.requireOperationalCommandsAllowed(mission);
        String resolvedMissionId = mission.getId();
        requireStatus(mission, MissionStatus.PENDING_CONFIRMATION);
        if (!isReadyForHandover(mission)) {
            throw new ApiException(ErrorCode.MISSION_STATUS_INVALID,
                    "All assigned staff must accept and every required device precheck must pass before handover");
        }
        if (getCurrentMainDeviceAssignment(resolvedMissionId).getStatus()
                != MissionDeviceAssignmentStatus.READY_TO_FLY) {
            throw new ApiException(ErrorCode.MISSION_STATUS_INVALID,
                    "Assigned device must pass preflight before control handover");
        }
        requireFreshPreflightAuthorization(resolvedMissionId);

        // Record ControlHandover audit log linked to current DeviceConnection
        DeviceConnection activeConnection = deviceConnectionRepository
                .findTopByMissionIdAndConnectionStatusOrderByConnectedAtDesc(resolvedMissionId, "CONNECTED")
                .orElse(null);

        ControlHandover handover = new ControlHandover();
        handover.setDeviceConnection(activeConnection);
        handover.setStaffAssignment(requireCurrentStaffAssignment(resolvedMissionId, staffId));
        handover.setStatus("CONFIRMED");
        handover.setAcknowledgementText("Staff confirmed control handover and pre-device checks before launch");
        handover.setConfirmedAt(Instant.now());
        controlHandoverRepository.save(handover);

        mission.setStatus(MissionStatus.READY);
        log.info("Mission {} – control handed over to Staff {} at {}", missionId, staffId, Instant.now());
        Mission saved = missionRepository.save(mission);
        return missionMapper.toResponse(saved);
    }

    // =========================================================================
    // F3.3 – Takeoff & Execution lifecycle
    // =========================================================================

    @Override
    @Transactional
    @PreAuthorize("@missionAuthorizationService.canStartMission(#missionId)")
    public MissionResponse startMission(String missionId) {
        Mission mission = getRuntimeMissionOrThrow(missionId);
        MissionRuntimeGate.requireOperationalCommandsAllowed(mission);
        requireFlightPermitApproved(mission);
        requireStatus(mission, MissionStatus.READY);
        if (controlHandoverRepository
                .findTopByStaffAssignment_Mission_IdAndStatusOrderByConfirmedAtDesc(mission.getId(), "CONFIRMED")
                .isEmpty()) {
            throw new ApiException(ErrorCode.MISSION_STATUS_INVALID,
                    "Control handover must be confirmed before the mission can start");
        }
        MissionDeviceAssignment deviceAssignment = getCurrentMainDeviceAssignment(mission.getId());
        if (deviceAssignment.getStatus() != MissionDeviceAssignmentStatus.READY_TO_FLY) {
            throw new ApiException(ErrorCode.MISSION_STATUS_INVALID,
                    "Assigned device must pass preflight before the mission can start");
        }
        requireFreshSafeWeather(mission);

        mission.setStatus(MissionStatus.IN_PROGRESS);
        deviceAssignment.setStatus(MissionDeviceAssignmentStatus.IN_PROGRESS);
        if (mission.getActualStartAt() == null) {
            mission.setActualStartAt(Instant.now());
        }
        updateDeviceStatus(mission, DeviceStatus.ACTIVE_MISSION);
        if (checklistAssignmentStatusSync != null) checklistAssignmentStatusSync.syncForMission(mission);

        log.info("Mission {} execution started – telemetry and RTSP video stream OPENED", missionId);
        Mission saved = missionRepository.save(mission);
        return missionMapper.toResponse(saved);
    }

    private void requireFlightPermitApproved(Mission mission) {
        flightPermitService.requireApprovedForExecution(mission);
    }

    private void requireFreshPreflightAuthorization(String missionId) {
        var run = preDeviceCheckRepository.findFirstByMissionIdOrderByCreatedAtDesc(missionId)
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_REQUEST,
                        "Không tìm thấy phiên kiểm tra trước chuyến bay."));
        boolean blocked = run.getItems().stream().anyMatch(item ->
                !"weather_conditions".equals(item.getCheckType())
                        && Boolean.TRUE.equals(item.getRequired())
                        && item.getStatus() != PreDeviceItemStatus.PASSED);
        if (blocked) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Kiểm tra trước chuyến bay chưa đạt đầy đủ. Vui lòng kiểm tra lại.");
        }
    }

    private void requireFreshSafeWeather(Mission mission) {
        MissionWeatherCheck check = missionWeatherCheckRepository
                .findFirstByMissionIdOrderByCreatedAtDesc(mission.getId())
                .filter(weather -> weather.getCheckedAt() != null
                        && weather.getCheckedAt().isAfter(Instant.now().minusSeconds(15 * 60L)))
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_REQUEST,
                        "Hãy kiểm tra thời tiết trong vòng 15 phút trước khi bắt đầu Mission."));
        Instant permitUpdatedAt = mission.getFlightPermit() == null
                ? null
                : mission.getFlightPermit().getUpdatedAt();
        if (permitUpdatedAt != null && !check.getCheckedAt().isAfter(permitUpdatedAt)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Weather must be checked after the flight permit is approved");
        }
        if (!Boolean.TRUE.equals(check.getSafeToFly())) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Thời tiết hiện tại chưa an toàn để bắt đầu Mission.");
        }
        if (!Boolean.TRUE.equals(check.getInspectorAccepted())) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Nghiệm thu cần xác nhận thời tiết đạt trước khi bắt đầu Mission.");
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Override
    @Transactional
    @PreAuthorize("@missionAuthorizationService.canControlFlight(#missionId)")
    public MissionResponse markReturning(String missionId) {
        Mission mission = getRuntimeMissionOrThrow(missionId);
        if (Set.of(MissionStatus.IN_PROGRESS, MissionStatus.AWAITING_REVIEW,
                MissionStatus.PENDING_REVIEW, MissionStatus.COMPLETED).contains(mission.getStatus())
                && postDeviceCheckRepository.findFirstByMissionIdOrderByCreatedAtDesc(mission.getId()).isPresent()) {
            return missionMapper.toResponse(mission);
        }
        MissionDeviceAssignment deviceAssignment = getCurrentMainDeviceAssignment(mission.getId());
        if (mission.getStatus() != MissionStatus.IN_PROGRESS
                || deviceAssignment.getStatus() != MissionDeviceAssignmentStatus.IN_PROGRESS) {
            if (Set.of(MissionDeviceAssignmentStatus.RETURNING,
                    MissionDeviceAssignmentStatus.POSTFLIGHT_CHECKING,
                    MissionDeviceAssignmentStatus.COMPLETED).contains(deviceAssignment.getStatus())
                    && Set.of(MissionStatus.IN_PROGRESS, MissionStatus.AWAITING_REVIEW,
                            MissionStatus.PENDING_REVIEW, MissionStatus.COMPLETED).contains(mission.getStatus())) {
                return missionMapper.toResponse(mission);
            }
            throw new ApiException(ErrorCode.MISSION_STATUS_INVALID,
                    "Mission execution must be in progress before marking the device returning");
        }
        // A requested failure may have been caused by an incomplete checklist; safe return must
        // remain available even when ordinary mission completion readiness is not satisfied.
        if (mission.getFailureRequestedAt() == null) {
            checklistExecutionService.requireReadyForSubmission(mission);
        }
        deviceAssignment.setStatus(MissionDeviceAssignmentStatus.RETURNING);
        updateDeviceStatus(mission, DeviceStatus.RETURNING);
        log.info("Mission {} – device returning to base", missionId);
        Mission saved = missionRepository.save(mission);
        return missionMapper.toResponse(saved);
    }

    @Override
    @Transactional
    @PreAuthorize("@missionAuthorizationService.canControlFlight(#missionId) or @missionAuthorizationService.canContinueRequestedFailurePostflight(#missionId)")
    public MissionResponse startPostDeviceChecking(String missionId) {
        Mission mission = getRuntimeMissionOrThrow(missionId);
        if (Set.of(MissionStatus.IN_PROGRESS, MissionStatus.AWAITING_REVIEW,
                MissionStatus.PENDING_REVIEW, MissionStatus.COMPLETED).contains(mission.getStatus())
                && postDeviceCheckRepository.findFirstByMissionIdOrderByCreatedAtDesc(mission.getId()).isPresent()) {
            return missionMapper.toResponse(mission);
        }
        MissionDeviceAssignment deviceAssignment = getCurrentMainDeviceAssignment(mission.getId());
        if (deviceAssignment.getStatus() == MissionDeviceAssignmentStatus.POSTFLIGHT_CHECKING
                || (deviceAssignment.getStatus() == MissionDeviceAssignmentStatus.COMPLETED
                        && Set.of(MissionStatus.IN_PROGRESS, MissionStatus.AWAITING_REVIEW, MissionStatus.PENDING_REVIEW,
                                MissionStatus.COMPLETED).contains(mission.getStatus()))) {
            return missionMapper.toResponse(mission);
        }
        boolean neverFlew = mission.getActualStartAt() == null
                && Set.of(MissionDeviceAssignmentStatus.ASSIGNED, MissionDeviceAssignmentStatus.CONNECTED,
                        MissionDeviceAssignmentStatus.PREFLIGHT_CHECKING, MissionDeviceAssignmentStatus.READY_TO_FLY)
                        .contains(deviceAssignment.getStatus());
        if (!neverFlew && deviceAssignment.getStatus() != MissionDeviceAssignmentStatus.RETURNING) {
            throw new ApiException(ErrorCode.MISSION_STATUS_INVALID,
                    "Device must be returning before postflight inspection");
        }
        deviceAssignment.setStatus(MissionDeviceAssignmentStatus.POSTFLIGHT_CHECKING);
        log.info("Mission {} – post-device inspection started", missionId);
        Mission saved = missionRepository.save(mission);
        return missionMapper.toResponse(saved);
    }

    @Override
    @Transactional
    @PreAuthorize("@missionAuthorizationService.canCompleteMission(#missionId)")
    public MissionResponse completeMission(String missionId) {
        Mission resolved = getRuntimeMissionOrThrow(missionId);
        MissionRuntimeGate.requireOperationalCommandsAllowed(resolved);
        orderRepository.findById(resolved.getOrder().getId())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        Mission mission = missionRepository.findById(resolved.getId()).orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
        String resolvedMissionId = mission.getId();
        if (mission.getFailureRequestedAt() != null) {
            throw new ApiException(ErrorCode.MISSION_STATUS_INVALID,
                    "A requested mission failure must be finalized after postflight instead of completing the mission");
        }
        if (mission.getCompletedAt() != null
                && postDeviceCheckRepository.findFirstByMissionIdOrderByCreatedAtDesc(resolvedMissionId).isPresent()) {
            return missionMapper.toResponse(mission);
        }
        if (mission.getStatus() != MissionStatus.IN_PROGRESS || mission.getActualEndAt() == null) {
            throw new ApiException(ErrorCode.MISSION_STATUS_INVALID,
                    "Mission must be in progress with a recorded post-flight inspection before acceptance");
        }
        if (postDeviceCheckRepository.findFirstByMissionIdOrderByCreatedAtDesc(resolvedMissionId).isEmpty()) {
            throw new ApiException(ErrorCode.MISSION_STATUS_INVALID,
                    "A recorded post-flight inspection is required before mission completion");
        }
        checklistExecutionService.requireReadyForSubmission(mission);
        Instant completedAt = Instant.now();
        if (mission.getActualEndAt() == null) {
            mission.setActualEndAt(completedAt);
        }
        mission.setCompletedAt(completedAt);
        releaseMissionResources(mission, "MISSION_COMPLETE");
        if (checklistAssignmentStatusSync != null) checklistAssignmentStatusSync.syncForMission(mission);
        log.info("Mission {} acceptance completed; awaiting result submission to Manager", missionId);
        Mission saved = missionRepository.save(mission);
        missionResultService.ensureCompletedResult(saved);
        if (deliveryWorkflowService != null) {
            deliveryWorkflowService.markProcessing(saved.getOrder().getId());
        }
        return missionMapper.toResponse(saved);
    }

    @Override
    @Transactional
    @PreAuthorize("@missionAuthorizationService.canControlFlight(#missionId)")
    public MissionResponse requestMissionFailure(String missionId, String reason) {
        Mission mission = missionRepository.findByIdForUpdate(missionId)
                .orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
        if (mission.getFailureRequestedAt() != null) return missionMapper.toResponse(mission);
        if (mission.getStatus() != MissionStatus.IN_PROGRESS || mission.getActualStartAt() == null) {
            throw new ApiException(ErrorCode.MISSION_STATUS_INVALID,
                    "A flight failure can only be requested while a mission is in progress");
        }
        String normalizedReason = reason == null ? null : reason.strip();
        if (normalizedReason == null || normalizedReason.isBlank() || normalizedReason.length() > 1000) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "A failure reason of at most 1000 characters is required");
        }
        mission.setFailureReason(normalizedReason);
        mission.setFailureRequestedAt(Instant.now());
        return missionMapper.toResponse(missionRepository.save(mission));
    }

    @Override
    @Transactional
    @PreAuthorize("@missionAuthorizationService.canFailMission(#missionId)")
    public MissionResponse failMission(String missionId, String reason) {
        Mission mission = getRuntimeMissionOrThrow(missionId);
        boolean requestedFlightFailure = mission.getFailureRequestedAt() != null
                && mission.getActualEndAt() != null
                && postDeviceCheckRepository.findFirstByMissionIdOrderByCreatedAtDesc(mission.getId())
                        .map(run -> run.getStatus() == DeviceCheckStatus.PASSED
                                || run.getStatus() == DeviceCheckStatus.FAILED)
                        .orElse(false);
        boolean flightConnectionStillActive = deviceConnectionRepository.findByMissionId(mission.getId()).stream()
                .anyMatch(c -> "CONNECTED".equals(c.getConnectionStatus())
                        || Boolean.TRUE.equals(c.getTelemetryActive()));
        if ((mission.getStatus() == MissionStatus.IN_PROGRESS && !requestedFlightFailure)
                || (mission.getActualStartAt() != null && mission.getStatus() != MissionStatus.AWAITING_REVIEW
                    && mission.getStatus() != MissionStatus.COMPLETED && !requestedFlightFailure)
                || flightConnectionStillActive)
            throw new ApiException(ErrorCode.MISSION_STATUS_INVALID,
                    "An active mission cannot be marked failed before flight operations are closed");
        String effectiveReason = reason == null || reason.isBlank()
                ? mission.getFailureReason() : reason.strip();
        if (effectiveReason == null || effectiveReason.isBlank()) effectiveReason = "Mission failed";
        if (effectiveReason.length() > 1000)
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Failure reason must not exceed 1000 characters");
        checklistExecutionService.markMissionFailed(mission.getId(), effectiveReason);
        missionDeviceAssignmentRepository.findAllByMissionIdAndIsCurrentTrueOrderByCreatedAtDesc(mission.getId())
                .stream().filter(assignment -> assignment.getReleasedAt() == null)
                .forEach(assignment -> assignment.setStatus(MissionDeviceAssignmentStatus.FAILED));
        mission.setStatus(MissionStatus.FAILED);
        if (mission.getActualEndAt() == null) mission.setActualEndAt(Instant.now());
        mission.setFailureReason(effectiveReason);
        if (checklistAssignmentStatusSync != null) checklistAssignmentStatusSync.syncForMission(mission);
        releaseFailedMissionChecklistAssignments(mission, effectiveReason);
        releaseMissionResources(mission, "MISSION_FAILED");
        log.error("Mission {} FAILED – reason: {}", missionId, effectiveReason);
        Mission saved = missionRepository.save(mission);
        return missionMapper.toResponse(saved);
    }

    private void releaseFailedMissionChecklistAssignments(Mission mission, String reason) {
        Instant removedAt = Instant.now();
        String removedBy = authenticatedUserResolver.getCurrentUserId();
        String removalReason = "MISSION_FAILED: " + reason;
        if (removalReason.length() > 1000) removalReason = removalReason.substring(0, 1000);
        var assignments = missionChecklistAssignmentRepository
                .findByMissionIdAndRemovedAtIsNullOrderByDisplayOrderAscIdAsc(mission.getId());
        for (MissionChecklistAssignment assignment : assignments) {
            assignment.setRemovedAt(removedAt);
            assignment.setRemovedBy(removedBy);
            assignment.setRemovalReason(removalReason);
        }
        missionChecklistAssignmentRepository.saveAll(assignments);
    }


    // =========================================================================
    // F3.5 – Post-flight device status update
    // =========================================================================

    @Override
    @Transactional
    @PreAuthorize("@missionAuthorizationService.canMaintainDevice(#missionId)")
    public MissionResponse updatePostFlightStatus(String missionId, DeviceStatus newDeviceStatus, String notes) {
        return savePostFlightStatus(missionId, newDeviceStatus, notes, Map.of());
    }

    @Override
    @Transactional
    @PreAuthorize("@missionAuthorizationService.canMaintainDevice(#missionId)")
    public MissionResponse recordPostFlightInspection(String missionId, DeviceStatus newDeviceStatus,
            String notes, Map<String, InspectionResult> results,
            PostFlightStatusRequest.TelemetrySnapshot telemetrySnapshot) {
        Set<String> required = Set.of("a1", "a2", "p1", "p2", "e1", "e2", "e3", "e4", "d1");
        if (results == null || !results.keySet().equals(required)
                || results.values().stream().anyMatch(value -> value == null)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "All nine inspection items must have a result");
        }
        boolean faultDetected = results.containsValue(InspectionResult.FAIL);
        DeviceStatus expected = faultDetected ? DeviceStatus.MAINTENANCE : DeviceStatus.AVAILABLE;
        if (newDeviceStatus != expected) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Device status does not match the submitted inspection results");
        }
        Mission mission = getRuntimeMissionOrThrow(missionId);
        MissionDeviceAssignment assignment = getCurrentMainDeviceAssignment(mission.getId());
        var savedPostflight = postDeviceCheckRepository
                .findFirstByMissionIdOrderByCreatedAtDesc(mission.getId());
        boolean savedPostflightComplete = savedPostflight
                .map(run -> run.getStatus() == DeviceCheckStatus.PASSED
                        || run.getStatus() == DeviceCheckStatus.FAILED)
                .orElse(false);
        boolean recoveringMissingPostflight =
                assignment.getStatus() == MissionDeviceAssignmentStatus.COMPLETED
                        && mission.getActualEndAt() != null
                        && !savedPostflightComplete;
        if (assignment.getStatus() == MissionDeviceAssignmentStatus.COMPLETED
                && savedPostflightComplete) {
            return missionMapper.toResponse(mission);
        }
        if (assignment.getStatus() != MissionDeviceAssignmentStatus.POSTFLIGHT_CHECKING
                && !recoveringMissingPostflight) {
            throw new ApiException(ErrorCode.MISSION_STATUS_INVALID,
                    "Device must be in postflight inspection before recording results");
        }
        String summary = results.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(", "));
        String auditNotes = "Inspection: " + summary + ". " + (notes == null ? "" : notes.strip());
        return savePostFlightStatus(
                missionId,
                newDeviceStatus,
                auditNotes,
                results,
                telemetrySnapshot,
                !recoveringMissingPostflight);
    }

    private MissionResponse savePostFlightStatus(String missionId, DeviceStatus newDeviceStatus,
            String notes, Map<String, InspectionResult> results) {
        return savePostFlightStatus(missionId, newDeviceStatus, notes, results, null);
    }

    private MissionResponse savePostFlightStatus(String missionId, DeviceStatus newDeviceStatus,
            String notes, Map<String, InspectionResult> results,
            PostFlightStatusRequest.TelemetrySnapshot telemetrySnapshot) {
        return savePostFlightStatus(missionId, newDeviceStatus, notes, results, telemetrySnapshot, true);
    }

    private MissionResponse savePostFlightStatus(String missionId, DeviceStatus newDeviceStatus,
            String notes, Map<String, InspectionResult> results,
            PostFlightStatusRequest.TelemetrySnapshot telemetrySnapshot,
            boolean createMaintenanceTicket) {
        Mission mission = getRuntimeMissionOrThrow(missionId);
        Device device = getCurrentDevice(mission.getId());
        if (device == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Mission " + missionId + " has no assigned device");
        }
        device.setStatus(newDeviceStatus);
        deviceRepository.save(device);

        // Auto-create MaintenanceTicket if device requires maintenance post-flight
        if (newDeviceStatus == DeviceStatus.MAINTENANCE && createMaintenanceTicket) {
            MaintenanceTicket ticket = new MaintenanceTicket();
            ticket.setTicketCode("TKT-POSTFLIGHT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
            ticket.setDevice(device); // device field — device-type agnostic
            ticket.setReportedBy(getCurrentStaffId(mission.getId()));
            ticket.setIssueType("POSTFLIGHT_DAMAGE");
            ticket.setSeverity("HIGH");
            ticket.setDescription("Post-flight physical inspection flagged maintenance needed for device "
                    + device.getDeviceCode() + ". Notes: " + notes);
            ticket.setStatus("OPEN");
            ticket.setOpenedAt(Instant.now());
            maintenanceTicketRepository.save(ticket);
            log.error("[MAINTENANCE] Auto-created MaintenanceTicket {} for device {}", ticket.getTicketCode(),
                    device.getDeviceCode());
        }

        persistedPostDeviceCheckService.recordInspection(mission.getId(), results, telemetrySnapshot);
        MissionDeviceAssignment deviceAssignment = getCurrentMainDeviceAssignment(mission.getId());
        if (deviceAssignment.getStatus() == MissionDeviceAssignmentStatus.POSTFLIGHT_CHECKING) {
            deviceAssignment.setStatus(MissionDeviceAssignmentStatus.COMPLETED);
        }
        // Postflight closes device operations. Keep the Mission IN_PROGRESS through inspector
        // acceptance; submission to the Manager changes it to AWAITING_REVIEW.
        if (mission.getActualEndAt() == null) mission.setActualEndAt(Instant.now());
        // End physical reservations while retaining final crew context for reporting.
        releaseOperationalReservations(mission);

        if (notes != null && !notes.isBlank()) {
            log.info("Mission {} post-flight notes: {}", missionId, notes);
        }
        log.info("Mission {} post-flight completed – device {} status set to {}", missionId, device.getDeviceCode(),
                newDeviceStatus);
        Mission saved = missionRepository.save(mission);
        return missionMapper.toResponse(saved);
    }

    private String generateMissionCode() {
        String code;
        do {
            int number = ThreadLocalRandom.current().nextInt(100000);
            code = String.format("MS-%05d", number);
        } while (missionRepository.existsByMissionCode(code));
        return code;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Mission getMissionByIdOrThrow(String missionId) {
        return missionRepository.findById(missionId)
                .orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND,
                        "Mission not found: " + missionId));
    }

    private Mission getRuntimeMissionOrThrow(String missionId) {
        Mission mission = missionRepository.findById(missionId)
                .orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
        MissionRuntimeGate.requireFinalizedStructure(mission);
        checklistExecutionService.requireMaterialized(mission);
        return mission;
    }

    private Mission getPlanningResourceMission(String missionId) {
        String orderId = missionRepository.findOrderIdByMissionId(missionId)
                .orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
        orderRepository.findById(orderId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        Mission mission = missionRepository.findById(missionId)
                .orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
        MissionRuntimeGate.requireOperationalCommandsAllowed(mission);
        if (mission.getStatus() != MissionStatus.PLANNING) {
            throw new ApiException(ErrorCode.MISSION_STATUS_INVALID,
                    "Resources can only be assigned or removed while Mission is PLANNING");
        }
        return mission;
    }

    private Mission getReplaceableResourceMission(String missionId) {
        Mission mission = getMissionByIdOrThrow(missionId);
        if (Set.of(MissionStatus.PLANNING, MissionStatus.CANCELLED, MissionStatus.FAILED)
                .contains(mission.getStatus())) {
            throw new ApiException(ErrorCode.MISSION_STATUS_INVALID,
                    "Resources cannot be replaced while Mission is PLANNING, CANCELLED, or FAILED");
        }
        return mission;
    }

    private String currentStaffId() {
        User staff = authenticatedUserResolver.getCurrentUser();
        if (staff.getRole() == null || staff.getRole().getCode() != RoleCode.STAFF) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "A staff account is required");
        }
        return staff.getId().toString();
    }

    private void requireStatus(Mission mission, MissionStatus expected) {
        if (mission.getStatus() != expected) {
            throw new ApiException(ErrorCode.MISSION_STATUS_INVALID,
                    "Mission status must be " + expected + " but is " + mission.getStatus());
        }
    }

    private void updateDeviceStatus(Mission mission, DeviceStatus newStatus) {
        Device device = getCurrentDevice(mission.getId());
        if (device != null) {
            device.setStatus(newStatus);
            deviceRepository.save(device);
        }
    }

    private void releaseMissionResources(Mission mission, String reason) {
        Instant releasedAt = Instant.now();
        String missionId = mission.getId();
        resources.release(missionId, reason);
        closeMissionSessions(missionId, reason);

        Device device = getCurrentDevice(mission.getId());
        if (device != null && !"MISSION_COMPLETE".equals(reason)) {
            if (device.getStatus() != DeviceStatus.MAINTENANCE) {
                device.setStatus(DeviceStatus.AVAILABLE);
            }
            deviceRepository.save(device);
        }

        List<MissionDeviceAssignment> deviceAssignments = missionDeviceAssignmentRepository.findByMissionId(missionId);
        for (MissionDeviceAssignment assignment : deviceAssignments) {
            if (!Boolean.TRUE.equals(assignment.getIsCurrent()) || assignment.getReleasedAt() != null) continue;
            if (assignment.getReleasedAt() == null) {
                assignment.setReleasedAt(releasedAt);
            }
            assignment.setReleaseReason(reason);
        }
        if (!deviceAssignments.isEmpty()) {
            missionDeviceAssignmentRepository.saveAll(deviceAssignments);
        }

        List<MissionStaffAssignment> staffAssignments = missionStaffAssignmentRepository.findByMissionId(missionId);
        for (MissionStaffAssignment assignment : staffAssignments) {
            // Preserve replaced/released history; only the final current crew receives MISSION_COMPLETE.
            if (!Boolean.TRUE.equals(assignment.getIsCurrent()) || assignment.getReleasedAt() != null) continue;
            if (assignment.getRespondedAt() == null) {
                assignment.setRespondedAt(releasedAt);
            }
            if (assignment.getReleasedAt() == null) {
                assignment.setReleasedAt(releasedAt);
            }
            assignment.setReleaseReason(reason);
        }
        if (!staffAssignments.isEmpty()) {
            missionStaffAssignmentRepository.saveAll(staffAssignments);
        }

        UserScheduleStatus releasedScheduleStatus = "MISSION_COMPLETE".equals(reason)
                ? UserScheduleStatus.COMPLETED
                : UserScheduleStatus.CANCELLED;
        List<UserSchedule> schedules = userScheduleRepository.findByReferenceId(missionId);
        for (UserSchedule schedule : schedules) {
            if (schedule.getScheduleType() == UserScheduleType.MISSION
                    && schedule.getStatus() != UserScheduleStatus.COMPLETED
                    && schedule.getStatus() != UserScheduleStatus.CANCELLED) {
                schedule.setStatus(releasedScheduleStatus);
                schedule.setNotes(appendReleaseNote(schedule.getNotes(), reason));
            }
        }
        if (!schedules.isEmpty()) {
            userScheduleRepository.saveAll(schedules);
        }
    }

    private void releaseOperationalReservations(Mission mission) {
        String missionId = mission.getId();
        resources.release(missionId, "POSTFLIGHT_COMPLETE");
        closeMissionSessions(missionId, "POSTFLIGHT_COMPLETE");
        // Do not change isCurrent/releasedAt: final assignments still authorize evidence/report work.
        var schedules = userScheduleRepository.findByReferenceId(missionId);
        for (var schedule : schedules) {
            if (schedule.getScheduleType() == UserScheduleType.MISSION && schedule.getStatus() != UserScheduleStatus.CANCELLED) {
                schedule.setStatus(UserScheduleStatus.COMPLETED);
                schedule.setNotes(appendReleaseNote(schedule.getNotes(), "POSTFLIGHT_COMPLETE"));
            }
        }
        userScheduleRepository.saveAll(schedules);
    }

    private String appendReleaseNote(String notes, String reason) {
        String releaseNote = "Released: " + reason;
        if (notes == null || notes.isBlank()) {
            return releaseNote;
        }
        if (notes.contains(releaseNote)) {
            return notes;
        }
        return notes + " | " + releaseNote;
    }

    private void closeMissionSessions(String missionId, String reason) {
        var connections = deviceConnectionRepository.findByMissionId(missionId);
        for (var session : connections) if ("CONNECTED".equals(session.getConnectionStatus()) || Boolean.TRUE.equals(session.getTelemetryActive())) {
            session.setConnectionStatus("DISCONNECTED"); session.setTelemetryActive(false);
            if (session.getDisconnectedAt() == null) session.setDisconnectedAt(Instant.now());
            session.setDisconnectReason(reason);
        }
        deviceConnectionRepository.saveAll(connections);
    }

    private Device getCurrentDevice(String missionId) {
        return missionDeviceAssignmentRepository.findFirstByMissionIdOrderByCreatedAtDesc(missionId)
                .map(MissionDeviceAssignment::getDevice)
                .orElse(null);
    }

    private MissionDeviceAssignment getCurrentMainDeviceAssignment(String missionId) {
        return missionDeviceAssignmentRepository.findAllByMissionIdAndIsCurrentTrueOrderByCreatedAtDesc(missionId)
                .stream().filter(a -> a.getReleasedAt() == null && a.getDeviceRole() == DeviceRole.MAIN)
                .findFirst().orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Mission has no current main device assignment"));
    }

    private String getCurrentStaffId(String missionId) {
        return missionStaffAssignmentRepository.findFirstByMissionIdOrderByAssignedAtDesc(missionId)
                .map(assignment -> assignment.getStaff() != null && assignment.getStaff().getId() != null
                        ? assignment.getStaff().getId().toString()
                        : null)
                .orElse(null);
    }

}
