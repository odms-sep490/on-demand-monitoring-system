package com.ondemandmonitoring.auth.service;

import com.ondemandmonitoring.auth.dto.request.SocialSyncRequest;
import com.ondemandmonitoring.auth.enumeration.AuthProvider;
import com.ondemandmonitoring.auth.enumeration.SocialAuthIntent;
import com.ondemandmonitoring.auth.port.out.AuthCompensationPort;
import com.ondemandmonitoring.auth.mapper.AuthenticatedUserMapper;
import com.ondemandmonitoring.auth.port.out.AuthenticationTokens;
import com.ondemandmonitoring.auth.port.out.IdentityProviderPort;
import com.ondemandmonitoring.auth.port.out.SocialAuthenticationResult;
import com.ondemandmonitoring.auth.port.out.SocialIdentityProviderPort;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.enumeration.IdentityProvider;
import com.ondemandmonitoring.user.service.IUserService;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapstruct.factory.Mappers;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InvalidPasswordException;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SocialAuthServiceTest {
    private SocialIdentityProviderPort socialProvider;
    private IdentityProviderPort cognito;
    private AuthCompensationPort compensationPort;
    private IUserService users;
    private RefreshTokenCookieService cookies;
    private SocialAuthService service;

    @BeforeEach
    void setUp() {
        socialProvider = mock(SocialIdentityProviderPort.class);
        cognito = mock(IdentityProviderPort.class);
        compensationPort = mock(AuthCompensationPort.class);
        users = mock(IUserService.class);
        cookies = mock(RefreshTokenCookieService.class);
        service = new SocialAuthService(
                socialProvider, cognito, compensationPort, users, cookies,
                Mappers.getMapper(AuthenticatedUserMapper.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void socialLoginCreatesCustomerAssignsGroupAndReturnsRefreshedToken(boolean rememberMe) {
        SocialSyncRequest request = request(SocialAuthIntent.LOGIN_OR_REGISTER);
        request.setRememberMe(rememberMe);
        SocialAuthenticationResult identity = identity(true);
        User user = customer();
        when(socialProvider.exchangeSocialCode(request)).thenReturn(identity);
        when(users.findOptionalByEmail("user@example.com")).thenReturn(Optional.empty());
        when(users.createSocialUser(anyString(), anyString(), eq(RoleCode.CUSTOMER), anyString(), anyString()))
                .thenReturn(user);
        when(cognito.refresh("social-refresh", "google_123"))
                .thenReturn(new AuthenticationTokens("access-with-group", null, 3600, "google_123"));
        HttpServletResponse response = mock(HttpServletResponse.class);

        var result = service.sync(request, response);

        assertEquals("access-with-group", result.getAccessToken());
        assertEquals(RoleCode.CUSTOMER, result.getUser().getRole());
        verify(cognito).addUserToGroup("google_123", "CUSTOMER");
        verify(cookies).write(response, "social-refresh", "google_123", rememberMe);
    }

    @Test
    void socialLoginLinksGoogleToExistingLocalUserWithoutChangingRole() {
        SocialSyncRequest request = request(SocialAuthIntent.LOGIN_OR_REGISTER);
        User staff = customer();
        staff.setRole(Role.builder().code(RoleCode.MANAGER).build());
        when(socialProvider.exchangeSocialCode(request)).thenReturn(identity(true));
        when(users.findOptionalByEmail("user@example.com")).thenReturn(Optional.of(staff));
        when(users.hasIdentity(staff.getId(), IdentityProvider.LOCAL)).thenReturn(true);
        when(users.getCognitoUsername("user@example.com", IdentityProvider.LOCAL)).thenReturn("local-uuid");
        when(users.syncSocialIdentity(eq(staff), anyString(), anyString(), anyString(), eq(true))).thenReturn(staff);
        when(cognito.refresh("social-refresh", "local-uuid"))
                .thenReturn(new AuthenticationTokens("new-access", null, 3600, "local-uuid"));

        var result = service.sync(request, mock(HttpServletResponse.class));

        assertEquals(RoleCode.MANAGER, result.getUser().getRole());
        verify(cognito).addUserToGroup("local-uuid", "MANAGER");
        verify(users, never()).createSocialUser(anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void socialLoginRejectsUnverifiedEmail() {
        SocialSyncRequest request = request(SocialAuthIntent.LOGIN_OR_REGISTER);
        when(socialProvider.exchangeSocialCode(request)).thenReturn(identity(false));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.sync(request, mock(HttpServletResponse.class)));

        assertEquals(ErrorCode.SOCIAL_EMAIL_NOT_VERIFIED, exception.getErrorCode());
        verifyNoInteractions(users, cognito);
    }

    @Test
    void loginOnlyDoesNotCreateUnknownSocialUser() {
        SocialSyncRequest request = request(SocialAuthIntent.LOGIN_ONLY);
        when(socialProvider.exchangeSocialCode(request)).thenReturn(identity(true));
        when(users.findOptionalByEmail("user@example.com")).thenReturn(Optional.empty());

        ApiException exception = assertThrows(ApiException.class,
                () -> service.sync(request, mock(HttpServletResponse.class)));

        assertEquals(ErrorCode.USER_NOT_FOUND, exception.getErrorCode());
        verify(users, never()).createSocialUser(anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void newSocialAccountSchedulesCleanupWhenRoleSynchronizationFails() {
        SocialSyncRequest request = request(SocialAuthIntent.LOGIN_OR_REGISTER);
        when(socialProvider.exchangeSocialCode(request)).thenReturn(identity(true));
        when(users.findOptionalByEmail("user@example.com")).thenReturn(Optional.empty());
        when(users.createSocialUser(anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn(customer());
        doThrow(new IllegalStateException("group failure"))
                .when(cognito).addUserToGroup("google_123", "CUSTOMER");

        ApiException exception = assertThrows(ApiException.class,
                () -> service.sync(request, mock(HttpServletResponse.class)));

        assertEquals(ErrorCode.AUTH_PROVIDER_ERROR, exception.getErrorCode());
        verify(compensationPort).scheduleCognitoCleanup("google_123", "cognito-sub");
    }

    @Test
    void linkLocalIdentityRejectsDuplicateLocalIdentity() {
        User user = customer();
        when(users.findByCognitoSub("sub")).thenReturn(user);
        when(users.hasIdentity(user.getId(), IdentityProvider.LOCAL)).thenReturn(true);

        ApiException exception = assertThrows(ApiException.class,
                () -> service.linkLocalIdentity("sub", "username", "Password1!"));

        assertEquals(ErrorCode.RESOURCE_ALREADY_EXISTS, exception.getErrorCode());
        verify(cognito, never()).setPermanentPassword(anyString(), anyString());
    }

    @Test
    void linkLocalIdentityPersistsIdentityBeforeEnablingCognitoPassword() {
        User user = customer();
        when(users.findByCognitoSub("sub")).thenReturn(user);
        when(users.hasIdentity(user.getId(), IdentityProvider.LOCAL)).thenReturn(false);

        service.linkLocalIdentity("sub", "google_123", "Password1!");

        var ordered = inOrder(users, cognito);
        ordered.verify(users).linkLocalIdentity(user, "google_123", "sub");
        ordered.verify(cognito).setPermanentPassword("google_123", "Password1!");
        verify(users, never()).unlinkIdentity(any(), any());
    }

    @Test
    void linkLocalIdentityCompensatesDatabaseWhenCognitoRejectsPassword() {
        User user = customer();
        when(users.findByCognitoSub("sub")).thenReturn(user);
        when(users.hasIdentity(user.getId(), IdentityProvider.LOCAL)).thenReturn(false);
        doThrow(InvalidPasswordException.builder().message("weak password").build())
                .when(cognito).setPermanentPassword("google_123", "weak-pass");

        ApiException exception = assertThrows(ApiException.class,
                () -> service.linkLocalIdentity("sub", "google_123", "weak-pass"));

        assertEquals(ErrorCode.PASSWORD_POLICY_VIOLATED, exception.getErrorCode());
        verify(users).unlinkIdentity(user, IdentityProvider.LOCAL);
    }

    private SocialSyncRequest request(SocialAuthIntent intent) {
        SocialSyncRequest request = new SocialSyncRequest();
        request.setCode("code");
        request.setRedirectUri("http://localhost/callback");
        request.setProvider(AuthProvider.GOOGLE);
        request.setIntent(intent);
        return request;
    }

    private SocialAuthenticationResult identity(boolean verified) {
        return new SocialAuthenticationResult("initial-access", "social-refresh", 3600,
                "google_123", "cognito-sub", "123", " User@Example.com ", "User", verified);
    }

    private User customer() {
        User user = User.builder().email("user@example.com").fullName("User")
                .emailVerified(true).isActive(true)
                .role(Role.builder().code(RoleCode.CUSTOMER).build()).build();
        user.setId(UUID.randomUUID().toString());
        return user;
    }
}
