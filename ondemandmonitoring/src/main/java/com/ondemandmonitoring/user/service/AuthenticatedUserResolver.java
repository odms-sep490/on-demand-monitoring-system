package com.ondemandmonitoring.user.service;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.repository.UserRepository;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class AuthenticatedUserResolver {

    UserRepository userRepository;
    IUserIdentityService userIdentityService;

    @Transactional(readOnly = true)
    public String getCurrentUserId() {
        return getCurrentUser().getId();
    }

    @Transactional(readOnly = true)
    public User getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "User authentication is required");
        }

        User user = authentication.getPrincipal() instanceof Jwt jwt
                ? resolveJwtUser(jwt)
                : resolveByAuthenticationName(authentication.getName());
        ensureActive(user);
        return user;
    }

    private User resolveJwtUser(Jwt jwt) {
        String cognitoSub = jwt.getSubject();
        if (cognitoSub != null && !cognitoSub.isBlank()) {
            try {
                return userIdentityService.findUserByCognitoSub(cognitoSub);
            } catch (ApiException exception) {
                if (exception.getErrorCode() != ErrorCode.USER_NOT_FOUND) {
                    throw exception;
                }
            }
        }

        String email = jwt.getClaimAsString("email");
        if (email != null && !email.isBlank()) {
            return userRepository.findByEmailIgnoreCase(email)
                    .orElseThrow(() -> new ApiException(
                            ErrorCode.USER_NOT_FOUND, "User not found for email: " + email));
        }

        throw unresolvedIdentity();
    }

    private User resolveByAuthenticationName(String authenticationName) {
        if (authenticationName == null || authenticationName.isBlank()) {
            throw unresolvedIdentity();
        }

        String identifier = authenticationName.trim();

        return userRepository.findById(identifier)
                .orElseGet(() ->
                        userRepository.findByEmailIgnoreCase(identifier)
                                .orElseThrow(() -> new ApiException(
                                        ErrorCode.USER_NOT_FOUND,
                                        "User not found with identifier: " + identifier
                                ))
                );
    }

    private ApiException unresolvedIdentity() {
        return new ApiException(
                ErrorCode.USER_NOT_FOUND, "Authenticated user identity could not be resolved");
    }

    private void ensureActive(User user) {
        if (!Boolean.TRUE.equals(user.getIsActive())) {
            throw new ApiException(ErrorCode.ACCOUNT_DISABLED);
        }
    }
}
