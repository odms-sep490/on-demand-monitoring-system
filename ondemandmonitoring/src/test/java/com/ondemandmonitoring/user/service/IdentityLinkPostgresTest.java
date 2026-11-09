package com.ondemandmonitoring.user.service;

import com.ondemandmonitoring.auth.mapper.AuthenticatedUserMapper;
import com.ondemandmonitoring.auth.port.out.*;
import com.ondemandmonitoring.auth.service.RefreshTokenCookieService;
import com.ondemandmonitoring.auth.service.SocialAuthService;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.domain.UserIdentity;
import com.ondemandmonitoring.user.enumeration.IdentityProvider;
import com.ondemandmonitoring.user.repository.UserIdentityRepository;
import com.ondemandmonitoring.user.service.impl.UserIdentityServiceImpl;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InvalidPasswordException;

import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt-in PostgreSQL/JPA integration; never loads application.yaml or connects to the dev database. */
@SpringJUnitConfig(IdentityLinkPostgresTest.Config.class)
@EnabledIfEnvironmentVariable(named = "ODMS_IDENTITY_TEST_JDBC_URL",
        matches = "jdbc:postgresql://127[.]0[.]0[.]1:(?!5433/)[0-9]+/identity_verification")
class IdentityLinkPostgresTest {
    @Autowired IUserIdentityService identities;
    @Autowired UserIdentityRepository repository;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate transaction;
    @Autowired SocialAuthService social;
    @Autowired IdentityProviderPort cognito;
    private User user;
    private String sub;
    private String username;

    @BeforeEach
    void prepare() {
        reset(cognito);
        sub = UUID.randomUUID().toString();
        username = "google_" + sub;
        user = transaction.execute(status -> {
            Role role = entityManager.createQuery("select r from Role r", Role.class)
                    .getResultStream().findFirst().orElseGet(() -> {
                        Role created = Role.builder().code(RoleCode.CUSTOMER).name("Customer").active(true).build();
                        entityManager.persist(created);
                        return created;
                    });
            User created = User.builder().fullName("Identity test").email(sub + "@example.test")
                    .role(role).isActive(true).emailVerified(true).build();
            entityManager.persist(created);
            return created;
        });
        identities.create(user, IdentityProvider.GOOGLE, username, sub);
    }

    @Test
    void linkedProvidersResolveSameUserForSubAndUsername() {
        social.linkLocalIdentity(sub, username, "Test-password1!");
        assertThat(repository.findAllByCognitoSub(sub)).hasSize(2);
        assertThat(identities.findUserByCognitoSub(sub).getId()).isEqualTo(user.getId());
        assertThat(identities.findUserByCognitoUsername(username).getId()).isEqualTo(user.getId());
        assertThat(identities.findUserByCognitoSub(sub).getRole().getCode()).isEqualTo(RoleCode.CUSTOMER);
        assertError(() -> social.linkLocalIdentity(sub, username, "Another-password1!"), ErrorCode.RESOURCE_ALREADY_EXISTS);
        verify(cognito, times(1)).setPermanentPassword(anyString(), anyString());
    }

    @Test
    void cognitoRejectionRollsBackLocalIdentityAndAllowsRetry() {
        doThrow(InvalidPasswordException.builder().message("Rejected").build())
                .when(cognito).setPermanentPassword(username, "Rejected-password1!");
        assertError(() -> social.linkLocalIdentity(sub, username, "Rejected-password1!"), ErrorCode.PASSWORD_POLICY_VIOLATED);
        assertThat(repository.findAllByCognitoSub(sub)).hasSize(1);
        assertThat(identities.hasIdentity(user.getId(), IdentityProvider.LOCAL)).isFalse();
        social.linkLocalIdentity(sub, username, "Accepted-password1!");
        assertThat(repository.findAllByCognitoSub(sub)).hasSize(2);
    }

    @Test
    void concurrentLocalPasswordRequestsOnlyCallCognitoOnce() throws Exception {
        List<Object> outcomes = concurrently(
                () -> social.linkLocalIdentity(sub, username, "First-password1!"),
                () -> social.linkLocalIdentity(sub, username, "Second-password1!"));
        assertThat(outcomes).containsExactlyInAnyOrder("ok", ErrorCode.RESOURCE_ALREADY_EXISTS);
        assertThat(repository.findAllByCognitoSub(sub)).hasSize(2);
        verify(cognito, times(1)).setPermanentPassword(anyString(), anyString());
    }

    @Test
    void corruptedSubAndUsernameAcrossUsersFailClosed() {
        User other = anotherUser();
        transaction.executeWithoutResult(status -> repository.saveAndFlush(UserIdentity.builder()
                .user(other).provider(IdentityProvider.LOCAL).cognitoSub(sub).cognitoUsername(username).build()));
        assertError(() -> identities.findUserByCognitoSub(sub), ErrorCode.IDENTITY_OWNERSHIP_CONFLICT);
        assertError(() -> identities.findUserByCognitoUsername(username), ErrorCode.IDENTITY_OWNERSHIP_CONFLICT);
        assertError(() -> social.linkLocalIdentity(sub, username, "Password1!"), ErrorCode.IDENTITY_OWNERSHIP_CONFLICT);
        verifyNoInteractions(cognito);
    }

