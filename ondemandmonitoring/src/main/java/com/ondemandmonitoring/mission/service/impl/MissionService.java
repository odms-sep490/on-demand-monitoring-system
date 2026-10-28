package com.ondemandmonitoring.mission.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.api.PageResponse;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.delivery.service.IDeliveryWorkflowService;
import com.ondemandmonitoring.device.domain.Device;
import com.ondemandmonitoring.device.enums.DeviceStatus;
import com.ondemandmonitoring.device.repository.DeviceRepository;
import com.ondemandmonitoring.devicecheck.dto.response.PreDeviceCheckResponse;
import com.ondemandmonitoring.devicecheck.repository.PersistedPostDeviceCheckRepository;
import com.ondemandmonitoring.devicecheck.repository.PersistedPreDeviceCheckRepository;
import com.ondemandmonitoring.devicecheck.enums.PreDeviceCheckStatus;
import com.ondemandmonitoring.devicecheck.enums.PreDeviceItemStatus;
import com.ondemandmonitoring.devicecheck.service.IPreDeviceCheckCompletionService;
import com.ondemandmonitoring.devicecheck.service.IPersistedPostDeviceCheckService;
import com.ondemandmonitoring.mission.domain.ControlHandover;
import com.ondemandmonitoring.mission.domain.DeviceConnection;
import com.ondemandmonitoring.mission.domain.FlightToken;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.domain.MissionPlan;
import com.ondemandmonitoring.mission.dto.request.*;
import com.ondemandmonitoring.mission.dto.response.MissionPlanResponse;
import com.ondemandmonitoring.mission.dto.response.MissionResponse;
import com.ondemandmonitoring.order.enums.ExecutionPlanStatus;
import com.ondemandmonitoring.mission.dto.response.MissionTelemetryReadinessResponse;
import com.ondemandmonitoring.mission.enums.*;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.enums.OrderStatus;
import com.ondemandmonitoring.mission.repository.ControlHandoverRepository;
import com.ondemandmonitoring.mission.repository.DeviceConnectionRepository;
import com.ondemandmonitoring.mission.repository.FlightTokenRepository;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.mission.mapper.FlightTokenMapper;
import com.ondemandmonitoring.mission.mapper.MissionMapper;
import com.ondemandmonitoring.mission.service.IDeviceConnectionService;
import com.ondemandmonitoring.mission.service.IFlightTokenService;
import com.ondemandmonitoring.mission.service.IMissionResultService;
import com.ondemandmonitoring.mission.service.IMissionChecklistExecutionService;
import com.ondemandmonitoring.mission.service.IMissionChecklistMaterializationService;
import com.ondemandmonitoring.mission.service.IMissionService;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.finance.enums.QuoteStatus;
import com.ondemandmonitoring.finance.repository.InvoiceRepository;
import com.ondemandmonitoring.finance.service.IMissionPaymentEligibilityService;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.ondemandmonitoring.device.domain.MaintenanceTicket;
import com.ondemandmonitoring.device.repository.MaintenanceTicketRepository;
import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.repository.*;

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
    FlightTokenRepository flightTokenRepository;
    MissionMapper missionMapper;
    FlightTokenMapper flightTokenMapper;

    // Supporting audit & work order repositories
    MissionPlanRepository missionPlanRepository;
    DeviceConnectionRepository deviceConnectionRepository;
    ControlHandoverRepository controlHandoverRepository;
    PersistedPostDeviceCheckRepository postDeviceCheckRepository;
    @Autowired @NonFinal
    PersistedPreDeviceCheckRepository preDeviceCheckRepository;
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
    IMissionChecklistExecutionService checklistExecutionService;
    @Autowired @NonFinal
    IMissionChecklistMaterializationService checklistMaterializationService;
    com.ondemandmonitoring.mission.service.IMissionChecklistAssignmentService checklistAssignmentService;
    com.ondemandmonitoring.mission.service.IResourceReservationService resources;
    com.ondemandmonitoring.device.repository.DeviceTelemetryRepository telemetryRepository;
    @Autowired @NonFinal
    IDeliveryWorkflowService deliveryWorkflowService;
    @Autowired(required = false) @NonFinal
    ChecklistAssignmentStatusSync checklistAssignmentStatusSync;
    @Autowired @NonFinal
    com.ondemandmonitoring.order.repository.OrderChecklistItemRepository orderChecklistItemRepository;
    @Autowired @NonFinal
    MissionChecklistExecutionRepository missionChecklistExecutionRepository;
    // =========================================================================
    // Query Methods
    // =========================================================================

    @Override
    @Transactional
    @PreAuthorize("@missionAuthorizationService.canManageMissions()")
    public MissionResponse updateFlightPermit(String missionId, FlightPermitUpdateRequest request) {
        Mission mission = getMissionByIdOrThrow(missionId);
        boolean hasDevice = missionDeviceAssignmentRepository
                .findAllByMissionIdAndIsCurrentTrueOrderByCreatedAtDesc(mission.getId()).stream()
                .anyMatch(a -> a.getReleasedAt() == null && a.getDevice() != null);
        boolean hasStaff = missionStaffAssignmentRepository
                .findAllByMissionIdAndIsCurrentTrue(mission.getId()).stream()
                .anyMatch(a -> a.getReleasedAt() == null);
        if (!hasDevice || !hasStaff) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Assign a drone and staff before updating the flight permit");
        }
        if (request.dataUrl() != null && request.dataUrl().length() > 7_000_000) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Flight permit file must not exceed 5 MB");
        }
        mission.setFlightPermitStatus(request.status());
        mission.setFlightPermitNote(blankToNull(request.note()));
        if (request.dataUrl() != null && !request.dataUrl().isBlank()) {
            mission.setFlightPermitFileName(request.fileName());
            mission.setFlightPermitContentType(request.contentType());
            mission.setFlightPermitDataUrl(request.dataUrl());
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
    
    private MissionResponse createMissionInternal(String orderId, MissionCreateRequest request, boolean additional) {
        String scopeLabel = Normalizer.normalize(
                request.getScopeLabel().strip().replaceAll("\\s+", " "), Normalizer.Form.NFC);
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found: " + orderId));
        if (additional) {
            if (order.getExecutionPlanStatus() != ExecutionPlanStatus.FINALIZED)
                throw new ApiException(ErrorCode.EXECUTION_PLAN_NOT_FINALIZED);
            if (order.getOrderStatus() != OrderStatus.APPROVED && order.getOrderStatus() != OrderStatus.IN_PROGRESS)
                throw new ApiException(ErrorCode.INVALID_REQUEST, "Order must be APPROVED or IN_PROGRESS");
        } else if (order.getExecutionPlanStatus() != ExecutionPlanStatus.DRAFT) {
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
        Mission mission = new Mission();
        mission.setMissionCode(generateMissionCode());
        mission.setStatus(MissionStatus.PLANNING);
        mission.setOrder(order);
        mission.setScheduledStartAt(request.getScheduledStartAt()); mission.setScheduledEndAt(request.getScheduledEndAt());
        mission.setScopeLabel(scopeLabel);
        Mission saved = missionRepository.saveAndFlush(mission);
        assignChecklist(saved, order, request.getChecklistIds());
        // Planning creation never materializes executions; the canonical plan finalizer owns that boundary.
        return missionMapper.toResponse(saved);
    }

    @Override
    @Transactional
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public MissionResponse confirmMissionPlanning(String missionId) {
        var order = getMissionOrder(missionId);
        var mission = getMissionForOrder(missionId, order);
        if (order.getExecutionPlanStatus() != ExecutionPlanStatus.FINALIZED)
            throw new ApiException(ErrorCode.EXECUTION_PLAN_NOT_FINALIZED);
        requireOpenOrder(order);
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
        mission.setStatus(MissionStatus.READY);
        mission.setResourcePrepareStatus(MissionResourcePrepareStatus.PENDING);
        missionStaffAssignmentRepository.findByMissionId(missionId).forEach(a -> {
            if (a.getReleasedAt() == null) { a.setResponseStatus(MissionStaffAssignmentStatus.PENDING); a.setIsCurrent(true); }
        });
        missionDeviceAssignmentRepository.findByMissionId(missionId).forEach(a -> {
            if (a.getReleasedAt() == null) { a.setCheckupStatus(MissionDeviceAssignmentStatus.PENDING); a.setIsCurrent(true); }
        });
        order.setExecutionPlanRevision(order.getExecutionPlanRevision() + 1);
        orderRepository.saveAndFlush(order);
        return missionMapper.toResponse(missionRepository.saveAndFlush(mission));
    }

    @Override
    @Transactional
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public void deleteDraftMission(String missionId) {
        var order = getMissionOrder(missionId);
        var mission = getMissionForOrder(missionId, order);
        if (order.getExecutionPlanStatus() != ExecutionPlanStatus.DRAFT)
            throw new ApiException(ErrorCode.EXECUTION_PLAN_NOT_DRAFT);
        if (mission.getStatus() != MissionStatus.PLANNING || mission.getActualStartAt() != null
                || !missionChecklistExecutionRepository.findOrderedByMissionId(missionId).isEmpty())
            throw new ApiException(ErrorCode.MISSION_STATUS_INVALID, "Only unconfirmed planning Missions can be deleted");
        requireRevisionAvailable(order);
        var crew = missionStaffAssignmentRepository.findByMissionId(missionId);
        var equipment = missionDeviceAssignmentRepository.findByMissionId(missionId);
        var selection = missionChecklistAssignmentRepository.findByMissionIdAndRemovedAtIsNullOrderByDisplayOrderAscIdAsc(missionId);
        var otherOwners = new java.util.HashSet<String>();
        missionChecklistAssignmentRepository.findActiveByOrderId(order.getId()).stream()
                .filter(a -> !missionId.equals(a.getMissionId()) && ExecutionPlanCoverageService.effective(a.getMission()))
                .forEach(a -> otherOwners.add(a.getOrderChecklistItemId()));
        selection.stream().filter(a -> !otherOwners.contains(a.getOrderChecklistItemId()))
                .forEach(a -> a.getOrderChecklistItem().setAssignmentStatus(com.ondemandmonitoring.order.enums.ChecklistAssignmentStatus.UNASSIGNED));
        orderChecklistItemRepository.saveAll(selection.stream().map(a -> a.getOrderChecklistItem()).toList());
        // Delete children before the Mission because the schema deliberately uses restrictive FKs.
        missionChecklistAssignmentRepository.deleteAll(missionChecklistAssignmentRepository.findByMissionId(missionId)); missionChecklistAssignmentRepository.flush();
        userScheduleRepository.deleteAll(userScheduleRepository.findByReferenceId(missionId).stream()
                .filter(s -> s.getScheduleType() == UserScheduleType.MISSION).toList()); userScheduleRepository.flush();
        resourceTimeLockRepository.deleteAll(resourceTimeLockRepository.findByMissionId(missionId)); resourceTimeLockRepository.flush();
        missionStaffAssignmentRepository.deleteAll(crew); missionStaffAssignmentRepository.flush();
        missionDeviceAssignmentRepository.deleteAll(equipment); missionDeviceAssignmentRepository.flush();
        missionRepository.delete(mission); missionRepository.flush();
        order.setExecutionPlanRevision(order.getExecutionPlanRevision() + 1);
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
        if (order.getExecutionPlanRevision() == Long.MAX_VALUE) throw new ApiException(ErrorCode.EXECUTION_PLAN_REVISION_CONFLICT);
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
                .filter(a -> a.getReleasedAt() == null && a.getStaff() != null)
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
                        order.getExecutionPlanRevision(), items));
    }

    private void validatePlanningSchedule(Instant start, Instant end, Order order) {
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
        return missionRepository.findByStatusIn(List.of(MissionStatus.READY))
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
        String deviceId = request.getDeviceId();
        DeviceRole deviceRole = request.getDeviceRole();
        Mission mission = getPlanningResourceMission(missionId);
        String resolvedMissionId = mission.getId();
        if (mission.getStatus() != MissionStatus.PLANNING && mission.getStatus() != MissionStatus.READY) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Mission status must be PLANNING or READY to assign a device.");
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

        DeviceRole role = (deviceRole != null) ? deviceRole : DeviceRole.MAIN;
        resources.reserve(mission, deviceId, false);

        Instant now = Instant.now();
        var currentSameDevice = missionDeviceAssignmentRepository
                .findAllByMissionIdAndDeviceIdAndIsCurrentTrueOrderByCreatedAtDesc(resolvedMissionId, deviceId);
        boolean alreadyAssigned = mission.getStatus() == MissionStatus.PLANNING
                ? missionDeviceAssignmentRepository.findByMissionId(resolvedMissionId).stream()
                    .anyMatch(a -> a.getReleasedAt() == null && a.getDevice().getId().equals(deviceId))
                : !currentSameDevice.isEmpty();
        if (alreadyAssigned) {
            if (hasRequiredCrewAssigned(resolvedMissionId) && mission.getStatus() == MissionStatus.READY) {
                mission.setStatus(MissionStatus.READY);
            }
            return missionMapper.toResponse(missionRepository.save(mission));
        }

        if (role == DeviceRole.MAIN) {
            List<MissionDeviceAssignment> currentMainAssignments = mission.getStatus() == MissionStatus.PLANNING
                    ? missionDeviceAssignmentRepository.findByMissionId(resolvedMissionId).stream()
                        .filter(a -> a.getReleasedAt() == null && a.getDeviceRole() == DeviceRole.MAIN).toList()
                    : missionDeviceAssignmentRepository.findAllByMissionIdAndDeviceRoleAndIsCurrentTrueOrderByCreatedAtDesc(
                            resolvedMissionId, DeviceRole.MAIN);
            for (MissionDeviceAssignment assignment : currentMainAssignments) {
                if (mission.getStatus() != MissionStatus.PLANNING) {
                    assignment.setIsCurrent(false);
                    assignment.setReleasedAt(now);
                    assignment.setReleaseReason("Replaced by device assignment " + deviceId);
                }
                resources.releaseResource(resolvedMissionId, assignment.getDevice().getId(), "DEVICE_REPLACED");
            }
            if (!currentMainAssignments.isEmpty() && mission.getStatus() == MissionStatus.PLANNING) {
                missionDeviceAssignmentRepository.deleteAll(currentMainAssignments);
                missionDeviceAssignmentRepository.flush();
            } else if (!currentMainAssignments.isEmpty()) {
                // Release the unique current-MAIN slot before Hibernate queues the new INSERT.
                missionDeviceAssignmentRepository.saveAllAndFlush(currentMainAssignments);
            }
        }

        MissionDeviceAssignment newAssignment = MissionDeviceAssignment.builder()
                .mission(mission)
                .device(device)
                .deviceRole(role)
                .isCurrent(mission.getStatus() != MissionStatus.PLANNING)
                .checkupStatus(mission.getStatus() == MissionStatus.PLANNING
                        ? MissionDeviceAssignmentStatus.PLANNING : MissionDeviceAssignmentStatus.PENDING)
                .postcheckStatus(null)
                .verifiedBy(null)
                .verifiedAt(null)
                .failureNotes(null)
                .assignedAt(now)
                .build();

        missionDeviceAssignmentRepository.save(newAssignment);
        if (hasRequiredCrewAssigned(resolvedMissionId) && mission.getStatus() == MissionStatus.READY) {
            mission.setStatus(MissionStatus.READY);
        }
        refreshResourcePrepareStatus(mission);
        Mission saved = missionRepository.save(mission);
        return missionMapper.toResponse(saved);
    }

    @Override
    @Transactional
    @PreAuthorize("@missionAuthorizationService.canManageMissions()")
    public MissionResponse removeDevice(String missionId, com.ondemandmonitoring.mission.dto.request.RemoveDeviceRequest request) {
        if (request == null || request.getDeviceId() == null || request.getDeviceId().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "deviceId is required");
        }
        Mission mission = getPlanningResourceMission(missionId);
        String resolvedMissionId = mission.getId();

        if (mission.getStatus() != MissionStatus.PLANNING && mission.getStatus() != MissionStatus.READY) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Cannot remove device in current mission status");
        }

        Instant now = Instant.now();
        List<MissionDeviceAssignment> currentAssignments = mission.getStatus() == MissionStatus.PLANNING
                ? missionDeviceAssignmentRepository.findByMissionId(resolvedMissionId).stream()
                    .filter(a -> request.getDeviceId().equals(a.getDevice().getId()) && a.getReleasedAt() == null).toList()
                : missionDeviceAssignmentRepository.findAllByMissionIdAndDeviceIdAndIsCurrentTrueOrderByCreatedAtDesc(
                        resolvedMissionId, request.getDeviceId());

        if (!currentAssignments.isEmpty()) {
            String reason = request.getReason() != null && !request.getReason().isBlank()
                    ? request.getReason().trim()
                    : "Removed by manager";
            for (MissionDeviceAssignment a : currentAssignments) {
                if (mission.getStatus() == MissionStatus.PLANNING) {
                    resources.releaseResource(resolvedMissionId, a.getDevice().getId(), reason);
                } else {
                    a.setIsCurrent(false);
                    a.setReleasedAt(now);
                    a.setReleaseReason(reason);
                }
            }
            if (mission.getStatus() == MissionStatus.PLANNING) missionDeviceAssignmentRepository.deleteAll(currentAssignments);
            else {
                missionDeviceAssignmentRepository.saveAllAndFlush(currentAssignments);
                boolean deviceStillAssigned = missionDeviceAssignmentRepository.findByMissionId(resolvedMissionId).stream()
                        .anyMatch(a -> request.getDeviceId().equals(a.getDevice().getId())
                                && Boolean.TRUE.equals(a.getIsCurrent()) && a.getReleasedAt() == null);
                if (!deviceStillAssigned)
                    resources.releaseResource(resolvedMissionId, request.getDeviceId(), reason);
            }
        }

        Mission saved = missionRepository.save(mission);
        return missionMapper.toResponse(saved);
    }


    @Override
    @Transactional
    @PreAuthorize("@missionAuthorizationService.canManageMissions()")
    public MissionResponse assignStaff(String missionId, AssignStaffRequest request) {
        String staffId = request.getStaffId();
        MissionStaffRole assignedRole = resolveStaffRole(request.getAssignedRole());

        Mission mission = getPlanningResourceMission(missionId);
        String resolvedMissionId = mission.getId();
        if (mission.getStatus() != MissionStatus.PLANNING && mission.getStatus() != MissionStatus.READY) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Mission status must be PLANNING or READY to assign staff.");
        }

        User staff = staffDirectory.requireActiveStaff(staffId);

        Instant missionStart = mission.getScheduledStartAt();
        Instant missionEnd = mission.getScheduledEndAt();

        if (missionStart == null || missionEnd == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Mission schedule start and end times must be set before assigning staff.");
        }

        resources.reserve(mission, staffId, true);

        if (missionStaffAssignmentRepository.findByMissionId(resolvedMissionId).stream()
                .anyMatch(a -> a.getReleasedAt() == null && a.getStaff().getId().equals(staffId)
                        && a.getAssignedRole() == assignedRole)) {
            return missionMapper.toResponse(missionRepository.save(mission));
        }

        boolean confirmed = mission.getStatus() != MissionStatus.PLANNING;
        MissionStaffAssignment assignment = MissionStaffAssignment.builder()
                .mission(mission)
                .staff(staff)
                .assignedRole(assignedRole)
                .responseStatus(confirmed ? MissionStaffAssignmentStatus.PENDING : MissionStaffAssignmentStatus.PLANNING)
                .isCurrent(confirmed)
                .assignedAt(Instant.now())
                .build();
        missionStaffAssignmentRepository.save(assignment);

        UserSchedule staffSchedule = UserSchedule.builder()
                .staff(staff)
                .startTime(missionStart)
                .endTime(missionEnd)
                .scheduleType(UserScheduleType.MISSION)
                .referenceId(mission.getId())
                .status(UserScheduleStatus.SCHEDULED)
                .notes("Assigned to mission " + mission.getMissionCode() + " as " + assignedRole)
                .build();
        userScheduleRepository.save(staffSchedule);

        Mission saved = missionRepository.save(mission);
        refreshResourcePrepareStatus(saved);
        return missionMapper.toResponse(saved);
    }

    @Override
    @Transactional
    @PreAuthorize("@missionAuthorizationService.canManageMissions()")
    public MissionResponse removeStaff(String missionId, com.ondemandmonitoring.mission.dto.request.RemoveStaffRequest request) {
        if (request == null || request.getStaffId() == null || request.getStaffId().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "staffId is required");
        }
        Mission mission = getPlanningResourceMission(missionId);
        String resolvedMissionId = mission.getId();

        if (mission.getStatus() != MissionStatus.PLANNING && mission.getStatus() != MissionStatus.READY) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Cannot remove staff in current mission status");
        }

        Instant now = Instant.now();
        List<MissionStaffAssignment> currentAssignments;
        if (mission.getStatus() == MissionStatus.PLANNING) {
            currentAssignments = missionStaffAssignmentRepository.findByMissionId(resolvedMissionId).stream()
                    .filter(a -> request.getStaffId().equals(a.getStaff().getId()) && a.getReleasedAt() == null)
                    .filter(a -> request.getAssignedRole() == null || request.getAssignedRole().isBlank()
                            || a.getAssignedRole() == resolveStaffRole(request.getAssignedRole()))
                    .toList();
            if (!currentAssignments.isEmpty()) {
                missionStaffAssignmentRepository.deleteAll(currentAssignments);
                boolean staffStillAssigned = missionStaffAssignmentRepository.findByMissionId(resolvedMissionId).stream()
                        .anyMatch(a -> request.getStaffId().equals(a.getStaff().getId()) && a.getReleasedAt() == null);
                if (!staffStillAssigned) {
                    resources.releaseResource(resolvedMissionId, request.getStaffId(), "Removed while planning");
                    userScheduleRepository.findByReferenceId(resolvedMissionId).stream()
                            .filter(s -> s.getStaff() != null && request.getStaffId().equals(s.getStaff().getId()))
                            .forEach(s -> s.setStatus(UserScheduleStatus.CANCELLED));
                }
            }
            refreshResourcePrepareStatus(mission);
            return missionMapper.toResponse(missionRepository.save(mission));
        }
        if (request.getAssignedRole() != null && !request.getAssignedRole().isBlank()) {
            MissionStaffRole role = resolveStaffRole(request.getAssignedRole());
            currentAssignments = missionStaffAssignmentRepository
                    .findAllByMissionIdAndStaffIdAndAssignedRoleAndIsCurrentTrueOrderByAssignedAtDesc(
                            resolvedMissionId, request.getStaffId(), role);
        } else {
            currentAssignments = missionStaffAssignmentRepository
                    .findAllByMissionIdAndStaffIdAndIsCurrentTrueOrderByAssignedAtDesc(
                            resolvedMissionId, request.getStaffId());
        }

        if (!currentAssignments.isEmpty()) {
            String reason = request.getReason() != null && !request.getReason().isBlank()
                    ? request.getReason().trim()
                    : "Removed by manager";
            for (MissionStaffAssignment a : currentAssignments) {
                a.setIsCurrent(false);
                a.setReleasedAt(now);
                a.setReleaseReason(reason);
            }
            missionStaffAssignmentRepository.saveAllAndFlush(currentAssignments);
            boolean staffStillAssigned = missionStaffAssignmentRepository.findByMissionId(resolvedMissionId).stream()
                    .anyMatch(a -> request.getStaffId().equals(a.getStaff().getId())
                            && Boolean.TRUE.equals(a.getIsCurrent()) && a.getReleasedAt() == null);
            if (!staffStillAssigned) resources.releaseResource(resolvedMissionId, request.getStaffId(), reason);

            // Clean up user schedule
            userScheduleRepository.findByReferenceId(mission.getId()).stream()
                    .filter(s -> s.getStaff() != null && request.getStaffId().equals(s.getStaff().getId()))
                    .forEach(s -> s.setStatus(UserScheduleStatus.CANCELLED));
        }

        refreshResourcePrepareStatus(mission);
        Mission saved = missionRepository.save(mission);
        return missionMapper.toResponse(saved);
    }
    @Override
    @Transactional
    @PreAuthorize("@missionAuthorizationService.canManageMissions()")
    public MissionResponse updateMission(String missionId, MissionUpdateRequest request) {
        String orderId = missionRepository.findOrderIdByMissionId(missionId)
                .orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
        Order order = orderRepository.findById(orderId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        Mission mission = missionRepository.findById(missionId).orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
        if (!orderId.equals(mission.getOrder().getId())) throw new ApiException(ErrorCode.CHECKLIST_ASSIGNMENT_CROSS_ORDER);
        if ((order.getExecutionPlanStatus() != ExecutionPlanStatus.DRAFT
                && order.getExecutionPlanStatus() != ExecutionPlanStatus.FINALIZED)
                || mission.getStatus() != MissionStatus.PLANNING)
            throw new ApiException(ErrorCode.MISSION_STATUS_INVALID, "Only planning Missions can be updated");
        if (order.getOrderStatus() != OrderStatus.APPROVED && order.getOrderStatus() != OrderStatus.IN_PROGRESS)
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        if (request == null) throw new ApiException(ErrorCode.INVALID_REQUEST);

        Instant previousStart = mission.getScheduledStartAt();
        Instant previousEnd = mission.getScheduledEndAt();

        Instant newStart = request.getScheduledStartAt() != null ? request.getScheduledStartAt() : previousStart;
        Instant newEnd = request.getScheduledEndAt() != null ? request.getScheduledEndAt() : previousEnd;

        validateScheduledDates(newStart, newEnd, mission.getOrder());
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

        mission.setStatus(MissionStatus.READY);
        refreshResourcePrepareStatus(mission);
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
        refreshResourcePrepareStatus(mission);
        return missionMapper.toResponse(missionRepository.save(mission));
    }

    @Override
    @Transactional
    @PreAuthorize("@missionAuthorizationService.canStartMission(#missionId)")
    public MissionResponse startMissionWorkflow(String missionId) {
        Mission mission = getRuntimeMissionOrThrow(missionId);
        requireFlightPermitApproved(mission);
        requireCrewConfirmationStatus(mission);
        if (!isRequiredCrewAccepted(mission.getId())) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "All required crew members must accept before the mission can start");
        }
        mission.setStatus(MissionStatus.SCHEDULED);
        if (mission.getActualStartAt() == null) {
            mission.setActualStartAt(Instant.now());
        }
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
                || mission.getSafeStopRequestedAt() != null
                || !Set.of(MissionStatus.SCHEDULED, MissionStatus.CONNECTED, MissionStatus.PREFLIGHT_CHECKING,
                        MissionStatus.READY_TO_FLY, MissionStatus.IN_FLIGHT, MissionStatus.IN_PROGRESS).contains(mission.getStatus())) {
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
        missionDeviceAssignmentRepository.findAllByMissionIdAndDeviceIdAndIsCurrentTrueOrderByCreatedAtDesc(missionId, deviceId)
                .forEach(assignment -> assignment.setCheckupStatus(MissionDeviceAssignmentStatus.CHECKING));
        PreDeviceCheckResponse result = preDeviceCheckCompletionService.complete(missionId, deviceId);
        missionDeviceAssignmentRepository.findAllByMissionIdAndDeviceIdAndIsCurrentTrueOrderByCreatedAtDesc(missionId, deviceId)
                .forEach(assignment -> assignment.setCheckupStatus(Boolean.TRUE.equals(result.getOverallPassed())
                        ? MissionDeviceAssignmentStatus.PASSED : MissionDeviceAssignmentStatus.FAILED));
        missionRepository.findById(missionId).ifPresent(this::refreshResourcePrepareStatus);
        return result;
    }




    private FlightToken issueFlightToken(String missionId, String deviceId, String staffId) {
        return flightTokenService.issueFlightToken(missionId, deviceId, staffId);
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
        if (mission.getStatus() != MissionStatus.READY
                && mission.getStatus() != MissionStatus.WAITING_OPERATOR_ACCEPTANCE) {
            throw new ApiException(ErrorCode.MISSION_STATUS_INVALID,
                    "Mission must be waiting for crew confirmation, current: " + mission.getStatus());
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

    private void refreshResourcePrepareStatus(Mission mission) {
        if (mission.getStatus() == MissionStatus.CANCELLED) {
            mission.setResourcePrepareStatus(MissionResourcePrepareStatus.CANCELLED);
            return;
        }
        if (mission.getStatus() == MissionStatus.PLANNING) {
            mission.setResourcePrepareStatus(MissionResourcePrepareStatus.PLANNING);
            return;
        }
        List<MissionStaffAssignment> crew = missionStaffAssignmentRepository.findByMissionId(mission.getId()).stream()
                .filter(a -> Boolean.TRUE.equals(a.getIsCurrent()) && a.getReleasedAt() == null).toList();
        List<MissionDeviceAssignment> devices = missionDeviceAssignmentRepository.findByMissionId(mission.getId()).stream()
                .filter(a -> Boolean.TRUE.equals(a.getIsCurrent()) && a.getReleasedAt() == null).toList();
        Set<MissionStaffRole> acceptedRoles = crew.stream()
                .filter(a -> a.getResponseStatus() == MissionStaffAssignmentStatus.ACCEPTED)
                .map(MissionStaffAssignment::getAssignedRole).collect(java.util.stream.Collectors.toSet());
        boolean ready = acceptedRoles.containsAll(REQUIRED_CREW_ROLES)
                && crew.stream().allMatch(a -> a.getResponseStatus() == MissionStaffAssignmentStatus.ACCEPTED)
                && !devices.isEmpty()
                && devices.stream().allMatch(a -> a.getCheckupStatus() == MissionDeviceAssignmentStatus.PASSED);
        mission.setResourcePrepareStatus(ready
                ? MissionResourcePrepareStatus.READY : MissionResourcePrepareStatus.PENDING);
    }

    private boolean isRequiredCrewAccepted(String missionId) {
        List<MissionStaffAssignment> assignments =
                missionStaffAssignmentRepository.findAllByMissionIdAndIsCurrentTrue(missionId).stream()
                        .filter(this::isUsablePlanningCrew).toList();
        Set<MissionStaffRole> acceptedRoles = assignments.stream()
                .filter(assignment -> assignment.getResponseStatus() == MissionStaffAssignmentStatus.ACCEPTED)
                .map(MissionStaffAssignment::getAssignedRole)
                .collect(java.util.stream.Collectors.toSet());
        return acceptedRoles.containsAll(REQUIRED_CREW_ROLES)
                && assignments.stream().allMatch(entry -> entry.getResponseStatus() == MissionStaffAssignmentStatus.ACCEPTED);
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

    private void ensureScheduledStart(Mission mission) {
        if (mission.getScheduledStartAt() != null || mission.getOrder() == null) {
            return;
        }

        LocalDate date = mission.getOrder().getPreferredDateFrom();
        LocalTime time = mission.getOrder().getPreferredTime() != null
                ? mission.getOrder().getPreferredTime().getStartTime()
                : null;
        if (date == null || time == null) {
            return;
        }

        mission.setScheduledStartAt(date.atTime(time)
                .atZone(ZoneId.of("Asia/Ho_Chi_Minh"))
                .toInstant());
    }


    @Override
    @Transactional
    @PreAuthorize("(@missionAuthorizationService.canOperatePayload(#missionId) or @missionAuthorizationService.canControlFlight(#missionId)) and @missionAuthorizationService.canViewStaffMissions(#staffId)")
    public MissionResponse handoverControl(String missionId, String staffId) {
        Mission mission = getRuntimeMissionOrThrow(missionId);
        MissionRuntimeGate.requireOperationalCommandsAllowed(mission);
        String resolvedMissionId = mission.getId();
        requireStatus(mission, MissionStatus.READY_TO_FLY);
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

        log.info("Mission {} – control handed over to Staff {} at {}", missionId, staffId, Instant.now());
        Mission saved = missionRepository.save(mission);
        return missionMapper.toResponse(saved);
    }

    // =========================================================================
    // F3.3 – Takeoff & Execution lifecycle
    // =========================================================================

    @Override
    @Transactional
    @PreAuthorize("@missionAuthorizationService.canControlFlight(#missionId)")
    public MissionResponse startMission(String missionId, String tokenValue) {
        Mission mission = getRuntimeMissionOrThrow(missionId);
        MissionRuntimeGate.requireOperationalCommandsAllowed(mission);
        requireFlightPermitApproved(mission);
        String resolvedMissionId = mission.getId();
        requireStatus(mission, MissionStatus.READY_TO_FLY);

        if (tokenValue != null && !tokenValue.isBlank()) {
            FlightToken token = flightTokenRepository.findByTokenValue(tokenValue)
                    .orElseThrow(() -> new ApiException(ErrorCode.INVALID_REQUEST, "Invalid flight access token"));

            if (!resolvedMissionId.equals(token.getMissionId())) {
                throw new ApiException(ErrorCode.ACCESS_DENIED, "Flight token belongs to another mission");
            }

            if (!token.isValid()) {
                token.setRevoked(true);
                flightTokenRepository.save(token);
                log.warn("Mission {} flight token EXPIRED or ALREADY USED – revoking token", missionId);
                throw new ApiException(ErrorCode.INVALID_REQUEST,
                        "Flight token is expired or revoked. Please re-run pre-device check.");
            }
            token.setUsed(true);
            flightTokenRepository.save(token);
        } else {
            FlightToken token = flightTokenRepository
                    .findAllByMissionIdAndUsedFalseAndRevokedFalseOrderByIssuedAtDesc(resolvedMissionId)
                    .stream()
                    .findFirst()
                    .orElse(null);
            if (token != null) {
                if (!token.isValid()) {
                    token.setRevoked(true);
                    flightTokenRepository.save(token);
                    throw new ApiException(ErrorCode.INVALID_REQUEST,
                            "Flight token is expired. Please re-run pre-device check.");
                }
                token.setUsed(true);
                flightTokenRepository.save(token);
            }
        }

        mission.setStatus(MissionStatus.IN_FLIGHT);
        if (mission.getActualStartAt() == null) {
            mission.setActualStartAt(Instant.now());
        }
        updateDeviceStatus(mission, DeviceStatus.ACTIVE_MISSION);
        if (checklistAssignmentStatusSync != null) checklistAssignmentStatusSync.syncForMission(mission);

        log.info("Mission {} IN_FLIGHT – WebSocket telemetry and RTSP video stream OPENED", missionId);
        Mission saved = missionRepository.save(mission);
        return missionMapper.toResponse(saved);
    }

    @Override
    @Transactional
    @PreAuthorize("@missionAuthorizationService.canControlFlight(#missionId)")
    public MissionResponse startMission(String missionId) {
        return startMission(missionId, null);
    }

    private void requireFlightPermitApproved(Mission mission) {
        if (mission.getFlightPermitStatus() != FlightPermitStatus.APPROVED) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Flight permit must be approved before the mission can start");
        }
    }

    private void requireFreshPreflightAuthorization(String missionId) {
        var run = preDeviceCheckRepository.findFirstByMissionIdOrderByCreatedAtDesc(missionId)
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_REQUEST,
                        "Không tìm thấy phiên kiểm tra trước chuyến bay."));
        Instant now = Instant.now();
        boolean blocked = run.getStatus() != PreDeviceCheckStatus.PASSED
                || run.getItems().stream().anyMatch(item -> Boolean.TRUE.equals(item.getRequired())
                && (item.getStatus() != PreDeviceItemStatus.PASSED
                || item.getExpiresAt() == null || !item.getExpiresAt().isAfter(now)));
        if (blocked) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Kết quả kiểm tra trước chuyến bay không còn hợp lệ. Vui lòng kiểm tra lại.");
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Override
    @Transactional
    @PreAuthorize("@missionAuthorizationService.canControlFlight(#missionId) or @missionAuthorizationService.canTerminateFlight(#missionId)")
    public MissionResponse markReturning(String missionId) {
        Mission mission = getRuntimeMissionOrThrow(missionId);
        MissionRuntimeGate.requireTerminationTransitionAllowed(mission);
        if (mission.getStatus() != MissionStatus.IN_FLIGHT && mission.getStatus() != MissionStatus.IN_PROGRESS) {
            throw new ApiException(ErrorCode.MISSION_STATUS_INVALID,
                    "Mission must be IN_FLIGHT to mark returning, current: " + mission.getStatus());
        }
        mission.setStatus(MissionStatus.RETURNING);
        updateDeviceStatus(mission, DeviceStatus.RETURNING);
        log.info("Mission {} – device returning to base", missionId);
        Mission saved = missionRepository.save(mission);
        return missionMapper.toResponse(saved);
    }

    @Override
    @Transactional
    @PreAuthorize("@missionAuthorizationService.canControlFlight(#missionId) or @missionAuthorizationService.canTerminateFlight(#missionId) or @missionAuthorizationService.canPrepareSafeStopPostflight(#missionId)")
    public MissionResponse startPostDeviceChecking(String missionId) {
        Mission mission = getRuntimeMissionOrThrow(missionId);
        MissionRuntimeGate.requirePostflightTransitionAllowed(mission);
        MissionStatus current = mission.getStatus();
        // Cho phép sang Postcheck cả khi mission chưa bay (chỉ chụp ảnh tham chiếu).
        boolean neverFlew = current == MissionStatus.CONNECTED
                || current == MissionStatus.PREFLIGHT_CHECKING
                || current == MissionStatus.READY_TO_FLY;
        if (!neverFlew) {
            requireStatus(mission, MissionStatus.RETURNING);
        }
        mission.setStatus(MissionStatus.POSTFLIGHT_CHECKING);
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
        if (mission.getStatus() == MissionStatus.COMPLETED
                && postDeviceCheckRepository.findFirstByMissionIdOrderByCreatedAtDesc(resolvedMissionId).isPresent()) {
            return missionMapper.toResponse(mission);
        }
        if (mission.getStatus() != MissionStatus.PENDING_REVIEW) {
            throw new ApiException(ErrorCode.MISSION_STATUS_INVALID,
                    "Mission must be PENDING_REVIEW with a recorded inspection to complete but is "
                            + mission.getStatus());
        }
        if (postDeviceCheckRepository.findFirstByMissionIdOrderByCreatedAtDesc(resolvedMissionId).isEmpty()) {
            throw new ApiException(ErrorCode.MISSION_STATUS_INVALID,
                    "A recorded post-flight inspection is required before mission completion");
        }
        checklistExecutionService.requireReadyForSubmission(mission);
        mission.setStatus(MissionStatus.COMPLETED);
        Instant completedAt = Instant.now();
        if (mission.getActualEndAt() == null) {
            mission.setActualEndAt(completedAt);
        }
        mission.setCompletedAt(completedAt);
        releaseMissionResources(mission, "MISSION_COMPLETE");
        if (checklistAssignmentStatusSync != null) checklistAssignmentStatusSync.syncForMission(mission);
        log.info("Mission {} COMPLETED successfully", missionId);
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
    public MissionResponse failMission(String missionId, String reason) {
        Mission mission = getRuntimeMissionOrThrow(missionId);
        if (Set.of(MissionStatus.IN_FLIGHT, MissionStatus.IN_PROGRESS, MissionStatus.RETURNING,
                MissionStatus.CONNECTED, MissionStatus.PREFLIGHT_CHECKING, MissionStatus.READY_TO_FLY,
                MissionStatus.POSTFLIGHT_CHECKING).contains(mission.getStatus())
                || (mission.getActualStartAt() != null && mission.getStatus() != MissionStatus.PENDING_REVIEW
                    && mission.getStatus() != MissionStatus.COMPLETED)
                || deviceConnectionRepository.findByMissionId(mission.getId()).stream().anyMatch(c ->
                    "CONNECTED".equals(c.getConnectionStatus()) || Boolean.TRUE.equals(c.getTelemetryActive())))
            throw new ApiException(ErrorCode.MISSION_CANCELLATION_REQUIRES_SAFE_STOP);
        if (mission.getStatus() == MissionStatus.IN_FLIGHT
                || mission.getStatus() == MissionStatus.RETURNING) {
            updateDeviceStatus(mission, DeviceStatus.MAINTENANCE);
        }
        mission.setStatus(MissionStatus.FAILED);
        mission.setActualEndAt(Instant.now());
        releaseMissionResources(mission, "MISSION_FAILED");
        log.error("Mission {} FAILED – reason: {}", missionId, reason);
        Mission saved = missionRepository.save(mission);
        return missionMapper.toResponse(saved);
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
        requireStatus(mission, MissionStatus.POSTFLIGHT_CHECKING);
        String summary = results.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(", "));
        String auditNotes = "Inspection: " + summary + ". " + (notes == null ? "" : notes.strip());
        return savePostFlightStatus(missionId, newDeviceStatus, auditNotes, results, telemetrySnapshot);
    }

    private MissionResponse savePostFlightStatus(String missionId, DeviceStatus newDeviceStatus,
            String notes, Map<String, InspectionResult> results) {
        return savePostFlightStatus(missionId, newDeviceStatus, notes, results, null);
    }

    private MissionResponse savePostFlightStatus(String missionId, DeviceStatus newDeviceStatus,
            String notes, Map<String, InspectionResult> results,
            PostFlightStatusRequest.TelemetrySnapshot telemetrySnapshot) {
        Mission mission = getRuntimeMissionOrThrow(missionId);
        Device device = getCurrentDevice(mission.getId());
        if (device == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Mission " + missionId + " has no assigned device");
        }
        device.setStatus(newDeviceStatus);
        deviceRepository.save(device);

        // Auto-create MaintenanceTicket if device requires maintenance post-flight
        if (newDeviceStatus == DeviceStatus.MAINTENANCE) {
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
        if (mission.getStatus() == MissionStatus.POSTFLIGHT_CHECKING) {
            mission.setStatus(MissionStatus.PENDING_REVIEW);
            if (mission.getActualEndAt() == null) mission.setActualEndAt(Instant.now());
            // End physical reservations while retaining final crew context for reporting.
            if (mission.getSafeStopRequestedAt() == null) releaseOperationalReservations(mission);
        }

        if (notes != null && !notes.isBlank()) {
            log.info("Mission {} post-flight notes: {}", missionId, notes);
        }
        log.info("Mission {} post-flight completed – device {} status set to {}", missionId, device.getDeviceCode(),
                newDeviceStatus);
        Mission saved = missionRepository.save(mission);
        if (saved.getStatus() == MissionStatus.COMPLETED) missionResultService.ensureCompletedResult(saved);
        return missionMapper.toResponse(saved);
    }

    private void requireScheduledWindow(Mission mission) {
        if (mission.getScheduledStartAt() == null || mission.getScheduledEndAt() == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Mission schedule start and end times must be set before assigning resources.");
        }
    }

    private void validateScheduledDates(Instant startAt, Instant endAt, Order order) {
        validatePlanningSchedule(startAt, endAt, order);
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
        var tokens = flightTokenRepository.findByMissionId(missionId);
        for (var token : tokens) token.setRevoked(true);
        flightTokenRepository.saveAll(tokens);
    }

    private Device getCurrentDevice(String missionId) {
        return missionDeviceAssignmentRepository.findFirstByMissionIdOrderByCreatedAtDesc(missionId)
                .map(MissionDeviceAssignment::getDevice)
                .orElse(null);
    }

    private String getCurrentStaffId(String missionId) {
        return missionStaffAssignmentRepository.findFirstByMissionIdOrderByAssignedAtDesc(missionId)
                .map(assignment -> assignment.getStaff() != null && assignment.getStaff().getId() != null
                        ? assignment.getStaff().getId().toString()
                        : null)
                .orElse(null);
    }

}
