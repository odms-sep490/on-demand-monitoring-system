package com.ondemandmonitoring.mission.service.impl;

import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.dto.request.ExecutionPlanFinalizeRequest;
import com.ondemandmonitoring.mission.dto.response.ExecutionPlanResponse;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.*;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.enums.*;
import com.ondemandmonitoring.order.repository.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
public class ExecutionPlanServiceImpl implements IExecutionPlanService {
    private final OrderRepository orders;
    private final MissionRepository missions;
    private final OrderChecklistItemRepository items;
    private final MissionChecklistAssignmentRepository assignments;
    private final IMissionChecklistMaterializationService materialization;
    private final IMissionChecklistScopeService scope;
    private final com.ondemandmonitoring.user.service.AuthenticatedUserResolver currentUser;
    private final ExecutionPlanCoverageService coverage;
    private final MissionStaffAssignmentRepository staff;
    private final MissionDeviceAssignmentRepository devices;

    @Override
    @Transactional(readOnly = true)
    public ExecutionPlanResponse get(String orderId) {
        return response(orders.findById(orderId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND)), children(orderId));
    }


    @Override
    @Transactional
    public ExecutionPlanResponse finalizePlan(String orderId, ExecutionPlanFinalizeRequest request) {
        if (request == null || request.expectedPlanRevision() == null || request.expectedPlanRevision() < 0) throw new ApiException(ErrorCode.INVALID_REQUEST);
        var order = orders.findById(orderId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if (order.getExecutionPlanStatus() != ExecutionPlanStatus.DRAFT)
            throw new ApiException(ErrorCode.EXECUTION_PLAN_NOT_DRAFT);
        if (order.getOrderStatus() != OrderStatus.APPROVED && order.getOrderStatus() != OrderStatus.IN_PROGRESS)
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Order must be APPROVED or IN_PROGRESS");
        if (order.getExecutionPlanRevision() != request.expectedPlanRevision() || order.getExecutionPlanRevision() == Long.MAX_VALUE)
            throw new ApiException(ErrorCode.EXECUTION_PLAN_REVISION_CONFLICT);
        var children = missions.findIdsByOrderIdOrderById(orderId).stream().sorted()
                .map(id -> missions.findById(id).orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND))).toList();
        var effective = children.stream().filter(ExecutionPlanCoverageService::effective).toList();
        if (effective.isEmpty()) throw new ApiException(ErrorCode.INVALID_REQUEST, "Order requires at least one Mission");
        var selected = assignments.findActiveByOrderId(orderId);
        var calculated = coverage.evaluate(items.findAllByOrderIdInOrderByDisplayOrderAscIdAsc(List.of(orderId)), children, selected);
        if (!calculated.coverageReady() || calculated.repeated() > 0)
            throw new ApiException(ErrorCode.CHECKLIST_COVERAGE_INCOMPLETE);
        for (var mission : effective) {
            if (!orderId.equals(mission.getOrder().getId())) throw new ApiException(ErrorCode.CHECKLIST_ASSIGNMENT_CROSS_ORDER);
            MissionService.requirePlanningReady(mission, staff.findByMissionId(mission.getId()),
                    devices.findByMissionId(mission.getId()));
            if (selected.stream().noneMatch(a -> mission.getId().equals(a.getMissionId())))
                throw new ApiException(ErrorCode.CHECKLIST_COVERAGE_INCOMPLETE, "Every Mission requires checklist items");
        }
        // Runtime consumes executions; create them only after all planning validation passes.
        effective.forEach(materialization::materialize);
        effective.forEach(m -> {
            m.setStatus(com.ondemandmonitoring.mission.enums.MissionStatus.READY);
            m.setResourcePrepareStatus(com.ondemandmonitoring.mission.enums.MissionResourcePrepareStatus.PENDING);
            staff.findByMissionId(m.getId()).stream().filter(a -> a.getReleasedAt() == null).forEach(a -> {
                a.setResponseStatus(com.ondemandmonitoring.mission.enums.MissionStaffAssignmentStatus.PENDING);
                a.setIsCurrent(true);
            });
            devices.findByMissionId(m.getId()).stream().filter(a -> a.getReleasedAt() == null).forEach(a -> {
                a.setCheckupStatus(com.ondemandmonitoring.mission.enums.MissionDeviceAssignmentStatus.PENDING);
                a.setIsCurrent(true);
            });
        });
        order.setExecutionPlanStatus(ExecutionPlanStatus.FINALIZED);
        order.setExecutionPlanRevision(order.getExecutionPlanRevision() + 1);
        order.setExecutionPlanFinalizedAt(Instant.now());
        order.setExecutionPlanFinalizedBy(currentUser.getCurrentUserId());
        missions.saveAllAndFlush(effective);
        orders.saveAndFlush(order);
        return response(order, children);
    }

    private List<Mission> children(String orderId) { return missions.findAllByOrderIdOrderByScheduledStartAtAscIdAsc(orderId); }

    private ExecutionPlanResponse response(Order order, List<Mission> children) {
        var active = assignments.findActiveByOrderId(order.getId());
        var calculated = coverage.evaluate(items.findAllByOrderIdInOrderByDisplayOrderAscIdAsc(List.of(order.getId())), children, active);
        var blockers = new ArrayList<String>();
        if (children.stream().noneMatch(ExecutionPlanCoverageService::effective)) blockers.add("NO_EFFECTIVE_MISSIONS");
        if (!calculated.coverageReady() || calculated.repeated() > 0) blockers.add("CHECKLIST_COVERAGE_INCOMPLETE");
        if (order.getOrderStatus() != OrderStatus.APPROVED && order.getOrderStatus() != OrderStatus.IN_PROGRESS) blockers.add("ORDER_NOT_APPROVED");
        if (order.getExecutionPlanStatus() == ExecutionPlanStatus.DRAFT) {
            for (var mission : children.stream().filter(ExecutionPlanCoverageService::effective).toList()) {
                try { MissionService.requirePlanningReady(mission, staff.findByMissionId(mission.getId()),
                    devices.findByMissionId(mission.getId())); }
                catch (ApiException exception) { blockers.add(exception.getErrorCode().name() + ":" + mission.getId()); }
                if (active.stream().noneMatch(a -> mission.getId().equals(a.getMissionId())))
                    blockers.add("MISSION_CHECKLIST_EMPTY:" + mission.getId());
            }
        }
        return new ExecutionPlanResponse(order.getId(), order.getExecutionPlanStatus(), order.getExecutionPlanRevision(),
                children.stream().map(m -> {
                    var mAssignments = active.stream().filter(a -> m.getId().equals(a.getMissionId())).toList();
                    var itemIds = mAssignments.stream().map(com.ondemandmonitoring.mission.domain.MissionChecklistAssignment::getOrderChecklistItemId).toList();
                    return new ExecutionPlanResponse.MissionScope(m.getId(), m.getScopeLabel(), mAssignments.size(), itemIds);
                }).toList(),
                calculated, List.copyOf(blockers));
    }
}
