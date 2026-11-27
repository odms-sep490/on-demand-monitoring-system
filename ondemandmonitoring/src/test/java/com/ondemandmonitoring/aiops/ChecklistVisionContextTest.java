package com.ondemandmonitoring.aiops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ondemandmonitoring.ai.client.ChecklistImageVisionClient;
import com.ondemandmonitoring.ai.config.ChecklistVisionProperties;
import com.ondemandmonitoring.ai.config.OpenRouterProperties;
import com.ondemandmonitoring.aiops.service.ChecklistVisionRuntime;
import com.ondemandmonitoring.aiops.service.impl.AiOperationsServiceImpl;
import com.ondemandmonitoring.prompt.PromptManagementService;
import com.ondemandmonitoring.prompt.PromptTemplateRenderer;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ChecklistVisionContextTest {
    @Test void startsAiOperationsVisionAndPromptRegistryWithCircularReferencesDisabled() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getDefaultListableBeanFactory().setAllowCircularReferences(false);
            context.registerBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class));
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,
                    () -> mock(org.springframework.transaction.PlatformTransactionManager.class));
            context.registerBean(AuthenticatedUserResolver.class, () -> mock(AuthenticatedUserResolver.class));
            context.register(ChecklistVisionProperties.class, OpenRouterProperties.class,
                    PromptTemplateRenderer.class, PromptManagementService.class,
                    ChecklistVisionRuntime.class, ChecklistImageVisionClient.class, AiOperationsServiceImpl.class,
                    com.ondemandmonitoring.aiops.service.ChecklistVisionIncidentService.class,
                    com.ondemandmonitoring.aiops.service.ChecklistVisionAuditFilter.class,
                    com.ondemandmonitoring.prompt.ChecklistVisionPlaygroundService.class,
                    com.ondemandmonitoring.prompt.ChecklistVisionPlaygroundController.class);
            context.refresh();
            assertNotNull(context.getBean(AiOperationsServiceImpl.class));
            assertNotNull(context.getBean(PromptManagementService.class));
            assertNotNull(context.getBean(ChecklistImageVisionClient.class).deployment());
            assertNotNull(context.getBean(com.ondemandmonitoring.prompt.ChecklistVisionPlaygroundController.class));
        }
    }
}
