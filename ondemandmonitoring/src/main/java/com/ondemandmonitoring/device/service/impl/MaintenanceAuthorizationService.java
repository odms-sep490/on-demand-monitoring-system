package com.ondemandmonitoring.device.service.impl;

import com.ondemandmonitoring.device.repository.MaintenanceTicketRepository;
import com.ondemandmonitoring.device.service.IMaintenanceAuthorizationService;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service("maintenanceAuthorizationService")
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MaintenanceAuthorizationService implements IMaintenanceAuthorizationService {
    private final MaintenanceTicketRepository tickets;
    private final AuthenticatedUserResolver currentUser;

    @Override
    public boolean canManageTickets() {
        RoleCode role = currentUser.getCurrentUser().getRole().getCode();
        return role == RoleCode.MANAGER || role == RoleCode.ADMIN;
    }

    @Override
    public boolean canViewTicket(String id) {
        return tickets.findById(id).map(ticket -> canManageTickets() || canResolveTicket(id)).orElse(false);
    }

    @Override
    public boolean canResolveTicket(String id) {
        User user = currentUser.getCurrentUser();
        if (user.getRole().getCode() != RoleCode.STAFF) return false;
        return tickets.findById(id).map(ticket -> ticket.getAssignedStaff() != null
                && user.getId().equals(ticket.getAssignedStaff().getId())).orElse(false);
    }
}