    @Test
    void concurrentClaimsOfPreviouslyAbsentSubCannotCrossUsers() throws Exception {
        String unclaimed = UUID.randomUUID().toString();
        User other = anotherUser();
        List<Object> outcomes = concurrently(
                () -> identities.create(user, IdentityProvider.LOCAL, "local_" + unclaimed, unclaimed),
                () -> identities.create(other, IdentityProvider.GOOGLE, "google_" + unclaimed, unclaimed));
        assertThat(outcomes).containsExactlyInAnyOrder("ok", ErrorCode.IDENTITY_OWNERSHIP_CONFLICT);
        assertThat(repository.findAllByCognitoSub(unclaimed)).hasSize(1);
    }

    @Test
    void concurrentClaimsOfPreviouslyAbsentUsernameCannotCrossUsers() throws Exception {
        String unclaimed = "username_" + UUID.randomUUID();
        User other = anotherUser();
        List<Object> outcomes = concurrently(
                () -> identities.create(user, IdentityProvider.LOCAL, unclaimed, UUID.randomUUID().toString()),
                () -> identities.create(other, IdentityProvider.GOOGLE, unclaimed, UUID.randomUUID().toString()));
        assertThat(outcomes).containsExactlyInAnyOrder("ok", ErrorCode.IDENTITY_OWNERSHIP_CONFLICT);
        assertThat(repository.findAllByCognitoUsername(unclaimed)).hasSize(1);
    }

    private User anotherUser() {
        return transaction.execute(status -> {
            User other = User.builder().fullName("Other user").email(UUID.randomUUID() + "@example.test")
                    .role(entityManager.getReference(Role.class, user.getRole().getId())).build();
            entityManager.persist(other);
            return other;
        });
    }

    private List<Object> concurrently(Runnable first, Runnable second) throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Runnable action : List.of(first, second)) {
                futures.add(executor.submit(() -> {
                    if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Start timeout");
                    try {
                        action.run();
                        return "ok";
                    } catch (ApiException exception) {
                        return exception.getErrorCode();
                    }
                }));
            }
            start.countDown();
            return List.of(futures.get(0).get(15, TimeUnit.SECONDS), futures.get(1).get(15, TimeUnit.SECONDS));
        }
    }

    private void assertError(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }

    @Configuration
    @EnableTransactionManagement
    @EnableJpaAuditing
    static class Config {
        @Bean DataSource dataSource() {
            String url = System.getenv("ODMS_IDENTITY_TEST_JDBC_URL");
            if (url == null || !url.matches("jdbc:postgresql://127[.]0[.]0[.]1:(?!5433/)[0-9]+/identity_verification"))
                throw new IllegalStateException("Only isolated identity_verification PostgreSQL is allowed");
            return new DriverManagerDataSource(url, "identity_test", "isolated_test_only");
        }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan("com.ondemandmonitoring.user.domain", "com.ondemandmonitoring.role.domain");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
            return factory;
        }
        @Bean @Primary EntityManager entityManager(EntityManagerFactory factory) {
            return SharedEntityManagerCreator.createSharedEntityManager(factory);
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }
        @Bean TransactionTemplate transaction(PlatformTransactionManager manager) { return new TransactionTemplate(manager); }
        @Bean UserIdentityRepository repository(EntityManager em) {
            return new JpaRepositoryFactory(em).getRepository(UserIdentityRepository.class);
        }
        @Bean IdentityLinkLock lock(EntityManager em) { return new IdentityLinkLock(em); }
        @Bean IUserIdentityService identities(UserIdentityRepository repo, IdentityLinkLock lock) {
            return new UserIdentityServiceImpl(repo, lock);
        }
        @Bean IdentityProviderPort cognito() { return mock(IdentityProviderPort.class); }
        @Bean SocialAuthService social(IUserIdentityService identities, IdentityLinkLock lock, IdentityProviderPort cognito) {
            IUserService users = mock(IUserService.class);
            when(users.findByCognitoSub(anyString())).thenAnswer(call -> identities.findUserByCognitoSub(call.getArgument(0)));
            when(users.hasIdentity(anyString(), any())).thenAnswer(call -> identities.hasIdentity(call.getArgument(0), call.getArgument(1)));
            doAnswer(call -> identities.link(call.getArgument(0), IdentityProvider.LOCAL, call.getArgument(1), call.getArgument(2)))
                    .when(users).linkLocalIdentity(any(), anyString(), anyString());
            return new SocialAuthService(mock(SocialIdentityProviderPort.class), cognito, mock(AuthCompensationPort.class),
                    users, mock(RefreshTokenCookieService.class), mock(AuthenticatedUserMapper.class), lock);
        }
    }
}
