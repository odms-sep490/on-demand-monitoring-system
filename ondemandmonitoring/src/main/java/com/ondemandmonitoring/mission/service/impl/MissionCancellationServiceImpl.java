package com.ondemandmonitoring.mission.service.impl;

import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.mission.dto.request.MissionCancelRequest;
import com.ondemandmonitoring.mission.dto.response.MissionCancellationResponse;
import com.ondemandmonitoring.mission.enums.*;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.IMissionCancellationService;
import com.ondemandmonitoring.order.enums.*;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
public class MissionCancellationServiceImpl implements IMissionCancellationService {
    private final OrderRepository orders;
    private final MissionRepository missions;
    private final MissionResultRepository results;
    private final AuthenticatedUserResolver actor;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ChecklistAssignmentStatusSync checklistAssignmentStatusSync;

    @Override
    @Transactional
    public MissionCancellationResponse cancel(String missionId, MissionCancelRequest request) {
        if (request == null || request.expectedPlanRevision() == null || request.expectedPlanRevision() < 0
                || request.reason() == null || request.reason().isBlank() || request.reason().length() > 1000)
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        String orderId = missions.findOrderIdByMissionId(missionId)
                .orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
        var order = orders.findById(orderId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        var mission = missions.findById(missionId).orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
        MissionCancellationGuard.requireAllowed(orderId, order, mission, request.expectedPlanRevision(), results, missions);
        mission.setStatus(MissionStatus.CANCELLED);
        mission.setResourcePrepareStatus(MissionResourcePrepareStatus.CANCELLED);
        mission.setCancellationAt(Instant.now()); mission.setCancelledBy(actor.getCurrentUserId());
        mission.setCancellationReason(request.reason().strip());
        order.setExecutionPlanRevision(order.getExecutionPlanRevision() + 1);
        missions.saveAndFlush(mission); orders.saveAndFlush(order);
        if (checklistAssignmentStatusSync != null) checklistAssignmentStatusSync.syncForMission(mission);
        return new MissionCancellationResponse(missionId, mission.getStatus(), mission.getResourcePrepareStatus(),
                order.getExecutionPlanRevision(),
                mission.getCancellationAt(), mission.getCancelledBy(), mission.getCancellationReason());
    }
}
