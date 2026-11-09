package com.ondemandmonitoring.auth.controller;

import com.ondemandmonitoring.auth.dto.request.LinkLocalIdentityRequest;
import com.ondemandmonitoring.auth.service.IAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import static org.mockito.Mockito.*;

class AuthControllerTest {
    @Test
    void localLinkUsesCanonicalUsernameFromAccessTokenRatherThanSubject() {
        var service = mock(IAuthService.class);
        var controller = new AuthController(service);
        var request = mock(LinkLocalIdentityRequest.class);
        var jwt = Jwt.withTokenValue("access-token").header("alg", "RS256")
                .subject("cognito-sub").claim("token_use", "access")
                .claim("username", "google_123").build();
        controller.linkLocalIdentity(jwt, request);
        verify(service).linkLocalIdentity("cognito-sub", "google_123", request);
    }

    @Test
    void localLinkFallsBackToSubjectWhenCanonicalUsernameIsAbsent() {
        var service = mock(IAuthService.class);
        var request = mock(LinkLocalIdentityRequest.class);
        var jwt = Jwt.withTokenValue("access-token").header("alg", "RS256")
                .subject("local-sub").claim("token_use", "access").build();
        new AuthController(service).linkLocalIdentity(jwt, request);
        verify(service).linkLocalIdentity("local-sub", "local-sub", request);
    }
}
