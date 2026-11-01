package com.ondemandmonitoring.role;

import com.ondemandmonitoring.auth.port.out.IdentityProviderPort;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.role.infrastructure.RoleDataInitializer;
import com.ondemandmonitoring.user.config.UserSeedDataInitializer;
import com.ondemandmonitoring.user.repository.UserIdentityRepository;
import com.ondemandmonitoring.user.repository.UserRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Application startup + Hibernate + real PostgreSQL, without AWS calls or
 * production data.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "ODMS_ROLE_TEST_JDBC_URL", matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/role_verification(_legacy)?")
class RoleStartupPostgresTest {
        @Autowired
        org.springframework.context.ApplicationContext context;
        @Autowired
        JdbcTemplate jdbc;
        @Autowired
        RoleDataInitializer roles;
        @Autowired
        UserSeedDataInitializer seed;
        @Autowired
        UserRepository users;
        @Autowired
        UserIdentityRepository identities;
        @MockitoBean(name = "cognitoAccessTokenDecoder")
        JwtDecoder accessDecoder;
        @MockitoBean(name = "cognitoIdTokenDecoder")
        JwtDecoder idDecoder;
        @MockitoBean
        IdentityProviderPort identityProvider;
        @MockitoBean
        software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient cognito;
        @MockitoBean
        software.amazon.awssdk.services.s3.S3Client s3;
        @MockitoBean
        software.amazon.awssdk.services.s3.presigner.S3Presigner presigner;

        @DynamicPropertySource
        static void database(DynamicPropertyRegistry properties) {
                properties.add("spring.datasource.url", () -> System.getenv("ODMS_ROLE_TEST_JDBC_URL"));
                properties.add("spring.datasource.username", () -> "role_test");
                properties.add("spring.datasource.password", () -> "role_test_only");
                properties.add("spring.jpa.hibernate.ddl-auto", () -> "create");
                properties.add("spring.jpa.open-in-view", () -> "false");
                properties.add("spring.jpa.show-sql", () -> "false");
                properties.add("auth.outbox.fixed-delay-ms", () -> "3600000");
                properties.add("app.seed.demo-drones", () -> "false");
                properties.add("app.media.sqs.enabled", () -> "false");
                properties.add("app.media.reconciliation.enabled", () -> "false");
                properties.add("mission.replanning.enabled", () -> "false");
        }

        @Test
        void startsWithFourRolesAndRepeatedInitializersPreserveUserIdsAndIdentities() {
                assertThat(context.getBeansOfType(com.ondemandmonitoring.media.messaging.SqsMediaEventConsumer.class))
                                .isEmpty();
                assertThat(jdbc.queryForList("SELECT code FROM roles ORDER BY code", String.class))
                                .containsExactly("ADMIN", "CUSTOMER", "MANAGER", "STAFF");
                List<String> before = jdbc.queryForList("SELECT id FROM users ORDER BY id", String.class);
                assertThat(before).hasSize(14);
                roles.run(null);
                seed.run(null);
                roles.run(null);
                seed.run(null);
                assertThat(jdbc.queryForList("SELECT id FROM users ORDER BY id", String.class)).isEqualTo(before);
                assertThat(count("user_identities")).isEqualTo(14);
                assertThat(count("customer_profiles")).isEqualTo(4);
                verifyNoInteractions(identityProvider);
                verifyNoInteractions(cognito, s3, presigner);
        }

        @Test
        void roleAndIdentityCanBeReadOutsideTransactionWithOpenInViewDisabled() {
                assertThat(users.findByEmailIgnoreCase("seed.manager@odms.local").orElseThrow().getRole().getCode())
                                .isEqualTo(RoleCode.MANAGER);
                assertThat(identities.findByCognitoSub("c9cab55c-0081-705a-a1e0-4358cd45d47e")
                                .orElseThrow().getUser().getRole().getCode()).isEqualTo(RoleCode.STAFF);
                assertThat(identities.findByCognitoUsername("39ea75ec-c001-7087-152e-e76ebbf4740b")
                                .orElseThrow().getUser().getRole().getCode()).isEqualTo(RoleCode.CUSTOMER);
        }

        @Test
        void repeatedSeedsDoNotOverwriteExistingAccountData() {
                var manager = users.findByEmailIgnoreCase("seed.manager.2@odms.local").orElseThrow();
                jdbc.update("UPDATE users SET full_name = 'Edited Manager', is_active = false WHERE id = ?",
                                manager.getId());
                try {
                        seed.run(null);
                        var after = users.findByEmailIgnoreCase("seed.manager.2@odms.local").orElseThrow();
                        assertThat(after.getFullName()).isEqualTo("Edited Manager");
                        assertThat(after.getIsActive()).isFalse();
                        assertThat(after.getId()).isEqualTo(manager.getId());
                        assertThat(count("users")).isEqualTo(14);
                        assertThat(count("user_identities")).isEqualTo(14);
                } finally {
                        jdbc.update("UPDATE users SET full_name = ?, is_active = ? WHERE id = ?",
                                        manager.getFullName(), manager.getIsActive(), manager.getId());
                }
        }

        @Test
        void duplicateEmailIsRejectedAfterJpaNormalizesCaseAndWhitespace() {
                var existing = users.findByEmailIgnoreCase("seed.manager@odms.local").orElseThrow();
                var duplicate = com.ondemandmonitoring.user.domain.User.builder()
                                .fullName("Duplicate verification user").email(" SEED.MANAGER@odms.local ")
                                .role(existing.getRole()).build();
                assertThatThrownBy(() -> users.saveAndFlush(duplicate))
                                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
                assertThat(count("users")).isEqualTo(14);
        }

        @Test
        void localAndGoogleIdentitiesShareOneRoleAndDuplicateProviderIsRejected() {
                var user = users.findByEmailIgnoreCase("seed.drone.operator@odms.local").orElseThrow();
                var google = com.ondemandmonitoring.user.domain.UserIdentity.builder().user(user)
                                .provider(com.ondemandmonitoring.user.enumeration.IdentityProvider.GOOGLE)
                                .cognitoUsername("google_verification_subject").cognitoSub("verification-google-sub")
                                .build();
                var saved = identities.saveAndFlush(google);
                try {
                        var linked = identities.findByCognitoSub("verification-google-sub").orElseThrow().getUser();
                        assertThat(linked.getId()).isEqualTo(user.getId());
                        assertThat(linked.getRole().getCode()).isEqualTo(RoleCode.STAFF);
                        var duplicateLocal = com.ondemandmonitoring.user.domain.UserIdentity.builder().user(user)
                                        .provider(com.ondemandmonitoring.user.enumeration.IdentityProvider.LOCAL)
                                        .cognitoUsername("duplicate_local").cognitoSub("duplicate-local-sub").build();
                        assertThatThrownBy(() -> identities.saveAndFlush(duplicateLocal))
                                        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
                } finally {
                        identities.deleteById(saved.getId());
                }
                assertThat(count("users")).isEqualTo(14);
                assertThat(count("user_identities")).isEqualTo(14);
        }

        private int count(String table) {
                return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        }
}
