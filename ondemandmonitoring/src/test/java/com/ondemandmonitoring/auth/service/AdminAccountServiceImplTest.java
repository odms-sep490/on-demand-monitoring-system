package com.ondemandmonitoring.auth.service;

import com.ondemandmonitoring.auth.dto.request.CreateManagedAccountRequest;
import com.ondemandmonitoring.auth.port.out.AuthCompensationPort;
import com.ondemandmonitoring.auth.port.out.IdentityProviderPort;
import com.ondemandmonitoring.auth.port.out.ManagedIdentity;
import com.ondemandmonitoring.auth.service.impl.AdminAccountServiceImpl;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.IUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminAccountServiceImplTest {
    private IUserService users;
    private IdentityProviderPort identityProvider;
    private AuthCompensationPort compensationPort;
    private AdminAccountServiceImpl service;

    @BeforeEach
    void setUp() {
        users = mock(IUserService.class);
        identityProvider = mock(IdentityProviderPort.class);
        compensationPort = mock(AuthCompensationPort.class);
        service = new AdminAccountServiceImpl(users, identityProvider, compensationPort);
    }

    @Test
    void createsInvitedEmployeeAndAssignsMatchingGroup() {
        CreateManagedAccountRequest request = request(RoleCode.STAFF);
        when(users.findOptionalByEmail("operator@example.com")).thenReturn(Optional.empty());
        when(identityProvider.createInvitedUser(request)).thenReturn(new ManagedIdentity("uuid-user", "sub"));

        var result = service.create(request);

        assertTrue(result.isInvitationSent());
        assertTrue(result.isPasswordChangeRequired());
        verify(users).createManagedUser("operator@example.com", "Operator", RoleCode.STAFF,
                "uuid-user", "sub");
        verify(identityProvider).addUserToGroup("uuid-user", "STAFF");
    }

    @Test
    void rejectsCustomerRole() {
        CreateManagedAccountRequest request = request(RoleCode.CUSTOMER);

        ApiException exception = assertThrows(ApiException.class, () -> service.create(request));

        assertEquals(ErrorCode.ACCESS_DENIED, exception.getErrorCode());
        verifyNoInteractions(identityProvider, users);
    }

    @Test
    void rejectsDuplicateEmailBeforeCreatingCognitoAccount() {
        CreateManagedAccountRequest request = request(RoleCode.MANAGER);
        when(users.findOptionalByEmail("operator@example.com"))
                .thenReturn(Optional.of(User.builder().build()));

        ApiException exception = assertThrows(ApiException.class, () -> service.create(request));

        assertEquals(ErrorCode.EMAIL_ALREADY_EXISTS, exception.getErrorCode());
        verifyNoInteractions(identityProvider);
    }

    @Test
    void schedulesCleanupWhenManagedUserPersistenceFails() {
        CreateManagedAccountRequest request = request(RoleCode.MANAGER);
        when(users.findOptionalByEmail(anyString())).thenReturn(Optional.empty());
        when(identityProvider.createInvitedUser(request)).thenReturn(new ManagedIdentity("uuid-user", "sub"));
        when(users.createManagedUser(anyString(), anyString(), any(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThrows(IllegalStateException.class, () -> service.create(request));
        verify(compensationPort).scheduleCognitoCleanup("uuid-user", "sub");
    }

    private CreateManagedAccountRequest request(RoleCode role) {
        CreateManagedAccountRequest request = new CreateManagedAccountRequest();
        request.setEmail(" Operator@Example.com ");
        request.setFullName("Operator");
        request.setRole(role);
        return request;
    }
}
