package com.ondemandmonitoring.config;

import com.ondemandmonitoring.auth.controller.AdminAccountController;
import com.ondemandmonitoring.auth.dto.response.ManagedAccountResponse;
import com.ondemandmonitoring.auth.service.IAdminAccountService;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.context.WebApplicationContext;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

@SpringJUnitConfig(classes = {
        SecurityConfig.class,
        ActiveAccountFilter.class,
        SecurityAuthorizationTest.TestBeans.class,
        AdminAccountController.class
})
@WebAppConfiguration
class SecurityAuthorizationTest {
    @Autowired
    private WebApplicationContext context;
    @Autowired
    private IAdminAccountService adminAccounts;
    @Autowired
    private AuthenticatedUserResolver resolver;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        when(adminAccounts.create(any())).thenReturn(ManagedAccountResponse.builder()
                .email("staff@example.com").role(RoleCode.MANAGER)
                .invitationSent(true).passwordChangeRequired(true).build());
    }

    @Test
    void unauthenticatedRequestReturns401() throws Exception {
        mvc.perform(post("/api/admin/accounts")
                        .with(csrf())
                        .contentType("application/json")
                        .content(requestBody()))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @EnumSource(value = RoleCode.class, names = "ADMIN", mode = EnumSource.Mode.EXCLUDE)
    void everyNonAdminRoleReturns403(RoleCode role) throws Exception {
        setRole(role);
        mvc.perform(post("/api/admin/accounts")
                        .with(csrf())
                        .with(jwt().authorities(() -> "ROLE_" + role.name()))
                        .contentType("application/json")
                        .content(requestBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanCreateManagedAccount() throws Exception {
        setRole(RoleCode.ADMIN);
        mvc.perform(post("/api/admin/accounts")
                        .with(jwt().authorities(() -> "ROLE_ADMIN"))
                        .contentType("application/json")
                        .content(requestBody()))
                .andExpect(status().isCreated());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "/api/auth/refresh", "/api/auth/logout", "/api/v1/auth/refresh", "/api/v1/auth/logout"})
    void cookieAuthenticationRequiresCsrf(String path) throws Exception {
        mvc.perform(post(path)).andExpect(status().isForbidden());
        mvc.perform(post(path).with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden());
        // No controller is registered for these endpoints in this test context.
        mvc.perform(post(path).with(csrf())).andExpect(status().isNotFound());
    }

    @Test
    void corsAllowsCsrfHeaderForRefresh() throws Exception {
        mvc.perform(options("/api/auth/refresh")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type,x-xsrf-token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
                .andExpect(header().string("Access-Control-Allow-Headers", org.hamcrest.Matchers.containsString("x-xsrf-token")));
    }

    private String requestBody() {
        return """
                {"email":"staff@example.com","fullName":"Staff","role":"STAFF"}
                """;
    }

    private void setRole(RoleCode role) {
        when(resolver.getCurrentUser()).thenReturn(User.builder()
                .role(Role.builder().code(role).active(true).build()).isActive(true).build());
    }

    @Configuration
    @EnableWebMvc
    static class TestBeans {
        @Bean
        IAdminAccountService adminAccountService() {
            return mock(IAdminAccountService.class);
        }

        @Bean
        @Qualifier("cognitoAccessTokenDecoder")
        JwtDecoder cognitoAccessTokenDecoder() {
            return mock(JwtDecoder.class);
        }

        @Bean
        AuthenticatedUserResolver authenticatedUserResolver() {
            return mock(AuthenticatedUserResolver.class);
        }
    }
}
