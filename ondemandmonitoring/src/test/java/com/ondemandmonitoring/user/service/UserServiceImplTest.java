package com.ondemandmonitoring.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.role.service.RoleService;
import com.ondemandmonitoring.user.domain.CustomerProfile;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.enumeration.IdentityProvider;
import com.ondemandmonitoring.user.repository.CustomerProfileRepository;
import com.ondemandmonitoring.user.repository.UserRepository;
import com.ondemandmonitoring.user.service.impl.UserServiceImpl;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleService roleService;

    @Mock
    private IUserIdentityService userIdentityService;

    @Mock
    private CustomerProfileRepository customerProfileRepository;

    @InjectMocks
    private UserServiceImpl userService;

    @Test
    void createLocalUser_customer_provisionsProfile() {
        Role customerRole = role(RoleCode.CUSTOMER);
        when(roleService.getActiveRole(RoleCode.CUSTOMER)).thenReturn(customerRole);
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(UUID.randomUUID().toString());
            return user;
        });

        User user = userService.createLocalUser(
                " Customer@Example.com ", "Customer Name", RoleCode.CUSTOMER,
                "cognito-user", "cognito-sub");

        verify(userIdentityService).create(
                user, IdentityProvider.LOCAL, "cognito-user", "cognito-sub");
        ArgumentCaptor<CustomerProfile> profileCaptor =
                ArgumentCaptor.forClass(CustomerProfile.class);
        verify(customerProfileRepository).save(profileCaptor.capture());
        assertThat(profileCaptor.getValue().getUser()).isSameAs(user);
    }

    @Test
    void createSocialUser_customer_provisionsProfile() {
        Role customerRole = role(RoleCode.CUSTOMER);
        when(roleService.getActiveRole(RoleCode.CUSTOMER)).thenReturn(customerRole);
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(UUID.randomUUID().toString());
            return user;
        });

        User user = userService.createSocialUser(
                "customer@example.com", "Customer Name", RoleCode.CUSTOMER,
                "google-user", "google-sub");

        verify(userIdentityService).create(
                user, IdentityProvider.GOOGLE, "google-user", "google-sub");
        verify(customerProfileRepository).save(any(CustomerProfile.class));
    }

    @Test
    void createManagedUser_employee_doesNotProvisionCustomerProfile() {
        Role staffRole = role(RoleCode.MANAGER);
        when(roleService.getActiveRole(RoleCode.MANAGER)).thenReturn(staffRole);
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        userService.createManagedUser(
                "staff@example.com", "Staff Name", RoleCode.MANAGER,
                "staff-user", "staff-sub");

        verify(customerProfileRepository, never()).save(any(CustomerProfile.class));
    }

    @Test
    void createLocalUser_whenProfileProvisioningFails_propagatesFailure() {
        when(roleService.getActiveRole(RoleCode.CUSTOMER)).thenReturn(role(RoleCode.CUSTOMER));
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(customerProfileRepository.save(any(CustomerProfile.class)))
                .thenThrow(new IllegalStateException("profile provisioning failed"));

        assertThatThrownBy(() -> userService.createLocalUser(
                "customer@example.com", "Customer Name", RoleCode.CUSTOMER,
                "cognito-user", "cognito-sub"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("profile provisioning failed");
    }

    @Test
    void createLocalUser_whenConcurrentEmailInsertWins_returnsEmailConflict() {
        when(roleService.getActiveRole(RoleCode.CUSTOMER)).thenReturn(role(RoleCode.CUSTOMER));
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("uk_users_email"));

        assertThatThrownBy(() -> userService.createLocalUser(
                "customer@example.com", "Customer Name", RoleCode.CUSTOMER,
                "cognito-user", "cognito-sub"))
                .isInstanceOf(com.ondemandmonitoring.common.exception.ApiException.class)
                .extracting("errorCode")
                .isEqualTo(com.ondemandmonitoring.common.exception.ErrorCode.EMAIL_ALREADY_EXISTS);

        verify(userIdentityService, never()).create(any(), any(), any(), any());
    }

    @Test
    void findByCognitoUsername_delegatesToIdentityService() {
        User expected = User.builder().build();
        expected.setId(UUID.randomUUID().toString());
        when(userIdentityService.findUserByCognitoUsername("cognito-user"))
                .thenReturn(expected);

        User actual = userService.findByCognitoUsername("cognito-user");

        assertThat(actual).isSameAs(expected);
    }

    @Test
    void unlinkIdentity_delegatesToIdentityService() {
        User user = User.builder().build();
        user.setId(UUID.randomUUID().toString());

        userService.unlinkIdentity(user, IdentityProvider.LOCAL);

        verify(userIdentityService).unlink(user, IdentityProvider.LOCAL);
    }

    private Role role(RoleCode code) {
        return Role.builder()
                .id(UUID.randomUUID())
                .code(code)
                .name(code.name())
                .active(true)
                .build();
    }
}
