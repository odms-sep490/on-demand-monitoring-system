package com.ondemandmonitoring.devicecheck.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

import com.ondemandmonitoring.audit.service.AdminAuditLogService;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.devicecheck.dto.request.DeviceCheckCriterionReorderRequest;
import com.ondemandmonitoring.devicecheck.dto.request.DeviceCheckCriterionRequest;
import com.ondemandmonitoring.devicecheck.enums.DeviceCheckPhase;
import com.ondemandmonitoring.devicecheck.repository.DeviceCheckCriterionRepository;
import com.ondemandmonitoring.devicecheck.service.impl.DeviceCheckCriterionAdminService;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real-Postgres check of admin device-check criteria. Requires a disposable database via ODMS_DEVICE_CRITERIA_TEST_JDBC_URL
 * (the dev database port 5433 is refused). Skipped when unset.
 */
class DeviceCheckCriteriaPostgresTest {
    static final String ENV = "ODMS_DEVICE_CRITERIA_TEST_JDBC_URL";
    static AnnotationConfigApplicationContext context;
    static EntityManagerFactory emf;
    static JdbcTemplate jdbc;
    static TransactionTemplate tx;

    DeviceCheckCriterionRepository repo;
    DeviceCheckCatalog catalog;
    DeviceCheckCriterionAdminService service;

    @Configuration
    @EnableJpaAuditing
    static class Jpa {
        @Bean
        javax.sql.DataSource dataSource() {
            return new DriverManagerDataSource(System.getenv(ENV),
                    System.getenv().getOrDefault("ODMS_DEVICE_CRITERIA_TEST_DB_USER", "busy_test"),
                    System.getenv().getOrDefault("ODMS_DEVICE_CRITERIA_TEST_DB_PASSWORD", "busy_test_only"));
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(javax.sql.DataSource ds) {
            var f = new LocalContainerEntityManagerFactoryBean();
            f.setDataSource(ds);
            f.setPackagesToScan("com.ondemandmonitoring");
            f.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            f.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop", "hibernate.jdbc.time_zone", "UTC"));
            return f;
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
            return new JpaTransactionManager(emf);
        }
    }

    @BeforeAll
    static void boot() {
        String url = System.getenv(ENV);
        assumeTrue(url != null && !url.isBlank(), ENV + " not set");
        if (url.contains(":5433/")) throw new IllegalStateException("Refusing dev database port 5433");
        context = new AnnotationConfigApplicationContext(Jpa.class);
        emf = context.getBean(EntityManagerFactory.class);
        jdbc = new JdbcTemplate(context.getBean(javax.sql.DataSource.class));
        tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    }

    @AfterAll
    static void shutdown() {
        if (context != null) context.close();
    }

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE device_check_criteria");
        repo = new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(emf))
                .getRepository(DeviceCheckCriterionRepository.class);
        catalog = new DeviceCheckCatalog(repo);
        service = new DeviceCheckCriterionAdminService(repo, catalog, mock(AdminAuditLogService.class));
    }

    <T> T inTx(Supplier<T> work) {
        return tx.execute(s -> work.get());
    }

    long rows(String where, Object... args) {
        return jdbc.queryForObject("select count(*) from device_check_criteria where " + where, Long.class, args);
    }

    @Test
    void seedingStoresBuiltInCatalogueOnceWithAuditColumns() {
        int pre = DeviceCheckCatalog.defaults(DeviceCheckPhase.PRE).size();
        inTx(() -> service.list(DeviceCheckPhase.PRE));
        inTx(() -> service.list(DeviceCheckPhase.PRE));
        inTx(() -> service.list(DeviceCheckPhase.POST));
        assertThat(rows("phase='PRE'")).isEqualTo(pre);
        assertThat(rows("phase='POST'")).isEqualTo(9);
        assertThat(rows("created_at is null")).isZero();
    }

    @Test
    void crudRoundTripAndCatalogueReflectsDatabase() {
        var created = inTx(() -> service.create(new DeviceCheckCriterionRequest(DeviceCheckPhase.POST, null,
                "Kiểm tra đèn định vị", "Đèn LED sáng đủ", "điện và thiết bị", true, true, null)));
        assertThat(rows("code='kiem_tra_den_dinh_vi' and phase='POST' and category='ĐIỆN VÀ THIẾT BỊ'")).isEqualTo(1);

        var updated = inTx(() -> service.update(created.id(), new DeviceCheckCriterionRequest(null, null,
                "Đèn định vị", null, null, false, false, created.version())));
        assertThat(updated.version()).isGreaterThan(created.version());
        assertThat(jdbc.queryForObject("select name from device_check_criteria where id=?", String.class, created.id()))
                .isEqualTo("Đèn định vị");
        assertThat(catalog.activeCriteria(DeviceCheckPhase.POST)).extracting(DeviceCheckCatalog.Criterion::code)
                .doesNotContain("kiem_tra_den_dinh_vi").hasSize(9);

        assertThatThrownBy(() -> inTx(() -> service.update(created.id(), new DeviceCheckCriterionRequest(null, null,
                "stale", null, null, null, null, created.version()))))
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.CONCURRENT_UPDATE));

        inTx(() -> { service.delete(created.id()); return null; });
        assertThat(rows("id=?", created.id())).isZero();
    }

    @Test
    void uniqueCodePerPhaseButSameCodeAllowedAcrossPhases() {
        inTx(() -> service.list(DeviceCheckPhase.PRE));
        assertThatThrownBy(() -> inTx(() -> service.create(new DeviceCheckCriterionRequest(DeviceCheckPhase.PRE,
                "battery_health", "Pin", null, null, true, true, null))))
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.RESOURCE_ALREADY_EXISTS));
        inTx(() -> service.create(new DeviceCheckCriterionRequest(DeviceCheckPhase.POST,
                "battery_health", "Pin sau bay", null, null, true, true, null)));
        assertThat(rows("code='battery_health'")).isEqualTo(2);
    }

    @Test
    void reorderPersistsAndLastActiveGuardHolds() {
        var post = inTx(() -> service.list(DeviceCheckPhase.POST));
        var ids = new ArrayList<>(post.stream().map(r -> r.id()).toList());
        Collections.reverse(ids);
        inTx(() -> service.reorder(new DeviceCheckCriterionReorderRequest(DeviceCheckPhase.POST, ids)));
        assertThat(jdbc.queryForObject("select code from device_check_criteria where phase='POST' order by sort_order limit 1",
                String.class)).isEqualTo("d1");
        assertThat(catalog.activeCriteria(DeviceCheckPhase.POST).get(0).code()).isEqualTo("d1");

        for (int i = 1; i < ids.size(); i++) {
            String id = ids.get(i);
            inTx(() -> { service.delete(id); return null; });
        }
        assertThatThrownBy(() -> inTx(() -> { service.delete(ids.get(0)); return null; }))
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.DEVICE_CHECK_LAST_ACTIVE_CRITERION));
        assertThat(rows("phase='POST'")).isEqualTo(1);
    }
}
