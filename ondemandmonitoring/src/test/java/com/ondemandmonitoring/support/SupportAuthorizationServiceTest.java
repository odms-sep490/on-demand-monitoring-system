package com.ondemandmonitoring.support;

import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.support.domain.SupportCustomerTicket;
import com.ondemandmonitoring.support.repository.SupportCustomerTicketRepository;
import com.ondemandmonitoring.support.service.impl.SupportAuthorizationService;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SupportAuthorizationServiceTest {
    @ParameterizedTest
    @EnumSource(RoleCode.class)
    void enforcesTicketOwnershipAndAssignmentForAllFourRoles(RoleCode role) {
        var tickets = mock(SupportCustomerTicketRepository.class);
        var resolver = mock(AuthenticatedUserResolver.class);
        var user = User.builder().role(Role.builder().code(role).active(true).build()).isActive(true).build();
        user.setId("user-1");
        when(resolver.getCurrentUser()).thenReturn(user);
        var ticket = new SupportCustomerTicket();
        ticket.setCustomerId("user-1");
        ticket.setAssignedStaffId("user-1");
        when(tickets.findById("ticket")).thenReturn(Optional.of(ticket));
        var policy = new SupportAuthorizationService(tickets, resolver);
        assertThat(policy.canAccessTicket("ticket")).isTrue();
        assertThat(policy.canProcessTicket("ticket")).isEqualTo(role != RoleCode.CUSTOMER);
        ticket.setCustomerId("other");
        ticket.setAssignedStaffId("other");
        boolean manager = role == RoleCode.ADMIN || role == RoleCode.MANAGER;
        assertThat(policy.canAccessTicket("ticket")).isEqualTo(manager);
        assertThat(policy.canProcessTicket("ticket")).isEqualTo(manager);
        assertThat(policy.canAccessTicket("missing")).isFalse();
    }
}
