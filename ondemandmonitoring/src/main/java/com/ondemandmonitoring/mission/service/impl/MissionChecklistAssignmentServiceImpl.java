package com.ondemandmonitoring.mission.service.impl;

import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.dto.request.ChecklistAssignmentSelectionRequest;
import com.ondemandmonitoring.mission.dto.response.ChecklistAssignmentSelectionResponse;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.mapper.MissionChecklistAssignmentMapper;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.IMissionChecklistAssignmentService;
import com.ondemandmonitoring.order.enums.ChecklistAssignmentStatus;
import com.ondemandmonitoring.order.enums.ExecutionPlanStatus;
import com.ondemandmonitoring.order.repository.*;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
public class MissionChecklistAssignmentServiceImpl implements IMissionChecklistAssignmentService {
    private final OrderRepository orders;
    private final MissionRepository missions;
    private final OrderChecklistItemRepository items;
    private final MissionChecklistAssignmentRepository assignments;
    private final MissionChecklistExecutionRepository executions;
    private final AuthenticatedUserResolver currentUser;
    private final MissionChecklistAssignmentMapper mapper;

    @Override
    @Transactional(readOnly = true)
    public ChecklistAssignmentSelectionResponse get(String missionId) { return response(mission(missionId)); }

    @Override
    @Transactional
    public ChecklistAssignmentSelectionResponse replace(String missionId, ChecklistAssignmentSelectionRequest request) {
        var orderId = mission(missionId).getOrder().getId();
        var order = orders.findById(orderId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        var mission = missions.findById(missionId).orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
        if (!orderId.equals(mission.getOrder().getId())) throw new ApiException(ErrorCode.CHECKLIST_ASSIGNMENT_CROSS_ORDER);
        boolean finalizedPlanning = order.getExecutionPlanStatus() == ExecutionPlanStatus.FINALIZED
                && mission.getStatus() == MissionStatus.PLANNING;
        if (order.getExecutionPlanStatus() != ExecutionPlanStatus.DRAFT && !finalizedPlanning)
            throw new ApiException(ErrorCode.EXECUTION_PLAN_NOT_DRAFT);
        if (request == null || request.expectedPlanRevision() == null || request.items() == null)
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        if (order.getExecutionPlanRevision() != request.expectedPlanRevision()
                || order.getExecutionPlanRevision() == Long.MAX_VALUE)
            throw new ApiException(ErrorCode.EXECUTION_PLAN_REVISION_CONFLICT);
        if (mission.getStatus() != MissionStatus.PLANNING || mission.getCancellationAt() != null
                || !executions.findOrderedByMissionId(missionId).isEmpty())
            throw new ApiException(ErrorCode.MISSION_PLANNING_ONLY);
        var selected = new LinkedHashMap<String, com.ondemandmonitoring.order.domain.OrderChecklistItem>();
        for (var entry : request.items()) {
            if (entry == null || entry.orderChecklistItemId() == null || entry.orderChecklistItemId().isBlank())
                throw new ApiException(ErrorCode.INVALID_REQUEST);
            var item = items.findById(entry.orderChecklistItemId()).orElseThrow(() -> new ApiException(ErrorCode.CHECKLIST_ASSIGNMENT_CROSS_ORDER));
            if (!orderId.equals(item.getOrder().getId())) throw new ApiException(ErrorCode.CHECKLIST_ASSIGNMENT_CROSS_ORDER);
            if (selected.put(item.getId(), item) != null) throw new ApiException(ErrorCode.CHECKLIST_ASSIGNMENT_DUPLICATE);
        }
        // One checklist item belongs to at most one effective Mission (cancelled Missions release their items).
        var ownerMissions = new HashMap<String, Mission>();
        for (var other : assignments.findActiveByOrderId(orderId)) {
            if (missionId.equals(other.getMissionId()) || !selected.containsKey(other.getOrderChecklistItemId())) continue;
            var owner = ownerMissions.computeIfAbsent(other.getMissionId(), id -> missions.findById(id)
                    .orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND)));
            if (ExecutionPlanCoverageService.effective(owner))
                throw new ApiException(ErrorCode.CHECKLIST_ALREADY_ASSIGNED,
                        "Checklist item " + other.getOrderChecklistItemId() + " is already assigned to Mission "
                                + owner.getMissionCode());
        }
        var active = assignments.findByMissionIdAndRemovedAtIsNullOrderByDisplayOrderAscIdAsc(missionId);
        var retained = new HashMap<String, MissionChecklistAssignment>();
        String actor = currentUser.getCurrentUserId();
        Instant now = Instant.now();
        var released = new ArrayList<com.ondemandmonitoring.order.domain.OrderChecklistItem>();
        for (var assignment : active) {
            if (!ChecklistAssignmentIntegrity.matches(mission, assignment)) throw new ApiException(ErrorCode.CHECKLIST_ASSIGNMENT_CROSS_ORDER);
            if (selected.containsKey(assignment.getOrderChecklistItemId())) retained.put(assignment.getOrderChecklistItemId(), assignment);
            else {
                assignment.setRemovedAt(now); assignment.setRemovedBy(actor); assignment.setRemovalReason("Replaced planning selection");
                var item = assignment.getOrderChecklistItem();
                item.setAssignmentStatus(ChecklistAssignmentStatus.UNASSIGNED); released.add(item);
            }
        }
        assignments.saveAllAndFlush(active);
        items.saveAll(released);
        var changed = new ArrayList<MissionChecklistAssignment>();
        int position = 0;
        for (var item : selected.values()) {
            var assignment = retained.get(item.getId());
            if (assignment == null) {
                assignment = new MissionChecklistAssignment();
                assignment.setMissionId(missionId); assignment.setOrderId(orderId); assignment.setOrderChecklistItemId(item.getId());
                assignment.setMission(mission); assignment.setOrderChecklistItem(item);
                assignment.setAssignedAt(now); assignment.setAssignedBy(actor);
            }
            assignment.setDisplayOrder(position++); changed.add(assignment);
            item.setAssignmentStatus(ChecklistAssignmentStatus.ASSIGNED);
        }
        assignments.saveAllAndFlush(changed);
        items.saveAll(selected.values());
        order.setExecutionPlanRevision(Math.addExact(order.getExecutionPlanRevision(), 1));
        orders.saveAndFlush(order);
        return response(mission);
    }

    private Mission mission(String id) { return missions.findById(id).orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND)); }
    private ChecklistAssignmentSelectionResponse response(Mission mission) {
        return new ChecklistAssignmentSelectionResponse(mission.getId(), mission.getOrder().getExecutionPlanRevision(),
                assignments.findByMissionIdAndRemovedAtIsNullOrderByDisplayOrderAscIdAsc(mission.getId()).stream().map(mapper::toResponse).toList());
    }
}
