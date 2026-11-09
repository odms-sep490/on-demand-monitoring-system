package com.ondemandmonitoring.config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import static org.assertj.core.api.Assertions.assertThat;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerExceptionResolver;

class ActiveAccountFilterTest {

    private AuthenticatedUserResolver authenticatedUserResolver;
    private HandlerExceptionResolver exceptionResolver;
    private ActiveAccountFilter filter;

    @BeforeEach
    void setUp() {
        authenticatedUserResolver = mock(AuthenticatedUserResolver.class);
        exceptionResolver = mock(HandlerExceptionResolver.class);
        filter = new ActiveAccountFilter(authenticatedUserResolver, exceptionResolver);
        when(authenticatedUserResolver.getCurrentUser()).thenReturn(User.builder()
                .role(Role.builder().code(RoleCode.CUSTOMER).active(true).build()).isActive(true).build());
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        "user@example.com", null, java.util.List.of()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void protectedRequest_withActiveAccount_continuesChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/me");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(authenticatedUserResolver).getCurrentUser();
        verify(chain).doFilter(request, response);
    }

    @Test
    void protectedRequest_withInactiveAccount_delegatesAccountDisabledResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/me");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        ApiException failure = new ApiException(ErrorCode.ACCOUNT_DISABLED);
        when(authenticatedUserResolver.getCurrentUser()).thenThrow(failure);

        filter.doFilter(request, response, chain);

        verify(exceptionResolver).resolveException(request, response, null, failure);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void accountLookupPreservesAuthoritiesFromJwtEvenWhenDatabaseRoleDiffers() throws Exception {
        when(authenticatedUserResolver.getCurrentUser()).thenReturn(User.builder()
                .role(Role.builder().code(RoleCode.MANAGER).active(true).build()).isActive(true).build());
        Jwt token = Jwt.withTokenValue("old-token").header("alg", "RS256").subject("user")
                .claim("cognito:groups", java.util.List.of("STAFF")).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token,
                java.util.List.of(new SimpleGrantedAuthority("ROLE_STAFF"))));
        filter.doFilter(new MockHttpServletRequest("GET", "/api/missions"), new MockHttpServletResponse(),
                mock(FilterChain.class));
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting("authority").containsExactly("ROLE_STAFF");
    }

    @Test
    void publicAuthRequest_skipsAccountLookup() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/logout");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(authenticatedUserResolver, never()).getCurrentUser();
        verify(chain).doFilter(request, response);
    }
}
