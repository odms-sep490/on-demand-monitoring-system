package com.ondemandmonitoring.auth.service;

import com.ondemandmonitoring.auth.dto.request.RegisterRequest;
import com.ondemandmonitoring.auth.dto.request.ResendOtpRequest;
import com.ondemandmonitoring.auth.dto.request.VerifyOtpRequest;
import com.ondemandmonitoring.auth.port.out.AuthCompensationPort;
import com.ondemandmonitoring.auth.port.out.IdentityProviderPort;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.enumeration.IdentityProvider;
import com.ondemandmonitoring.user.service.IUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CodeMismatchException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RegisterServiceTest {
    private IUserService users;
    private IdentityProviderPort identityProvider;
    private AuthCompensationPort compensationPort;
    private RegisterService service;

    @BeforeEach
    void setUp() {
        users = mock(IUserService.class);
        identityProvider = mock(IdentityProviderPort.class);
        compensationPort = mock(AuthCompensationPort.class);
        service = new RegisterService(users, identityProvider, compensationPort);
    }

    @Test
    void registerNormalizesEmailCreatesCustomerAndAssignsGroup() {
        RegisterRequest request = registerRequest("  Customer@Example.COM  ");
        when(users.findOptionalByEmail("customer@example.com")).thenReturn(Optional.empty());
        when(identityProvider.signUp(request)).thenReturn("cognito-sub");
        when(users.createLocalUser(anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn(User.builder().build());

        var response = service.register(request);

        assertTrue(response.isOtpRequired());
        assertEquals("customer@example.com", request.getEmail());
        verify(users).createLocalUser("customer@example.com", "Customer", RoleCode.CUSTOMER,
                "cognito-sub", "cognito-sub");
        verify(identityProvider).addUserToGroup("cognito-sub", "CUSTOMER");
    }

    @Test
    void registerRejectsDuplicateEmailBeforeCallingCognito() {
        RegisterRequest request = registerRequest("customer@example.com");
        when(users.findOptionalByEmail(request.getEmail())).thenReturn(Optional.of(User.builder().build()));

        ApiException exception = assertThrows(ApiException.class, () -> service.register(request));

        assertEquals(ErrorCode.EMAIL_ALREADY_EXISTS, exception.getErrorCode());
        verifyNoInteractions(identityProvider);
    }

    @Test
    void registerDirectsGoogleOnlyAccountToAuthenticatedLocalLinkFlow() {
        RegisterRequest request = registerRequest("customer@example.com");
        User googleUser = User.builder().build();
        googleUser.setId("user-id");
        when(users.findOptionalByEmail(request.getEmail())).thenReturn(Optional.of(googleUser));
        when(users.hasIdentity("user-id", IdentityProvider.GOOGLE)).thenReturn(true);
        when(users.hasIdentity("user-id", IdentityProvider.LOCAL)).thenReturn(false);

        ApiException exception = assertThrows(ApiException.class, () -> service.register(request));

        assertEquals(ErrorCode.LOCAL_IDENTITY_LINK_REQUIRED, exception.getErrorCode());
        verifyNoInteractions(identityProvider);
    }

    @Test
    void registerSchedulesCleanupWhenDatabasePersistenceFails() {
        RegisterRequest request = registerRequest("customer@example.com");
        when(users.findOptionalByEmail(request.getEmail())).thenReturn(Optional.empty());
        when(identityProvider.signUp(request)).thenReturn("cognito-sub");
        when(users.createLocalUser(anyString(), anyString(), any(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThrows(IllegalStateException.class, () -> service.register(request));
        verify(compensationPort).scheduleCognitoCleanup("cognito-sub", "cognito-sub");
    }

    @Test
    void verifyOtpMarksEmailVerified() {
        VerifyOtpRequest request = new VerifyOtpRequest();
        request.setEmail(" CUSTOMER@Example.com ");
        request.setOtpCode("123456");
        when(users.getCognitoUsername(eq("customer@example.com"), any())).thenReturn("cognito-user");

        service.verifyOtp(request);

        verify(identityProvider).confirmSignUp("cognito-user", "123456");
        verify(users).markEmailVerified("customer@example.com");
    }

    @Test
    void verifyOtpMapsCodeMismatchToOtpInvalid() {
        VerifyOtpRequest request = new VerifyOtpRequest();
        request.setEmail("customer@example.com");
        request.setOtpCode("000000");
        when(users.getCognitoUsername(anyString(), any())).thenReturn("cognito-user");
        doThrow(CodeMismatchException.builder().message("bad code").build())
                .when(identityProvider).confirmSignUp(anyString(), anyString());

        ApiException exception = assertThrows(ApiException.class, () -> service.verifyOtp(request));
        assertEquals(ErrorCode.OTP_INVALID, exception.getErrorCode());
        verify(users, never()).markEmailVerified(anyString());
    }

    @Test
    void resendOtpUsesNormalizedEmailAndLocalIdentity() {
        ResendOtpRequest request = new ResendOtpRequest();
        request.setEmail(" Customer@Example.com ");
        when(users.getCognitoUsername("customer@example.com", com.ondemandmonitoring.user.enumeration.IdentityProvider.LOCAL))
                .thenReturn("cognito-user");

        service.resendOtp(request);

        verify(identityProvider).resendConfirmationCode("cognito-user");
    }

    private RegisterRequest registerRequest(String email) {
        RegisterRequest request = new RegisterRequest();
        request.setEmail(email);
        request.setPassword("Password1!");
        request.setFullName("Customer");
        return request;
    }
}
