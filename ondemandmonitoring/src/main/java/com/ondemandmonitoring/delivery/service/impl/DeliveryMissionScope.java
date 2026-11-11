package com.ondemandmonitoring.delivery.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.delivery.domain.OrderDelivery;
import com.ondemandmonitoring.delivery.repository.OrderDeliveryRepository;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.enums.MissionPurpose;
import com.ondemandmonitoring.mission.repository.MissionChecklistAssignmentRepository;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import java.util.Set;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DeliveryMissionScope {
    private final MissionRepository missions;
    private final MissionChecklistAssignmentRepository assignments;
    private final OrderDeliveryRepository deliveries;

    public Mission requireOriginal(String orderId, String missionId) {
        Mission mission = missions.findByIdWithOrder(missionId).orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
        if (!orderId.equals(mission.getOrder().getId()) || mission.getPurpose() == MissionPurpose.REWORK
                || !com.ondemandmonitoring.mission.service.impl.ExecutionPlanCoverageService.effective(mission))
            throw new ApiException(ErrorCode.MEDIA_ACCESS_DENIED);
        return mission;
    }

    public Set<String> checklistIds(String orderId, String missionId) {
        requireOriginal(orderId, missionId);
        return assignments.findByMissionIdAndRemovedAtIsNullOrderByDisplayOrderAscIdAsc(missionId).stream()
                .map(a -> a.getOrderChecklistItem().getId()).collect(Collectors.toSet());
    }

    public Optional<OrderDelivery> find(String orderId, String missionId, boolean lock) {
        if (missionId == null) return lock ? deliveries.findByOrderIdForUpdate(orderId) : deliveries.findByOrderId(orderId);
        requireOriginal(orderId, missionId);
        return lock ? deliveries.findScopedForUpdate(orderId, missionId) : deliveries.findScoped(orderId, missionId);
    }

    public java.util.List<Mission> missions(String orderId, String missionId) {
        var all = missions.findAllByOrderIdOrderByScheduledStartAtAscIdAsc(orderId).stream()
                .filter(com.ondemandmonitoring.mission.service.impl.ExecutionPlanCoverageService::effective).toList();
        if (missionId == null) return all;
        var ids = checklistIds(orderId, missionId);
        var related = assignments.findActiveByOrderId(orderId).stream()
                .filter(a -> ids.contains(a.getOrderChecklistItem().getId())).map(a -> a.getMissionId()).collect(Collectors.toSet());
        return all.stream().filter(m -> missionId.equals(m.getId())
                || (m.getPurpose() == MissionPurpose.REWORK && related.contains(m.getId()))).toList();
    }
}
