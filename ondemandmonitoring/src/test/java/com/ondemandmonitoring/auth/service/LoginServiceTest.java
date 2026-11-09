package com.ondemandmonitoring.auth.service;

import com.ondemandmonitoring.auth.dto.request.LoginRequest;
import com.ondemandmonitoring.auth.dto.request.FirstLoginPasswordChangeRequest;
import com.ondemandmonitoring.auth.port.out.AuthenticationTokens;
import com.ondemandmonitoring.auth.port.out.IdentityProviderPort;
import com.ondemandmonitoring.auth.mapper.AuthenticatedUserMapper;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.enumeration.IdentityProvider;
import com.ondemandmonitoring.user.service.IUserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapstruct.factory.Mappers;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoginServiceTest {
    private IUserService users;
    private IdentityProviderPort identityProvider;
    private RefreshTokenCookieService cookies;
    private LoginService service;

    @BeforeEach
    void setUp() {
        users = mock(IUserService.class);
        identityProvider = mock(IdentityProviderPort.class);
        cookies = mock(RefreshTokenCookieService.class);
        service = new LoginService(users, identityProvider, cookies,
                Mappers.getMapper(AuthenticatedUserMapper.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void loginReturnsProfileAndWritesRefreshCookieWithCognitoUsername(boolean rememberMe) {
        User user = activeUser();
        LoginRequest request = loginRequest();
        request.setRememberMe(rememberMe);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(users.findByEmail("user@example.com")).thenReturn(user);
        when(users.getCognitoUsername("user@example.com", IdentityProvider.LOCAL)).thenReturn("uuid-user");
        when(identityProvider.authenticate("uuid-user", "Password1!"))
                .thenReturn(new AuthenticationTokens("access", "refresh", 3600, "uuid-user"));

        var result = service.login(request, response);

        assertEquals("AUTHENTICATED", result.getStatus());
        verify(identityProvider).authenticate("uuid-user", "Password1!");
        verify(identityProvider, never()).addUserToGroup(anyString(), anyString());
        assertEquals(RoleCode.CUSTOMER, result.getUser().getRole());
        verify(cookies).write(response, "refresh", "uuid-user", rememberMe);
        verify(users).recordLogin(user.getId());
    }

    @Test
    void loginReturnsPasswordChangeChallengeWithoutCookie() {
        User user = activeUser();
        when(users.findByEmail(anyString())).thenReturn(user);
        when(users.getCognitoUsername(anyString(), any())).thenReturn("uuid-user");
        when(identityProvider.authenticate(anyString(), anyString()))
                .thenReturn(AuthenticationTokens.challenge("uuid-user", "NEW_PASSWORD_REQUIRED", "session"));

        var result = service.login(loginRequest(), mock(HttpServletResponse.class));

        assertEquals("PASSWORD_CHANGE_REQUIRED", result.getStatus());
        assertEquals("session", result.getSession());
        verifyNoInteractions(cookies);
        verify(users, never()).recordLogin(any());
    }

    @Test
    void disabledUserCannotLogin() {
        User user = activeUser();
        user.setIsActive(false);
        when(users.findByEmail(anyString())).thenReturn(user);

        ApiException exception = assertThrows(ApiException.class,
                () -> service.login(loginRequest(), mock(HttpServletResponse.class)));
        assertEquals(ErrorCode.ACCOUNT_DISABLED, exception.getErrorCode());
        verifyNoInteractions(identityProvider);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void completeFirstLoginRespondsToChallengeAndWritesCookie(boolean rememberMe) {
        User user = activeUser();
        FirstLoginPasswordChangeRequest request = new FirstLoginPasswordChangeRequest();
        request.setEmail("user@example.com");
        request.setSession("challenge-session");
        request.setNewPassword("NewPassword1!");
        request.setRememberMe(rememberMe);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(users.findByEmail("user@example.com")).thenReturn(user);
        when(users.getCognitoUsername("user@example.com", IdentityProvider.LOCAL)).thenReturn("uuid-user");
        when(identityProvider.respondToNewPasswordChallenge("uuid-user", "challenge-session", "NewPassword1!"))
                .thenReturn(new AuthenticationTokens("access", "refresh", 3600, "uuid-user"));

        var result = service.completeFirstLogin(request, response);

        assertEquals("AUTHENTICATED", result.getStatus());
        verify(identityProvider, never()).addUserToGroup(anyString(), anyString());
        verify(cookies).write(response, "refresh", "uuid-user", rememberMe);
        verify(users).recordLogin(user.getId());
    }

    @Test
    void refreshUsesTokenAndCognitoUsernameFromCookie() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(users.findByCognitoUsername("uuid-user")).thenReturn(activeUser());
        when(cookies.read(request)).thenReturn(Optional.of(
                new RefreshTokenCookieService.RefreshToken("refresh", "uuid-user")));
        when(identityProvider.refresh("refresh", "uuid-user"))
                .thenReturn(new AuthenticationTokens("new-access", null, 3600, "uuid-user"));

        var result = service.refresh(request);

        assertEquals("new-access", result.getAccessToken());
        verify(identityProvider).refresh("refresh", "uuid-user");
        verify(identityProvider, never()).addUserToGroup(anyString(), anyString());
    }

    @Test
    void disabledUserCannotRefreshToken() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        User user = activeUser();
        user.setIsActive(false);
        when(cookies.read(request)).thenReturn(Optional.of(
                new RefreshTokenCookieService.RefreshToken("refresh", "uuid-user")));
        when(users.findByCognitoUsername("uuid-user")).thenReturn(user);

        ApiException exception = assertThrows(ApiException.class,
                () -> service.refresh(request));

        assertEquals(ErrorCode.ACCOUNT_DISABLED, exception.getErrorCode());
        verify(identityProvider, never()).refresh(anyString(), anyString());
    }

    @Test
    void refreshWithUnknownIdentity_returnsInvalidRefreshToken() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(cookies.read(request)).thenReturn(Optional.of(
                new RefreshTokenCookieService.RefreshToken("refresh", "missing-user")));
        when(users.findByCognitoUsername("missing-user"))
                .thenThrow(new ApiException(ErrorCode.USER_NOT_FOUND));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.refresh(request));

        assertEquals(ErrorCode.REFRESH_TOKEN_INVALID, exception.getErrorCode());
        verify(identityProvider, never()).refresh(anyString(), anyString());
    }

    @Test
    void logoutRevokesBothTokenTypesAndClearsCookie() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(cookies.read(request)).thenReturn(Optional.of(
                new RefreshTokenCookieService.RefreshToken("refresh", "uuid-user")));

        service.logout("access", request, response);

        verify(identityProvider).revoke("refresh");
        verify(identityProvider).globalSignOut("access");
        verify(cookies).clear(response);
    }

    private LoginRequest loginRequest() {
        LoginRequest request = new LoginRequest();
        request.setEmail(" User@Example.com ");
        request.setPassword("Password1!");
        return request;
    }

    private User activeUser() {
        User user = User.builder()
                .email("user@example.com")
                .fullName("User")
                .emailVerified(true)
                .isActive(true)
                .role(Role.builder().code(RoleCode.CUSTOMER).build())
                .build();
        user.setId(UUID.randomUUID().toString());
        return user;
    }
}
