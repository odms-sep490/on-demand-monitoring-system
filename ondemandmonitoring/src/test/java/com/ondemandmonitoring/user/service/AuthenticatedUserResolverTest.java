package com.ondemandmonitoring.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class AuthenticatedUserResolverTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private IUserIdentityService userIdentityService;

    @InjectMocks
    private AuthenticatedUserResolver resolver;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getCurrentUser_resolvesJwtByCognitoSubject() {
        User expected = user();
        Jwt jwt = jwt("cognito-sub", "user@example.com");
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of()));
        when(userIdentityService.findUserByCognitoSub("cognito-sub")).thenReturn(expected);

        User actual = resolver.getCurrentUser();

        assertThat(actual).isSameAs(expected);
        verify(userRepository, never()).findByEmailIgnoreCase("user@example.com");
    }

    @Test
    void getCurrentUser_fallsBackToJwtEmailWhenSubjectIsNotLinked() {
        User expected = user();
        Jwt jwt = jwt("unlinked-sub", "user@example.com");
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of()));
        when(userIdentityService.findUserByCognitoSub("unlinked-sub"))
                .thenThrow(new ApiException(ErrorCode.USER_NOT_FOUND));
        when(userRepository.findByEmailIgnoreCase("user@example.com"))
                .thenReturn(Optional.of(expected));

        assertThat(resolver.getCurrentUser()).isSameAs(expected);
    }

    @Test
    void getCurrentUser_resolvesNonJwtAuthenticationByUserId() {
        User expected = user();
        String userId = expected.getId().toString();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(userId, null, List.of()));
        when(userRepository.findById(expected.getId())).thenReturn(Optional.of(expected));

        assertThat(resolver.getCurrentUser()).isSameAs(expected);
    }

    @Test
    void getCurrentUser_resolvesNonJwtAuthenticationByEmail() {
        User expected = user();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        "user@example.com", null, List.of()));
        when(userRepository.findByEmailIgnoreCase("user@example.com"))
                .thenReturn(Optional.of(expected));

        assertThat(resolver.getCurrentUser()).isSameAs(expected);
    }

    @Test
    void getCurrentUser_withoutAuthentication_throwsUnauthorized() {
        assertThatThrownBy(resolver::getCurrentUser)
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.UNAUTHORIZED));
    }

    @Test
    void getCurrentUser_withAnonymousAuthentication_throwsUnauthorized() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        assertThatThrownBy(resolver::getCurrentUser)
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.UNAUTHORIZED));
    }

    @Test
    void getCurrentUser_withUnresolvableJwt_throwsUserNotFound() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of()));

        assertThatThrownBy(resolver::getCurrentUser)
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.USER_NOT_FOUND));
    }

    @Test
    void getCurrentUser_withInactiveAccount_throwsAccountDisabled() {
        User inactiveUser = user();
        inactiveUser.setIsActive(false);
        Jwt jwt = jwt("inactive-sub", "inactive@example.com");
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of()));
        when(userIdentityService.findUserByCognitoSub("inactive-sub"))
                .thenReturn(inactiveUser);

        assertThatThrownBy(resolver::getCurrentUser)
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.ACCOUNT_DISABLED));
    }

    private Jwt jwt(String subject, String email) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("email", email)
                .build();
    }

    @Test
    void identityConflictDoesNotFallBackToEmail() {
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt("conflicted-sub", "user@example.com"), List.of()));
        when(userIdentityService.findUserByCognitoSub("conflicted-sub"))
                .thenThrow(new ApiException(ErrorCode.IDENTITY_OWNERSHIP_CONFLICT));

        assertThatThrownBy(resolver::getCurrentUser)
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.IDENTITY_OWNERSHIP_CONFLICT));
        verify(userRepository, never()).findByEmailIgnoreCase("user@example.com");
    }

    private User user() {
        User user = User.builder()
                .email("user@example.com")
                .fullName("User Name")
                .build();
        user.setId(UUID.randomUUID().toString());
        return user;
    }
}
