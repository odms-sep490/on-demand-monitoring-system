package com.ondemandmonitoring.prompt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ondemandmonitoring.ai.client.ChecklistImageVisionClient;
import com.ondemandmonitoring.ai.config.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.stream.Stream;

class PromptRuntimeModelResolverTest {
    static Stream<ChecklistVisionProperties.Provider> providers() {
        return Stream.of(ChecklistVisionProperties.Provider.values());
    }

    @ParameterizedTest
    @MethodSource("providers")
    void readsActualConfiguredClientWithoutExposingCredentialsOrEndpoint(
            ChecklistVisionProperties.Provider provider) throws Exception {
        String model =
                provider == ChecklistVisionProperties.Provider.OLLAMA
                        ? "configured-vision:7b"
                        : "configured/vision:free";
        try (var context = deployment(provider, model, true)) {
            var result =
                    context.getBean(PromptRuntimeModelResolver.class)
                            .resolve(ChecklistVisionPlaygroundService.PROMPT_KEY);
            assertEquals(model, result.model());
            assertEquals(
                    provider == ChecklistVisionProperties.Provider.OLLAMA ? "Ollama" : "OpenRouter",
                    result.provider());
            assertTrue(result.configured());
            var serialized = new ObjectMapper().valueToTree(result);
            assertEquals(3, serialized.size());
            assertFalse(serialized.toString().contains("test-secret"));
            assertFalse(serialized.has("endpoint"));
        }
    }

    @Test
    void doesNotInventRuntimeForOtherPromptsOrInstantiateTheVisionClient() {
        @SuppressWarnings("unchecked")
        var provider = (ObjectProvider<ChecklistImageVisionClient>) mock(ObjectProvider.class);
        assertNull(new PromptRuntimeModelResolver(provider).resolve("report.inspection.system"));
        verifyNoInteractions(provider);
    }

    @Test
    void missingClientIsExplicitlyUnconfigured() {
        @SuppressWarnings("unchecked")
        var provider = (ObjectProvider<ChecklistImageVisionClient>) mock(ObjectProvider.class);
        var result =
                new PromptRuntimeModelResolver(provider)
                        .resolve(ChecklistVisionPlaygroundService.PROMPT_KEY);
        assertNull(result.model());
        assertNull(result.provider());
        assertFalse(result.configured());
    }

    @Test
    void unconfiguredClientDoesNotReportTheLegacyModelAlias() {
        try (var context = deployment(ChecklistVisionProperties.Provider.OLLAMA, "", false)) {
            var result =
                    context.getBean(PromptRuntimeModelResolver.class)
                            .resolve(ChecklistVisionPlaygroundService.PROMPT_KEY);
            assertEquals("", result.model());
            assertFalse(result.configured());
        }
    }

    static AnnotationConfigApplicationContext deployment(
            ChecklistVisionProperties.Provider provider, String model, boolean enabled) {
        var properties = new ChecklistVisionProperties();
        properties.setProvider(provider);
        properties.setModel(model);
        properties.setEnabled(enabled);
        var shared = new OpenRouterProperties();
        shared.setApiKey("test-secret");
        var context = new AnnotationConfigApplicationContext();
        context.getDefaultListableBeanFactory().setAllowCircularReferences(false);
        context.registerBean(ChecklistVisionProperties.class, () -> properties);
        context.registerBean(OpenRouterProperties.class, () -> shared);
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
        context.registerBean(com.ondemandmonitoring.aiops.service.ChecklistVisionRuntime.class,
                () -> mock(com.ondemandmonitoring.aiops.service.ChecklistVisionRuntime.class));
        context.register(ChecklistImageVisionClient.class, PromptRuntimeModelResolver.class);
        context.refresh();
        return context;
    }
}
