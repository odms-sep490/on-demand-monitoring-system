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
import com.ondemandmonitoring.order.enums.OrderWeatherFallback;
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
        return createMission(orderId, request, null);
    }


    @Override
    @Transactional
    @PreAuthorize("@missionAuthorizationService.canManageMissions()")
    public MissionResponse replaceMission(String missionId, MissionCreateRequest request) {
        String orderId = missionRepository.findOrderIdByMissionId(missionId)
                .orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
        return createMission(orderId, request, missionId);
    }

    private MissionResponse createMission(String orderId, MissionCreateRequest request, String replacesMissionId) {
        String scopeLabel = Normalizer.normalize(
                request.getScopeLabel().strip().replaceAll("\\s+", " "), Normalizer.Form.NFC);
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found: " + orderId));
        if (order.getExecutionPlanStatus() != ExecutionPlanStatus.DRAFT
                && !(replacesMissionId != null && order.getExecutionPlanStatus() == ExecutionPlanStatus.FINALIZED))
            throw new ApiException(ErrorCode.EXECUTION_PLAN_NOT_DRAFT);
      
        if (replacesMissionId != null) {
            Mission original = missionRepository.findByIdForUpdate(replacesMissionId)
                    .orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
            if (!order.getId().equals(original.getOrder().getId()))
                throw new ApiException(ErrorCode.MISSION_REPLACEMENT_CROSS_ORDER);
            if (missionRepository.hasApprovedResult(original.getId()))
                throw new ApiException(ErrorCode.MISSION_RESULT_ALREADY_APPROVED);
            if (original.getStatus() != MissionStatus.CANCELLED || original.getCancellationAt() == null)
                throw new ApiException(ErrorCode.MISSION_REPLACEMENT_ORIGINAL_NOT_CANCELLED);
            if (missionRepository.existsByReplacesMissionIdAndStatusNot(original.getId(), MissionStatus.CANCELLED))
                throw new ApiException(ErrorCode.MISSION_REPLACEMENT_ALREADY_EXISTS);
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
        mission.setReplacesMissionId(replacesMissionId);
        Mission saved = missionRepository.saveAndFlush(mission);
        assignChecklist(saved, order, request.getChecklistIds());
        // Planning creation never materializes executions; the canonical plan finalizer owns that boundary.
        return missionMapper.toResponse(saved);
    }

    @Override
    @Transactional
    @PreAuthorize("@missionAuthorizationService.canManageMissions()")
    public MissionResponse finalizeReplacementMission(String missionId) {
        String orderId = missionRepository.findOrderIdByMissionId(missionId)
                .orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        Mission mission = missionRepository.findByIdForUpdate(missionId)
                .orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
        if (order.getExecutionPlanStatus() != ExecutionPlanStatus.FINALIZED
                || mission.getReplacesMissionId() == null
                || mission.getStatus() != MissionStatus.PLANNING)
            throw new ApiException(ErrorCode.EXECUTION_PLAN_NOT_DRAFT);
        if (missionRepository.findByIdForUpdate(mission.getReplacesMissionId())
                .filter(original -> original.getStatus() == MissionStatus.CANCELLED).isEmpty())
            throw new ApiException(ErrorCode.MISSION_REPLACEMENT_ORIGINAL_NOT_CANCELLED);
        if (missionChecklistAssignmentRepository.findByMissionIdAndRemovedAtIsNullOrderByDisplayOrderAscIdAsc(missionId).isEmpty())
            throw new ApiException(ErrorCode.INVALID_REQUEST, "A replacement Mission must have checklist items");
        checklistMaterializationService.materialize(mission);
        mission.setStatus(MissionStatus.READY);
        return missionMapper.toResponse(missionRepository.saveAndFlush(mission));
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
        Mission mission = lockPlanningResourceMission(missionId);
        resources.lockResources(resourceIds(mission, deviceId));
        String resolvedMissionId = mission.getId();
        if (mission.getStatus() != MissionStatus.PLANNING
                && mission.getStatus() != MissionStatus.READY
                && mission.getStatus() != MissionStatus.WAITING_CREW_CONFIRMATION) {
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
        if (!currentSameDevice.isEmpty()) {
            if (hasCurrentCrewAssigned(resolvedMissionId) && mission.getStatus() == MissionStatus.READY) {
                mission.setStatus(MissionStatus.WAITING_CREW_CONFIRMATION);
            }
            return missionMapper.toResponse(missionRepository.save(mission));
        }

        if (role == DeviceRole.MAIN) {
            List<MissionDeviceAssignment> currentMainAssignments =
                    missionDeviceAssignmentRepository.findAllByMissionIdAndDeviceRoleAndIsCurrentTrueOrderByCreatedAtDesc(
                            resolvedMissionId,
                            DeviceRole.MAIN);
            for (MissionDeviceAssignment assignment : currentMainAssignments) {
                assignment.setIsCurrent(false);
                assignment.setReleasedAt(now);
                assignment.setReleaseReason("Replaced by device assignment " + deviceId);
                resources.releaseResource(resolvedMissionId, assignment.getDevice().getId(), "DEVICE_REPLACED");
            }
            if (!currentMainAssignments.isEmpty()) {
                // Release the unique current-MAIN slot before Hibernate queues the new INSERT.
                // The existing Order -> Mission -> resource locks serialize replacements.
                missionDeviceAssignmentRepository.saveAllAndFlush(currentMainAssignments);
            }
        }

        MissionDeviceAssignment newAssignment = MissionDeviceAssignment.builder()
                .mission(mission)
                .device(device)
                .deviceRole(role)
                .isCurrent(true)
                .checkupStatus(CheckupStatus.PENDING)
                .postcheckStatus(null)
                .verifiedBy(null)
                .verifiedAt(null)
                .failureNotes(null)
                .assignedAt(now)
                .build();

        missionDeviceAssignmentRepository.save(newAssignment);
        if (hasCurrentCrewAssigned(resolvedMissionId) && mission.getStatus() == MissionStatus.READY) {
            mission.setStatus(MissionStatus.WAITING_CREW_CONFIRMATION);
        }
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
        Mission mission = lockPlanningResourceMission(missionId);
        resources.lockResources(resourceIds(mission, request.getDeviceId()));
        String resolvedMissionId = mission.getId();

        if (mission.getStatus() != MissionStatus.PLANNING
                && mission.getStatus() != MissionStatus.READY
                && mission.getStatus() != MissionStatus.WAITING_CREW_CONFIRMATION) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Cannot remove device in current mission status");
        }

        Instant now = Instant.now();
        List<MissionDeviceAssignment> currentAssignments =
                missionDeviceAssignmentRepository.findAllByMissionIdAndDeviceIdAndIsCurrentTrueOrderByCreatedAtDesc(
                        resolvedMissionId, request.getDeviceId());

        if (!currentAssignments.isEmpty()) {
            String reason = request.getReason() != null && !request.getReason().isBlank()
                    ? request.getReason().trim()
                    : "Removed by manager";
            for (MissionDeviceAssignment a : currentAssignments) {
                a.setIsCurrent(false);
                a.setReleasedAt(now);
                a.setReleaseReason(reason);
                resources.releaseResource(resolvedMissionId, a.getDevice().getId(), reason);
            }
            missionDeviceAssignmentRepository.saveAllAndFlush(currentAssignments);
        }

        if (mission.getStatus() == MissionStatus.WAITING_CREW_CONFIRMATION && !hasRequiredCrewAssigned(resolvedMissionId)) {
            mission.setStatus(MissionStatus.READY);
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

        Mission mission = lockPlanningResourceMission(missionId);
        resources.lockResources(resourceIds(mission, staffId));
        String resolvedMissionId = mission.getId();
        if (mission.getStatus() != MissionStatus.PLANNING
                && mission.getStatus() != MissionStatus.READY
                && mission.getStatus() != MissionStatus.WAITING_CREW_CONFIRMATION) {
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

        if (!missionStaffAssignmentRepository
                .findAllByMissionIdAndStaffIdAndAssignedRoleAndIsCurrentTrueOrderByAssignedAtDesc(
                        resolvedMissionId,
                        staffId,
                        assignedRole)
                .isEmpty()) {
            if (missionDeviceAssignmentRepository.existsByMissionId(resolvedMissionId)
                    && mission.getStatus() == MissionStatus.READY) {
                mission.setStatus(MissionStatus.WAITING_CREW_CONFIRMATION);
            }
            return missionMapper.toResponse(missionRepository.save(mission));
        }

        MissionStaffAssignment assignment = MissionStaffAssignment.builder()
                .mission(mission)
                .staff(staff)
                .assignedRole(assignedRole)
                .responseStatus(StaffResponseStatus.PENDING)
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

        if (missionDeviceAssignmentRepository.existsByMissionId(resolvedMissionId)
                && mission.getStatus() == MissionStatus.READY) {
            mission.setStatus(MissionStatus.WAITING_CREW_CONFIRMATION);
        }
        Mission saved = missionRepository.save(mission);
        return missionMapper.toResponse(saved);
    }

    @Override
    @Transactional
    @PreAuthorize("@missionAuthorizationService.canManageMissions()")
    public MissionResponse removeStaff(String missionId, com.ondemandmonitoring.mission.dto.request.RemoveStaffRequest request) {
        if (request == null || request.getStaffId() == null || request.getStaffId().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "staffId is required");
        }
        Mission mission = lockPlanningResourceMission(missionId);
        resources.lockResources(resourceIds(mission, request.getStaffId()));
        String resolvedMissionId = mission.getId();

        if (mission.getStatus() != MissionStatus.PLANNING
                && mission.getStatus() != MissionStatus.READY
                && mission.getStatus() != MissionStatus.WAITING_CREW_CONFIRMATION) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Cannot remove staff in current mission status");
        }

        Instant now = Instant.now();
        List<MissionStaffAssignment> currentAssignments;
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
            resources.releaseResource(resolvedMissionId, request.getStaffId(), reason);

            // Clean up user schedule
            userScheduleRepository.findByReferenceId(mission.getId()).stream()
                    .filter(s -> s.getStaff() != null && request.getStaffId().equals(s.getStaff().getId()))
                    .forEach(s -> s.setStatus(UserScheduleStatus.CANCELLED));
        }

        if (mission.getStatus() == MissionStatus.WAITING_CREW_CONFIRMATION && !hasRequiredCrewAssigned(resolvedMissionId)) {
            mission.setStatus(MissionStatus.READY);
        }
        Mission saved = missionRepository.save(mission);
        return missionMapper.toResponse(saved);
    }
    @Override
    @Transactional
    @PreAuthorize("@missionAuthorizationService.canManageMissions()")
    public MissionResponse updateMission(String missionId, MissionUpdateRequest request) {
        String orderId = missionRepository.findOrderIdByMissionId(missionId)
                .orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
        Order order = orderRepository.findByIdForUpdate(orderId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        Mission mission = missionRepository.findByIdForUpdate(missionId).orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
        if (!orderId.equals(mission.getOrder().getId())) throw new ApiException(ErrorCode.CHECKLIST_ASSIGNMENT_CROSS_ORDER);
        if (order.getExecutionPlanStatus() != ExecutionPlanStatus.DRAFT
                || mission.getStatus() != MissionStatus.PLANNING)
            throw new ApiException(ErrorCode.EXECUTION_PLAN_NOT_DRAFT);
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

    @Override
    @Transactional
    @PreAuthorize("@missionAuthorizationService.canManageMissions()")
    public MissionResponse rescheduleMission(String missionId, MissionRescheduleRequest request) {
        if (request == null) throw new ApiException(ErrorCode.INVALID_REQUEST);

        String orderId = missionRepository.findOrderIdByMissionId(missionId)
                .orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        Mission mission = missionRepository.findByIdForUpdate(missionId)
                .orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));

        if (!orderId.equals(mission.getOrder().getId())) {
            throw new ApiException(ErrorCode.CHECKLIST_ASSIGNMENT_CROSS_ORDER);
        }
        if (!Set.of(MissionStatus.PLANNING, MissionStatus.WAITING_DEPOSIT,
                MissionStatus.READY, MissionStatus.WAITING_CREW_CONFIRMATION,
                MissionStatus.WAITING_OPERATOR_ACCEPTANCE, MissionStatus.SCHEDULED,
                MissionStatus.FAILED_PREFLIGHT, MissionStatus.PENDING_APPROVAL)
                .contains(mission.getStatus()) || mission.getActualStartAt() != null) {
            throw new ApiException(ErrorCode.MISSION_RESCHEDULE_NOT_ALLOWED);
        }
        if (order.getOrderStatus() != OrderStatus.APPROVED
                && order.getOrderStatus() != OrderStatus.IN_PROGRESS) {
            throw new ApiException(ErrorCode.MISSION_RESCHEDULE_NOT_ALLOWED);
        }

        OrderWeatherFallback fallback = order.getWeatherFallback() == null
                ? OrderWeatherFallback.CONTACT_CUSTOMER
                : order.getWeatherFallback();
        if (fallback == OrderWeatherFallback.CANCEL_ORDER) {
            throw new ApiException(ErrorCode.MISSION_RESCHEDULE_ORDER_CANCELLATION_REQUIRED);
        }
        if (fallback == OrderWeatherFallback.CONTACT_CUSTOMER && !request.customerConfirmed()) {
            throw new ApiException(ErrorCode.MISSION_RESCHEDULE_CUSTOMER_CONFIRMATION_REQUIRED);
        }

        Instant newStart = request.scheduledStartAt();
        Instant newEnd = request.scheduledEndAt();
        if (newStart == null || newEnd == null || !newStart.isBefore(newEnd)
                || newStart.isBefore(Instant.now())) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "The replacement schedule must be a valid future interval");
        }
        if (newStart.equals(mission.getScheduledStartAt()) && newEnd.equals(mission.getScheduledEndAt())) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Choose a different schedule");
        }

        try {
            validateScheduledDates(newStart, newEnd, order);
        } catch (ApiException invalidWindow) {
            throw new ApiException(ErrorCode.MISSION_RESCHEDULE_OUTSIDE_CUSTOMER_WINDOW,
                    "Update the Order with the customer before selecting a time outside the requested window");
        }

        resources.revalidateSchedule(mission, newStart, newEnd);

        Instant now = Instant.now();
        mission.setScheduledStartAt(newStart);
        mission.setScheduledEndAt(newEnd);
        mission.setLastRescheduleReason(request.reason());
        mission.setLastRescheduleNote(blankToNull(request.note()));
        mission.setLastRescheduledAt(now);
        mission.setLastRescheduledBy(authenticatedUserResolver.getCurrentUserId());
        mission.setScheduleRevision((mission.getScheduleRevision() == null ? 0 : mission.getScheduleRevision()) + 1);

        // A changed time requires every current crew member to explicitly accept again.
        List<MissionStaffAssignment> crew = missionStaffAssignmentRepository
                .findAllByMissionIdAndIsCurrentTrue(mission.getId());
        crew.forEach(assignment -> {
            assignment.setResponseStatus(StaffResponseStatus.PENDING);
            assignment.setResponseAt(null);
            assignment.setRespondedAt(null);
            assignment.setDeclineReason(null);
        });
        missionStaffAssignmentRepository.saveAll(crew);

        if (mission.getStatus() != MissionStatus.PLANNING
                && mission.getStatus() != MissionStatus.WAITING_DEPOSIT) {
            mission.setStatus(crew.isEmpty() ? MissionStatus.READY : MissionStatus.WAITING_CREW_CONFIRMATION);
        }

        if (order.getExecutionPlanStatus() == ExecutionPlanStatus.FINALIZED) {
            order.setExecutionPlanRevision(order.getExecutionPlanRevision() + 1);
            order.setExecutionPlanAmendedAt(now);
            order.setExecutionPlanAmendedBy(authenticatedUserResolver.getCurrentUserId());
            order.setExecutionPlanAmendmentReason("Mission rescheduled: " + request.reason());
            orderRepository.save(order);
        }

        return missionMapper.toResponse(missionRepository.save(mission));
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
            if (assignment.getResponseStatus() != StaffResponseStatus.PENDING) return;
            assignment.setResponseStatus(StaffResponseStatus.REJECTED);
            assignment.setDeclineReason(reason);
            assignment.setRespondedAt(now);
        });
        missionStaffAssignmentRepository.saveAll(assignments);

        mission.setStatus(MissionStatus.READY);
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
            if (assignment.getResponseStatus() != StaffResponseStatus.PENDING) return;
            assignment.setResponseStatus(StaffResponseStatus.ACCEPTED);
            assignment.setRespondedAt(now);
        });
        missionStaffAssignmentRepository.saveAll(assignments);

        // Normalize missions created by the older assignment flow, which left
        // assigned staff on READY and consequently hid the Accept action.
        if (mission.getStatus() == MissionStatus.READY) {
            mission.setStatus(MissionStatus.WAITING_CREW_CONFIRMATION);
        }

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
                        && c.getStaffAssignment().getResponseStatus() == StaffResponseStatus.ACCEPTED);
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
        return preDeviceCheckCompletionService.complete(missionId, deviceId);
    }




    private FlightToken issueFlightToken(String missionId, String deviceId, String staffId) {
        return flightTokenService.issueFlightToken(missionId, deviceId, staffId);
    }




    private void requireFeasiblePlan(MissionPlan plan) {
        requireFeasiblePlan(plan, false);
    }

    private MissionStaffAssignment requireCurrentStaffAssignment(String missionId, String staffId) {
        List<MissionStaffAssignment> acceptedAssignments = requireCurrentStaffAssignments(missionId, staffId).stream()
                .filter(entry -> entry.getResponseStatus() == StaffResponseStatus.ACCEPTED)
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
                && mission.getStatus() != MissionStatus.WAITING_CREW_CONFIRMATION
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

    private boolean hasCurrentCrewAssigned(String missionId) {
        return missionStaffAssignmentRepository.findAllByMissionIdAndIsCurrentTrue(missionId)
                .stream().anyMatch(this::isUsablePlanningCrew);
    }

    private boolean isRequiredCrewAccepted(String missionId) {
        List<MissionStaffAssignment> assignments =
                missionStaffAssignmentRepository.findAllByMissionIdAndIsCurrentTrue(missionId).stream()
                        .filter(this::isUsablePlanningCrew).toList();
        Set<MissionStaffRole> acceptedRoles = assignments.stream()
                .filter(assignment -> assignment.getResponseStatus() == StaffResponseStatus.ACCEPTED)
                .map(MissionStaffAssignment::getAssignedRole)
                .collect(java.util.stream.Collectors.toSet());
        return acceptedRoles.containsAll(REQUIRED_CREW_ROLES)
                && assignments.stream().allMatch(entry -> entry.getResponseStatus() == StaffResponseStatus.ACCEPTED);
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
        orderRepository.findByIdForUpdate(resolved.getOrder().getId())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        Mission mission = missionRepository.findByIdForUpdate(resolved.getId()).orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
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
        Mission mission = missionRepository.findByIdForUpdate(missionId)
                .orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
        MissionRuntimeGate.requireFinalizedStructure(mission);
        checklistExecutionService.requireMaterialized(mission);
        return mission;
    }

    private Mission lockPlanningResourceMission(String missionId) {
        String orderId = missionRepository.findOrderIdByMissionId(missionId)
                .orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
        orderRepository.findByIdForUpdate(orderId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        Mission mission = missionRepository.findByIdForUpdate(missionId)
                .orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
        MissionRuntimeGate.requireOperationalCommandsAllowed(mission);
        return mission;
    }

    private List<String> resourceIds(Mission mission, String additional) {
        var ids = new java.util.TreeSet<String>();
        for (var lock : resourceTimeLockRepository.findByMissionId(mission.getId())) ids.add(lock.getResourceId());
        for (var assignment : missionDeviceAssignmentRepository.findByMissionId(mission.getId()))
            if (assignment.getDevice() != null) ids.add(assignment.getDevice().getId());
        for (var assignment : missionStaffAssignmentRepository.findByMissionId(mission.getId()))
            if (assignment.getStaff() != null) ids.add(assignment.getStaff().getId());
        if (additional != null) ids.add(additional);
        return List.copyOf(ids);
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
        resources.lockResources(resourceIds(mission, null));
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
        resources.lockResources(resourceIds(mission, null));
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
