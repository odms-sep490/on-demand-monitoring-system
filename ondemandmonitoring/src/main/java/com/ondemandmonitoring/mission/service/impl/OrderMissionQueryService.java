package com.ondemandmonitoring.mission.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.mission.dto.response.MissionResponse;
import com.ondemandmonitoring.mission.mapper.MissionMapper;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.mission.service.IMissionAuthorizationService;
import com.ondemandmonitoring.mission.service.IOrderMissionQueryService;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderMissionQueryService implements IOrderMissionQueryService {
    private final OrderRepository orders;
    private final MissionRepository missions;
    private final MissionMapper mapper;
    private final AuthenticatedUserResolver currentUser;
    private final IMissionAuthorizationService missionAuthorization;

    @Override
    @PreAuthorize("@missionAuthorizationService.canManageMissions() or @orderAuthorizationService.canViewOrder(#orderId)")
    public List<MissionResponse> getMissionsByOrderId(String orderId) {
        orders.findById(orderId).orElseThrow(() -> new ApiException(
                ErrorCode.RESOURCE_NOT_FOUND, "Order not found: " + orderId));
        boolean staff = currentUser.getCurrentUser().getRole().getCode() == RoleCode.STAFF;
        // Order context permission does not expose sibling Mission details to an unassigned Staff.
        return missions.findAllByOrderIdOrderByScheduledStartAtAscIdAsc(orderId).stream()
                .filter(mission -> !staff || missionAuthorization.canViewMission(mission.getId()))
                .map(mapper::toResponse).toList();
    }
}
