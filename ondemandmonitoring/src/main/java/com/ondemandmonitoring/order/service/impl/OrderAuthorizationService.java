package com.ondemandmonitoring.order.service.impl;

import com.ondemandmonitoring.mission.service.IMissionAuthorizationService;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.order.service.IOrderAuthorizationService;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service("orderAuthorizationService")
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderAuthorizationService implements IOrderAuthorizationService {
    private final OrderRepository orders;
    private final AuthenticatedUserResolver currentUser;
    private final IMissionAuthorizationService missionAuthorization;
    private final MissionRepository missions;

    @Override
    public boolean canViewOrder(String orderId) {
        User user = currentUser.getCurrentUser();
        return orders.findById(orderId).map(order -> {
            RoleCode role = user.getRole().getCode();
            if (role == RoleCode.ADMIN || role == RoleCode.MANAGER) return true;
            if (role == RoleCode.CUSTOMER) {
                return order.getCustomer() != null && user.getId().equals(order.getCustomer().getId());
            }
            // Order context visibility never grants operational access to sibling Missions.
            return role == RoleCode.STAFF && missions.findAllByOrderIdOrderByScheduledStartAtAscIdAsc(orderId)
                    .stream().anyMatch(mission -> missionAuthorization.canViewMission(mission.getId()));
        }).orElse(false);
    }
}
