package com.ondemandmonitoring.ai;

import com.ondemandmonitoring.ai.client.ChecklistImageVisionClient;
import com.ondemandmonitoring.ai.config.ChecklistImageAiSettingsInitializer;
import com.ondemandmonitoring.ai.controller.AdminChecklistImageAiSettingsController;
import com.ondemandmonitoring.ai.repository.ChecklistImageAiSettingsRepository;
import com.ondemandmonitoring.ai.service.ChecklistImageAiSettingsService;
import com.ondemandmonitoring.common.exception.GlobalExceptionHandler;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real PostgreSQL in a disposable schema. HTTP authorization is covered by SettingsHttpTest. */
@EnabledIfEnvironmentVariable(named = "ODMS_TEST_AI_SETTINGS_POSTGRES", matches = "true")
class ChecklistImageAiSettingsPostgresTest {
    private static final String PATH = "/api/admin/config/checklist-image-ai";

    @Test void freshHibernateSchemaSupportsReadUpdateAndRestartWithoutResettingAdminChoice() throws Exception {
        var source = new DriverManagerDataSource(System.getenv("ODMS_TEST_JDBC_URL"),
                System.getenv("ODMS_TEST_DB_USER"), System.getenv("ODMS_TEST_DB_PASSWORD"));
        var jdbc = new JdbcTemplate(source);
        String schema = "qa_ai_settings_" + UUID.randomUUID().toString().replace("-", "");
        var factory = new LocalContainerEntityManagerFactoryBean();
        jdbc.execute("create schema " + schema);
        try {
            factory.setDataSource(source);
            factory.setPackagesToScan("com.ondemandmonitoring.ai.domain");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create",
                    "hibernate.default_schema", schema));
            factory.afterPropertiesSet();
            var emf = factory.getObject();
            assertNotNull(emf);
            var em = SharedEntityManagerCreator.createSharedEntityManager(emf);
            var repository = new JpaRepositoryFactory(em).getRepository(ChecklistImageAiSettingsRepository.class);
            var initializer = new ChecklistImageAiSettingsInitializer(repository, em,
                    new JpaTransactionManager(emf), Clock.systemUTC());
            assertEquals(0, repository.count());
            initializer.run(null);
            initializer.run(null);
            assertEquals(1, repository.count());

            var vision = mock(ChecklistImageVisionClient.class);
            when(vision.available()).thenReturn(true);
            var resolver = mock(AuthenticatedUserResolver.class);
            when(resolver.getCurrentUserId()).thenReturn("qa-admin");
            var service = new ChecklistImageAiSettingsService(repository, vision, Clock.systemUTC());
            // The service needs Spring transaction advice for the pessimistic update query.
            var proxy = new org.springframework.aop.framework.ProxyFactory(service);
            proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(
                    new JpaTransactionManager(emf),
                    new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
            var mvc = MockMvcBuilders.standaloneSetup(new AdminChecklistImageAiSettingsController(
                    (ChecklistImageAiSettingsService) proxy.getProxy(), resolver))
                    .setControllerAdvice(new GlobalExceptionHandler()).build();
            mvc.perform(get(PATH)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.enabled").value(true));
            mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"enabled\":false,\"expectedVersion\":0}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(false))
                    .andExpect(jsonPath("$.data.version").value(1));
            initializer.run(null);
            mvc.perform(get(PATH)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.enabled").value(false))
                    .andExpect(jsonPath("$.data.updatedBy").value("qa-admin"));
        } finally {
            if (factory.getObject() != null) factory.destroy();
            jdbc.execute("drop schema " + schema + " cascade");
        }
    }
}
