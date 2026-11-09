package com.ondemandmonitoring.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SecurityConfigTest {

    @ParameterizedTest
    @EnumSource(RoleCode.class)
    void mapsEveryBusinessRoleGroup(RoleCode role) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("subject")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("cognito:groups", List.of(role.name()))
                .build();

        var authentication = new SecurityConfig().jwtAuthenticationConverter().convert(jwt);

        assertNotNull(authentication);
        assertTrue(authentication.getAuthorities().stream()
                .anyMatch(authority -> ("ROLE_" + role.name()).equals(authority.getAuthority())));
    }

    @Test
    void mapsOnlySupportedCognitoGroupsToRoles() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("subject")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("cognito:groups", List.of("STAFF", "Google", "UNKNOWN"))
                .build();

        var authentication = new SecurityConfig().jwtAuthenticationConverter().convert(jwt);

        assertNotNull(authentication);
        assertEquals(List.of("ROLE_STAFF"), authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .filter(authority -> authority.startsWith("ROLE_"))
                .toList());
    }

    @Test
    void tokenWithoutGroupsHasNoBusinessAuthority() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("subject")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();

        var authentication = new SecurityConfig().jwtAuthenticationConverter().convert(jwt);

        assertNotNull(authentication);
        assertTrue(authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .noneMatch(authority -> authority.startsWith("ROLE_")));
    }
}
