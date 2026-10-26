package com.ondemandmonitoring.auth.service;

import com.ondemandmonitoring.auth.dto.request.SocialSyncRequest;
import com.ondemandmonitoring.auth.dto.response.AuthResponse;
import com.ondemandmonitoring.auth.enumeration.AuthProvider;
import com.ondemandmonitoring.auth.enumeration.SocialAuthIntent;
import com.ondemandmonitoring.auth.port.out.SocialAuthenticationResult;
import com.ondemandmonitoring.auth.port.out.SocialIdentityProviderPort;
import com.ondemandmonitoring.auth.port.out.IdentityProviderPort;
import com.ondemandmonitoring.auth.port.out.AuthenticationTokens;
import com.ondemandmonitoring.auth.port.out.AuthCompensationPort;
import com.ondemandmonitoring.auth.mapper.AuthenticatedUserMapper;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.enumeration.IdentityProvider;
import com.ondemandmonitoring.user.service.IUserService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CognitoIdentityProviderException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InvalidPasswordException;

import java.util.Locale;

@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class SocialAuthService {

    SocialIdentityProviderPort identityProvider;
    IdentityProviderPort cognitoUserDirectory;
    AuthCompensationPort compensationPort;
    IUserService userService;
    RefreshTokenCookieService refreshTokenCookieService;
    AuthenticatedUserMapper authenticatedUserMapper;

    @Transactional
    public AuthResponse sync(SocialSyncRequest request, HttpServletResponse response) {
        validateProvider(request.getProvider());
        SocialAuthenticationResult identity;
        try {
            identity = identityProvider.exchangeSocialCode(request);
        } catch (IllegalStateException exception) {
            throw new ApiException(ErrorCode.AUTH_PROVIDER_ERROR, "Social authentication failed");
        }

        if (!identity.emailVerified() || identity.email() == null || identity.email().isBlank()) {
            throw new ApiException(ErrorCode.SOCIAL_EMAIL_NOT_VERIFIED);
        }

        String email = identity.email().trim().toLowerCase(Locale.ROOT);
        var existingUser = userService.findOptionalByEmail(email);
        boolean accountCreated = existingUser.isEmpty();
        String effectiveCognitoUsername = identity.username();
        User user;
        try {
            if (existingUser.isPresent()) {
                User existing = existingUser.get();
            if (userService.hasIdentity(existing.getId(), IdentityProvider.LOCAL)) {
                effectiveCognitoUsername = userService.getCognitoUsername(email, IdentityProvider.LOCAL);
                }
                user = syncExistingSocialIdentity(existing, identity);
            } else {
                user = createUser(request, identity, email);
            }

            if (!Boolean.TRUE.equals(user.getIsActive())) {
                throw new ApiException(ErrorCode.ACCOUNT_DISABLED);
            }

            cognitoUserDirectory.addUserToGroup(effectiveCognitoUsername, user.getRole().getCode().name());
        } catch (RuntimeException exception) {
            if (exception instanceof ApiException) {
                throw exception;
            }
            log.error("Unable to synchronize social account. email={}, cognitoUsername={}, subject={}",
                    email, identity.username(), identity.subject(), exception);
            if (accountCreated) {
                compensationPort.scheduleCognitoCleanup(identity.username(), identity.subject());
            }
            throw new ApiException(ErrorCode.AUTH_PROVIDER_ERROR, "Unable to synchronize account role");
        }

        userService.recordLogin(user.getId());
        
        AuthenticationTokens responseTokens = refreshAfterGroupAssignment(
                identity,
                effectiveCognitoUsername,
                email);
        if (responseTokens.refreshToken() != null) {
            refreshTokenCookieService.write(
                    response,
                    responseTokens.refreshToken(),
                    responseTokens.username(),
                    request.isRememberMe());
        }

        return AuthResponse.builder()
                .accessToken(responseTokens.accessToken())
                .expiresIn(responseTokens.expiresIn())
                .user(authenticatedUserMapper.toResponse(user))
                .build();
    }

    private AuthenticationTokens refreshAfterGroupAssignment(
            SocialAuthenticationResult identity,
            String cognitoUsername,
            String email) {
        if (identity.refreshToken() == null || identity.refreshToken().isBlank()) {
            log.warn("Social login did not return a refresh token; the initial access token may not contain groups. email={}",
                    email);
            return new AuthenticationTokens(
                    identity.accessToken(),
                    null,
                    identity.expiresIn(),
                    cognitoUsername);
        }

        try {
            AuthenticationTokens refreshed = cognitoUserDirectory.refresh(
                    identity.refreshToken(),
                    cognitoUsername);
            return new AuthenticationTokens(
                    refreshed.accessToken(),
                    refreshed.refreshToken() == null
                            ? identity.refreshToken()
                            : refreshed.refreshToken(),
                    refreshed.expiresIn(),
                    cognitoUsername);
        } catch (CognitoIdentityProviderException exception) {
            log.error("Unable to refresh social token after group assignment. email={}, cognitoUsername={}",
                    email, cognitoUsername, exception);
            throw new ApiException(
                    ErrorCode.AUTH_PROVIDER_ERROR,
                    "Unable to refresh social token after role synchronization");
        }
    }

    public void linkLocalIdentity(String cognitoSub, String cognitoUsername, String password) {
        User user = userService.findByCognitoSub(cognitoSub);
        if (userService.hasIdentity(user.getId(), IdentityProvider.LOCAL)) {
            throw new ApiException(ErrorCode.RESOURCE_ALREADY_EXISTS,
                    "A local login is already linked to this account");
        }

        userService.linkLocalIdentity(user, cognitoUsername, cognitoSub);
        try {
            cognitoUserDirectory.setPermanentPassword(cognitoUsername, password);
        } catch (RuntimeException exception) {
            compensateLocalIdentityLink(user, exception);
            throw mapLocalIdentityProviderFailure(exception);
        }
    }

    private void compensateLocalIdentityLink(User user, RuntimeException originalException) {
        try {
            userService.unlinkIdentity(user, IdentityProvider.LOCAL);
        } catch (RuntimeException compensationException) {
            originalException.addSuppressed(compensationException);
            log.error("Unable to compensate LOCAL identity link for user {}",
                    user.getId(), compensationException);
        }
    }

    private ApiException mapLocalIdentityProviderFailure(RuntimeException exception) {
        if (exception instanceof InvalidPasswordException) {
            return new ApiException(ErrorCode.PASSWORD_POLICY_VIOLATED);
        }
        if (exception instanceof ApiException apiException) {
            return apiException;
        }
        return new ApiException(ErrorCode.AUTH_PROVIDER_ERROR,
                "Unable to enable local login for this account");
    }

    private User syncExistingSocialIdentity(User user, SocialAuthenticationResult identity) {
        return userService.syncSocialIdentity(
                user,
                identity.username(),
                identity.subject(),
                identity.fullName(),
                true);
    }

    private User createUser(SocialSyncRequest request, SocialAuthenticationResult identity, String email) {
        if (request.getIntent() == SocialAuthIntent.LOGIN_ONLY) {
            throw new ApiException(ErrorCode.USER_NOT_FOUND);
        }
        return userService.createSocialUser(
                email,
                identity.fullName(),
                RoleCode.CUSTOMER,
                identity.username(),
                identity.subject());
    }

    private void validateProvider(AuthProvider provider) {
        if (provider != AuthProvider.GOOGLE) {
            throw new ApiException(ErrorCode.SOCIAL_PROVIDER_UNSUPPORTED);
        }
    }
}
