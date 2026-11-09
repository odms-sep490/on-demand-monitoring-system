package com.ondemandmonitoring.role;

import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.role.infrastructure.RoleDataInitializer;
import com.ondemandmonitoring.role.repository.RoleRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RoleDataInitializerTest {
    @Test
    void createsExactlyFourUppercaseRoles() {
        RoleRepository roles = mock(RoleRepository.class);
        when(roles.findByCode(any())).thenReturn(Optional.empty());
        new RoleDataInitializer(roles).run(null);
        var captured = ArgumentCaptor.forClass(Role.class);
        verify(roles, times(4)).save(captured.capture());
        assertThat(captured.getAllValues()).extracting(Role::getCode)
                .containsExactlyInAnyOrder(RoleCode.ADMIN, RoleCode.MANAGER, RoleCode.STAFF, RoleCode.CUSTOMER);
        assertThat(captured.getAllValues()).allSatisfy(role -> {
            assertThat(role.getName()).isEqualTo(role.getCode().name());
            assertThat(role.isActive()).isTrue();
            assertThat(role.isSystemRole()).isTrue();
        });
    }

    @Test
    void existingRolesAreNotOverwrittenOnRestart() {
        RoleRepository roles = mock(RoleRepository.class);
        when(roles.findByCode(any())).thenReturn(Optional.of(new Role()));
        var initializer = new RoleDataInitializer(roles);
        initializer.run(null);
        initializer.run(null);
        verify(roles, never()).save(any());
    }
}
