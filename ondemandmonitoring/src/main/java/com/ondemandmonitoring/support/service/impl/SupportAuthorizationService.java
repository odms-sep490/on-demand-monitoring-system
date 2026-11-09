package com.ondemandmonitoring.support.service.impl;

import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.support.domain.SupportCustomerTicket;
import com.ondemandmonitoring.support.repository.SupportCustomerTicketRepository;
import com.ondemandmonitoring.support.service.ISupportAuthorizationService;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service("supportAuthorizationService")
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SupportAuthorizationService implements ISupportAuthorizationService {
    private final SupportCustomerTicketRepository tickets;
    private final AuthenticatedUserResolver currentUser;

    @Override
    public boolean canManageTickets() {
        RoleCode role = currentUser.getCurrentUser().getRole().getCode();
        return role == RoleCode.ADMIN || role == RoleCode.MANAGER;
    }

    @Override
    public boolean canAccessTicket(String ticketId) {
        User user = currentUser.getCurrentUser();
        return tickets.findById(ticketId).map(ticket -> canManageTickets() || assigned(ticket, user)
                || (user.getRole().getCode() == RoleCode.CUSTOMER && user.getId().equals(ticket.getCustomerId())))
                .orElse(false);
    }

    @Override
    public boolean canProcessTicket(String ticketId) {
        User user = currentUser.getCurrentUser();
        return tickets.findById(ticketId).map(ticket -> canManageTickets() || assigned(ticket, user)).orElse(false);
    }

    @Override
    public boolean canViewCustomerTickets(String customerId) {
        User user = currentUser.getCurrentUser();
        return canManageTickets() || (user.getRole().getCode() == RoleCode.CUSTOMER
                && user.getId().equals(customerId));
    }

    private boolean assigned(SupportCustomerTicket ticket, User user) {
        return user.getRole().getCode() == RoleCode.STAFF && user.getId().equals(ticket.getAssignedStaffId());
    }
}
