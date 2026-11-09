package com.ondemandmonitoring.device.service;

import com.ondemandmonitoring.device.domain.MaintenanceTicket;
import com.ondemandmonitoring.device.repository.MaintenanceTicketRepository;
import com.ondemandmonitoring.device.service.impl.MaintenanceAuthorizationService;
import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MaintenanceAuthorizationServiceTest {
    @ParameterizedTest
    @EnumSource(RoleCode.class)
    void onlyAssignedStaffCanResolveAndManagersCanView(RoleCode code) {
        var tickets = mock(MaintenanceTicketRepository.class);
        var resolver = mock(AuthenticatedUserResolver.class);
        var user = User.builder().role(Role.builder().code(code).active(true).build()).isActive(true).build();
        user.setId("staff-1");
        when(resolver.getCurrentUser()).thenReturn(user);
        var ticket = new MaintenanceTicket();
        ticket.setAssignedStaff(user);
        when(tickets.findById("ticket")).thenReturn(Optional.of(ticket));
        var policy = new MaintenanceAuthorizationService(tickets, resolver);
        boolean manager = code == RoleCode.MANAGER || code == RoleCode.ADMIN;
        assertThat(policy.canResolveTicket("ticket")).isEqualTo(code == RoleCode.STAFF);
        assertThat(policy.canViewTicket("ticket")).isEqualTo(manager || code == RoleCode.STAFF);
        ticket.setAssignedStaff(null);
        assertThat(policy.canResolveTicket("ticket")).isFalse();
        assertThat(policy.canViewTicket("ticket")).isEqualTo(manager);
        assertThat(policy.canViewTicket("missing")).isFalse();
    }
}
