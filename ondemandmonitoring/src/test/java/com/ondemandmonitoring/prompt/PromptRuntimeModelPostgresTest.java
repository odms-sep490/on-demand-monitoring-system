package com.ondemandmonitoring.prompt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ondemandmonitoring.ai.config.ChecklistVisionProperties;
import com.ondemandmonitoring.aiops.service.IAiOperationsService;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.*;

/**
 * Real registry/seed/list HTTP contract in a random PostgreSQL schema; no AI inference or live app.
 */
@EnabledIfEnvironmentVariable(named = "ODMS_PROMPT_RUNTIME_PG", matches = "true")
class PromptRuntimeModelPostgresTest {
    JdbcTemplate admin, db;
    String schema;

    @BeforeEach
    void setup() {
        String url = System.getenv("ODMS_TEST_JDBC_URL"),
                user = System.getenv("ODMS_TEST_DB_USER"),
                password = System.getenv("ODMS_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_prompt_runtime_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        String isolated =
                url.replaceAll("(?i)([?&])currentSchema=[^&]*", "$1")
                        .replace("?&", "?")
                        .replace("&&", "&")
                        .replaceAll("[?&]$", "");
        var source = new DriverManagerDataSource(isolated, user, password);
        var properties = new Properties();
        properties.setProperty("currentSchema", schema);
        source.setConnectionProperties(properties);
        db = new JdbcTemplate(source);
        assertEquals(schema, db.queryForObject("SHOW search_path", String.class));
    }

    @AfterEach
    void close() {
        if (schema != null) {
            assertTrue(schema.matches("qa_prompt_runtime_[a-f0-9]{32}"));
            admin.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    PromptManagementService service(AnnotationConfigApplicationContext context) {
        return new PromptManagementService(
                db,
                new ObjectMapper(),
                new PromptTemplateRenderer(),
                mock(AuthenticatedUserResolver.class),
                mock(IAiOperationsService.class),
                context.getBean(PromptRuntimeModelResolver.class));
    }

    @Test
    void runtimeReconfigurationChangesHttpMetadataWithoutRewritingStoredPromptsOrVersions()
            throws Exception {
        String version;
        try (var context =
                PromptRuntimeModelResolverTest.deployment(
                        ChecklistVisionProperties.Provider.OLLAMA, "vision-current:2b", true)) {
            var prompts = service(context);
            prompts.ensureSchema();
            var summary = prompts.listPrompts("CHECKLIST_VISION", null).getFirst();
            version = summary.activeVersionId();
            assertEquals("openrouter-vision", summary.modelBinding());
            assertEquals("vision-current:2b", summary.runtimeModel().model());
            MockMvcBuilders.standaloneSetup(new PromptManagementController(prompts))
                    .build()
                    .perform(get("/api/admin/ai/prompts").param("feature", "CHECKLIST_VISION"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].runtimeModel.provider").value("Ollama"))
                    .andExpect(jsonPath("$.data[0].runtimeModel.model").value("vision-current:2b"))
                    .andExpect(jsonPath("$.data[0].modelBinding").value("openrouter-vision"))
                    .andExpect(jsonPath("$.data[0].activeVersionNumber").value(1));
        }
        db.update(
                "UPDATE ai_prompts SET model_binding='outdated-admin-metadata' WHERE prompt_key=?",
                ChecklistVisionPlaygroundService.PROMPT_KEY);
        try (var context =
                PromptRuntimeModelResolverTest.deployment(
                        ChecklistVisionProperties.Provider.OPENROUTER,
                        "configured/new-vision:free",
                        true)) {
            var prompts = service(context);
            var summary = prompts.listPrompts("CHECKLIST_VISION", null).getFirst();
            assertEquals("configured/new-vision:free", summary.runtimeModel().model());
            assertEquals("OpenRouter", summary.runtimeModel().provider());
            assertEquals("outdated-admin-metadata", summary.modelBinding());
            assertEquals(version, summary.activeVersionId());
            assertEquals(1, summary.totalVersions());
            assertTrue(
                    prompts.listPrompts("REPORT_STUDIO", null).stream()
                            .allMatch(p -> p.runtimeModel() == null));
            assertEquals(
                    1,
                    db.queryForObject(
                            "SELECT count(*) FROM ai_prompt_registry_versions WHERE prompt_key=?",
                            Integer.class,
                            ChecklistVisionPlaygroundService.PROMPT_KEY));
        }
    }

    @Test
    void missingConfiguredModelIsNotReplacedBySeedBinding() throws Exception {
        try (var context =
                PromptRuntimeModelResolverTest.deployment(
                        ChecklistVisionProperties.Provider.OLLAMA, "", false)) {
            var prompts = service(context);
            MockMvcBuilders.standaloneSetup(new PromptManagementController(prompts))
                    .build()
                    .perform(get("/api/admin/ai/prompts").param("feature", "CHECKLIST_VISION"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].runtimeModel.provider").value("Ollama"))
                    .andExpect(jsonPath("$.data[0].runtimeModel.model").value(""))
                    .andExpect(jsonPath("$.data[0].runtimeModel.configured").value(false));
        }
    }
}
