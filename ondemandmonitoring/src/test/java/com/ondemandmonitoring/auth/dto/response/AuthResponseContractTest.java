package com.ondemandmonitoring.auth.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthResponseContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void authenticatedUserSummary_doesNotExposeInternalAccountState() {
        AuthResponse response = AuthResponse.builder()
                .accessToken("access-token")
                .user(AuthenticatedUserResponse.builder()
                        .id(UUID.randomUUID())
                        .fullName("User Name")
                        .email("user@example.com")
                        .role(RoleCode.CUSTOMER)
                        .build())
                .build();

        JsonNode user = objectMapper.valueToTree(response).get("user");

        assertThat(user.has("id")).isTrue();
        assertThat(user.has("fullName")).isTrue();
        assertThat(user.has("email")).isTrue();
        assertThat(user.has("role")).isTrue();
        assertThat(user.has("emailVerified")).isFalse();
        assertThat(user.has("isActive")).isFalse();
        assertThat(user.has("linkedProviders")).isFalse();
        assertThat(user.has("customerProfile")).isFalse();
    }
}
