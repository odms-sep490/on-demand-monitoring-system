package com.ondemandmonitoring.auth.service;

import com.ondemandmonitoring.auth.dto.request.LoginRequest;
import com.ondemandmonitoring.auth.dto.request.FirstLoginPasswordChangeRequest;
import com.ondemandmonitoring.auth.dto.response.AuthResponse;
import com.ondemandmonitoring.auth.mapper.AuthenticatedUserMapper;
import com.ondemandmonitoring.auth.port.out.AuthenticationTokens;
import com.ondemandmonitoring.auth.port.out.IdentityProviderPort;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.user.service.IUserService;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.enumeration.IdentityProvider;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.transaction.Transactional;
import jakarta.servlet.http.HttpServletRequest;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CognitoIdentityProviderException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InvalidPasswordException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.NotAuthorizedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotConfirmedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;

import java.util.Locale;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class LoginService {

    IUserService userService;
    IdentityProviderPort identityProvider;
    RefreshTokenCookieService refreshTokenCookieService;
    AuthenticatedUserMapper authenticatedUserMapper;

    @Transactional
    public AuthResponse login(LoginRequest request, HttpServletResponse response) {
        String email = normalizeEmail(request.getEmail());
        User user = userService.findByEmail(email);
        ensureCanLogin(user);
        String username = userService.getCognitoUsername(email, IdentityProvider.LOCAL);

        try {
            AuthenticationTokens tokens = identityProvider.authenticate(username, request.getPassword());
            if (tokens.requiresChallenge()) {
                return AuthResponse.builder()
                        .status("PASSWORD_CHANGE_REQUIRED")
                        .challengeName(tokens.challengeName())
                        .session(tokens.session())
                        .build();
            }
            userService.recordLogin(user.getId());
            if (tokens.refreshToken() != null) {
                refreshTokenCookieService.write(response, tokens.refreshToken(), tokens.username(), request.isRememberMe());
            }
            return toResponse(tokens, user);
        } catch (NotAuthorizedException exception) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        } catch (UserNotConfirmedException exception) {
            throw new ApiException(ErrorCode.USER_NOT_CONFIRMED);
        } catch (UserNotFoundException exception) {
            throw new ApiException(ErrorCode.USER_NOT_FOUND);
        } catch (CognitoIdentityProviderException exception) {
            String message = exception.awsErrorDetails() == null
                    ? exception.getMessage() : exception.awsErrorDetails().errorMessage();
            throw new ApiException(ErrorCode.AUTH_PROVIDER_ERROR, message);
        }
    }

    @Transactional
    public AuthResponse completeFirstLogin(FirstLoginPasswordChangeRequest request,
                                           HttpServletResponse response) {
        String email = normalizeEmail(request.getEmail());
        User user = userService.findByEmail(email);
        ensureCanLogin(user);
        String username = userService.getCognitoUsername(email, IdentityProvider.LOCAL);

        try {
            AuthenticationTokens tokens = identityProvider.respondToNewPasswordChallenge(
                    username, request.getSession(), request.getNewPassword());
            userService.recordLogin(user.getId());
            if (tokens.refreshToken() != null) {
                refreshTokenCookieService.write(response, tokens.refreshToken(), tokens.username(), request.isRememberMe());
            }
            return toResponse(tokens, user);
        } catch (InvalidPasswordException exception) {
            throw new ApiException(ErrorCode.PASSWORD_POLICY_VIOLATED);
        } catch (NotAuthorizedException exception) {
            throw new ApiException(ErrorCode.REFRESH_TOKEN_INVALID,
                    "The first-login password-change session is invalid or expired");
        } catch (CognitoIdentityProviderException exception) {
            String message = exception.awsErrorDetails() == null
                    ? exception.getMessage() : exception.awsErrorDetails().errorMessage();
            throw new ApiException(ErrorCode.AUTH_PROVIDER_ERROR, message);
        }
    }

    public AuthResponse refresh(HttpServletRequest request) {
        RefreshTokenCookieService.RefreshToken cookie = refreshTokenCookieService.read(request)
                .orElseThrow(() -> new ApiException(ErrorCode.REFRESH_TOKEN_INVALID));
        ensureActive(resolveRefreshUser(cookie.username()));
        try {
            AuthenticationTokens tokens = identityProvider.refresh(cookie.token(), cookie.username());
            return AuthResponse.builder()
                    .accessToken(tokens.accessToken())
                    .expiresIn(tokens.expiresIn())
                    .build();
        } catch (CognitoIdentityProviderException exception) {
            throw new ApiException(ErrorCode.REFRESH_TOKEN_INVALID);
        }
    }

    public void logout(String accessToken, HttpServletRequest request, HttpServletResponse response) {
        refreshTokenCookieService.read(request).ifPresent(cookie -> {
            try {
                identityProvider.revoke(cookie.token());
            } catch (CognitoIdentityProviderException ignored) {
                // Logout remains idempotent when the refresh token is already invalid.
            }
        });
        if (accessToken != null && !accessToken.isBlank()) {
            try {
                identityProvider.globalSignOut(accessToken);
            } catch (CognitoIdentityProviderException ignored) {
                // Logout remains idempotent when the access token is already expired.
            }
        }
        refreshTokenCookieService.clear(response);
    }

    private void ensureCanLogin(User user) {
        ensureActive(user);
        if (!Boolean.TRUE.equals(user.getEmailVerified())) {
            throw new ApiException(ErrorCode.USER_NOT_CONFIRMED);
        }
    }

    private void ensureActive(User user) {
        if (!Boolean.TRUE.equals(user.getIsActive())) {
            throw new ApiException(ErrorCode.ACCOUNT_DISABLED);
        }
    }

    private User resolveRefreshUser(String cognitoUsername) {
        try {
            return userService.findByCognitoUsername(cognitoUsername);
        } catch (ApiException exception) {
            if (exception.getErrorCode() == ErrorCode.USER_NOT_FOUND) {
                throw new ApiException(ErrorCode.REFRESH_TOKEN_INVALID);
            }
            throw exception;
        }
    }

    private AuthResponse toResponse(AuthenticationTokens tokens, User user) {
        return AuthResponse.builder()
                .accessToken(tokens.accessToken())
                .status("AUTHENTICATED")
                .expiresIn(tokens.expiresIn())
                .user(authenticatedUserMapper.toResponse(user))
                .build();
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

}
