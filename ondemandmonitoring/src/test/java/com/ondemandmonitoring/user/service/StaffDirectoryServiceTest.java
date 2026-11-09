package com.ondemandmonitoring.user.service;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.repository.UserRepository;
import com.ondemandmonitoring.user.service.impl.StaffDirectoryService;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StaffDirectoryServiceTest {
    @ParameterizedTest
    @EnumSource(RoleCode.class)
    void onlyActiveStaffWithActiveRoleCanBeAssigned(RoleCode code) {
        var users = mock(UserRepository.class);
        var user = User.builder().role(Role.builder().code(code).active(true).build()).isActive(true).build();
        user.setId("user");
        when(users.findById("user")).thenReturn(Optional.of(user));
        var service = new StaffDirectoryService(users);
        if (code == RoleCode.STAFF) {
            assertThat(service.requireActiveStaff("user")).isSameAs(user);
        } else {
            assertThatThrownBy(() -> service.requireActiveStaff("user")).isInstanceOf(ApiException.class);
        }
        user.setIsActive(false);
        assertThatThrownBy(() -> service.requireActiveStaff("user")).isInstanceOf(ApiException.class);
        user.setIsActive(true);
        user.getRole().setActive(false);
        assertThatThrownBy(() -> service.requireActiveStaff("user")).isInstanceOf(ApiException.class);
    }
}
