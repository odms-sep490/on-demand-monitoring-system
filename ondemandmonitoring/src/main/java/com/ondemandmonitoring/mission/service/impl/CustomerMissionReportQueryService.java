package com.ondemandmonitoring.mission.service.impl;

import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.dto.response.*;
import com.ondemandmonitoring.mission.enums.MissionResultApprovalStatus;
import com.ondemandmonitoring.mission.mapper.CustomerMissionReportMapper;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.ICustomerMissionReportQueryService;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@PreAuthorize("hasRole('CUSTOMER')")
public class CustomerMissionReportQueryService implements ICustomerMissionReportQueryService {
    private final OrderRepository orders;
    private final MissionRepository missions;
    private final MissionResultRepository results;
    private final MissionChecklistExecutionRepository executions;
    private final AuthenticatedUserResolver actor;
    private final CustomerMissionReportMapper mapper;

    private void requireOwner(String orderId) {
        var user = actor.getCurrentUser();
        if (!Boolean.TRUE.equals(user.getIsActive()) || user.getRole() == null || !user.getRole().isActive()
                || user.getRole().getCode() != RoleCode.CUSTOMER) throw new ApiException(ErrorCode.ACCESS_DENIED);
        var order = orders.findById(orderId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if (order.getCustomer() == null || !user.getId().equals(order.getCustomer().getId()))
            throw new ApiException(ErrorCode.ACCESS_DENIED);
    }

    @Override
    public List<CustomerMissionSummary> list(String orderId) {
        requireOwner(orderId);
        var children = missions.findAllByOrderIdOrderByScheduledStartAtAscIdAsc(orderId);
        var ids = children.stream().map(Mission::getId).collect(Collectors.toSet());
        var published = ids.isEmpty() ? Set.<String>of() : results.findApprovedMissionIds(ids);
        return children.stream().map(m -> mapper.summary(m,
                ids.contains(m.getReplacesMissionId()) ? m.getReplacesMissionId() : null,
                published.contains(m.getId()))).toList();
    }

    @Override
    public CustomerMissionReport report(String orderId, String missionId) {
        requireOwner(orderId);
        var mission = missions.findById(missionId).orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
        if (mission.getOrder() == null || !orderId.equals(mission.getOrder().getId()))
            throw new ApiException(ErrorCode.ACCESS_DENIED);
        var result = results.findByMissionId(missionId)
                .filter(r -> r.getApprovalStatus() == MissionResultApprovalStatus.APPROVED);
        String replacement = mission.getReplacesMissionId() == null ? null : missions.findById(mission.getReplacesMissionId())
                .filter(m -> m.getOrder() != null && orderId.equals(m.getOrder().getId()))
                .map(Mission::getId).orElse(null);
        var summary = mapper.summary(mission, replacement, result.isPresent());
        if (result.isEmpty()) return new CustomerMissionReport(summary, false, null, List.of());
        if (result.get().getMission() == null || !missionId.equals(result.get().getMission().getId()))
            throw new ApiException(ErrorCode.CHECKLIST_EXECUTION_INTEGRITY_INVALID);
        var rows = executions.findOrderedByMissionId(missionId);
        rows.forEach(e -> requireScope(e, orderId, missionId));
        return new CustomerMissionReport(summary, true, mapper.result(result.get()), rows.stream().map(mapper::checklist).toList());
    }

    private void requireScope(MissionChecklistExecution e, String orderId, String missionId) {
        var item = e.getOrderChecklistItem();
        var assignment = e.getMissionChecklistAssignment();
        if (!missionId.equals(e.getMissionId()) || !orderId.equals(e.getOrderId())
                || item == null || item.getOrder() == null || !orderId.equals(item.getOrder().getId())
                || !Objects.equals(item.getId(), e.getOrderChecklistItemId())
                || assignment == null || !missionId.equals(assignment.getMissionId())
                || !orderId.equals(assignment.getOrderId())
                || !Objects.equals(e.getOrderChecklistItemId(), assignment.getOrderChecklistItemId())
                || !Objects.equals(e.getMissionChecklistAssignmentId(), assignment.getId()))
            throw new ApiException(ErrorCode.CHECKLIST_EXECUTION_INTEGRITY_INVALID);
    }
}
