package com.ondemandmonitoring.ai.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ondemandmonitoring.ai.client.ChecklistImageVisionClient.CriterionInput;
import com.ondemandmonitoring.ai.client.ChecklistImageVisionClient.Finding;
import com.ondemandmonitoring.ai.client.ChecklistImageVisionClient.Verdict;
import com.ondemandmonitoring.ai.config.ChecklistVisionProperties;
import com.ondemandmonitoring.ai.config.OpenRouterProperties;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.io.entity.InputStreamEntity;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class ChecklistImageVisionClientTest {
    private static final byte[] JPEG_PREFIX = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9};
    private static final List<CriterionInput> CRITERIA = List.of(
            new CriterionInput("landing_area", "Landing area", "Look for a marked landing area."));
    private static final String FINDING = """
            {"findings":{"landing_area":{"verdict":"MATCH","observation":"Có dấu hiệu."}}}
            """;

    private final ObjectMapper mapper = new ObjectMapper();
    private final CloseableHttpClient http = mock(CloseableHttpClient.class);
    private final ChecklistVisionProperties vision = new ChecklistVisionProperties();
    private final OpenRouterProperties shared = new OpenRouterProperties();
    private ChecklistImageVisionClient client;

    @AfterEach
    void closeClient() throws IOException {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void sendsOnlyTheSuppliedBytesWithStrictPrivateRoutingAndUsesThePinnedModel() throws Exception {
        client = enabledClient();
        respond("stop", FINDING);

        var context = new ChecklistImageVisionClient.ChecklistContext(
                "Kiểm tra bãi đáp", "Chụp từ trên xuống", List.of("Một ảnh toàn cảnh"));
        assertThat(client.assess(JPEG_PREFIX, "vi", CRITERIA, context))
                .containsExactlyEntriesOf(Map.of("landing_area", new Finding(Verdict.MATCH, "Có dấu hiệu.")));

        ArgumentCaptor<HttpPost> captured = ArgumentCaptor.forClass(HttpPost.class);
        verify(http).executeOpen(isNull(), captured.capture(), isNull());
        HttpPost request = captured.getValue();
        assertThat(request.getUri().toString()).isEqualTo("https://openrouter.ai/api/v1/chat/completions");
        assertThat(request.getFirstHeader(HttpHeaders.AUTHORIZATION).getValue()).isEqualTo("Bearer shared-test-key");
        assertThat(request.getEntity().getContentType()).startsWith("application/json");
        JsonNode body = requestBody(request);
        assertThat(body.path("model").asText()).isEqualTo("vendor/vision-v1");
        assertThat(body.path("stream").asBoolean()).isFalse();
        assertThat(body.has("models")).isFalse();
        assertThat(body.path("provider")).isEqualTo(mapper.readTree("""
                {"data_collection":"deny","zdr":true,"allow_fallbacks":false,"require_parameters":true}
                """));
        JsonNode format = body.path("response_format");
        assertThat(format.path("type").asText()).isEqualTo("json_schema");
        assertThat(format.path("json_schema").path("strict").asBoolean()).isTrue();
        assertThat(format.at("/json_schema/schema/properties/findings/required/0").asText())
                .isEqualTo("landing_area");
        assertThat(body.at("/messages/1/content/1/image_url/url").asText()).isEqualTo("data:image/jpeg;base64,/9j/2Q==");
        JsonNode prompt = mapper.readTree(body.at("/messages/1/content/0/text").asText());
        assertThat(prompt.path("language").asText()).isEqualTo("vi");
        assertThat(prompt.path("criteria")).isEqualTo(mapper.valueToTree(CRITERIA));
        assertThat(prompt.path("checklistContext")).isEqualTo(mapper.valueToTree(context));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void managedPromptPreservesProviderSpecificRequestOptions(boolean local) throws Exception {
        vision.setEnabled(true);
        vision.setModel("vendor/vision-v1");
        shared.setApiKey("shared-test-key");
        if (local) {
            vision.setProvider(ChecklistVisionProperties.Provider.OLLAMA);
            vision.setModel("qwen3-vl:2b-instruct");
        }
        client = new ChecklistImageVisionClient(vision, shared, mapper, http);
        var prompts = mock(com.ondemandmonitoring.prompt.PromptManagementService.class);
        when(prompts.resolve("checklist.image-vision.system", Map.of()))
                .thenReturn(new com.ondemandmonitoring.prompt.PromptDtos.ResolvedPrompt(
                        "checklist.image-vision.system", "version-1", 1, "hash",
                        "Managed vision instructions", Map.of(), false));
        org.springframework.test.util.ReflectionTestUtils.setField(client, "promptManagement", prompts);
        respond("stop", FINDING);

        client.assess(JPEG_PREFIX, "vi", CRITERIA);

        var captured = ArgumentCaptor.forClass(HttpPost.class);
        verify(http).executeOpen(isNull(), captured.capture(), isNull());
        var body = requestBody(captured.getValue());
        assertThat(body.at("/messages/0/content").asText()).isEqualTo("Managed vision instructions");
        if (local) {
            assertThat(body.path("temperature").asInt(-1)).isZero();
            assertThat(body.has("provider")).isFalse();
        } else {
            assertThat(body.path("provider")).isEqualTo(mapper.readTree("""
                    {"data_collection":"deny","zdr":true,"allow_fallbacks":false,"require_parameters":true}
                    """));
        }
    }

    @Test
    void playgroundUsesExactPromptWithoutResolvingTheActiveVersion() throws Exception {
        client = enabledClient();
        var prompts = mock(com.ondemandmonitoring.prompt.PromptManagementService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(client, "promptManagement", prompts);
        respond("stop", FINDING);
        assertThat(client.assessWithPrompt(JPEG_PREFIX, "vi", CRITERIA, "Draft instructions"))
                .containsKey("landing_area");
        var captured = ArgumentCaptor.forClass(HttpPost.class);
        verify(http).executeOpen(isNull(), captured.capture(), isNull());
        assertThat(requestBody(captured.getValue()).at("/messages/0/content").asText()).isEqualTo("Draft instructions");
        verifyNoInteractions(prompts);
    }

    @Test
    void runtimeConfigAppliesToProviderAndDisabledModelStopsPlayground() throws Exception {
        client = enabledClient();
        var runtime = mock(com.ondemandmonitoring.aiops.service.ChecklistVisionRuntime.class);
        org.springframework.test.util.ReflectionTestUtils.setField(client, "runtime", runtime);
        when(runtime.enabled()).thenReturn(true);
        when(runtime.config()).thenReturn(
                new com.ondemandmonitoring.aiops.service.ChecklistVisionRuntime.Config(false, null, null, null),
                new com.ondemandmonitoring.aiops.service.ChecklistVisionRuntime.Config(true, 0.4, 300, 5));
        assertThatThrownBy(() -> client.assessWithPrompt(JPEG_PREFIX, "vi", CRITERIA, "Draft"))
                .isInstanceOf(ApiException.class);
        verifyNoInteractions(http);
        respond("stop", FINDING);
        client.assessWithPrompt(JPEG_PREFIX, "vi", CRITERIA, "Draft");
        var captured = ArgumentCaptor.forClass(HttpPost.class);
        verify(http).executeOpen(isNull(), captured.capture(), isNull());
        var body = requestBody(captured.getValue());
        assertThat(body.path("temperature").doubleValue()).isEqualTo(0.4);
        assertThat(body.path("max_tokens").intValue()).isEqualTo(300);
    }

    @Test
    void dedicatedEndpointAndKeyTakePrecedence() throws Exception {
        vision.setBaseUrl("https://vision.example.test/api/v1/");
        vision.setApiKey("vision-test-key");
        client = enabledClient();
        respond("stop", FINDING);

        client.assess(JPEG_PREFIX, "vi", CRITERIA);

        ArgumentCaptor<HttpPost> captured = ArgumentCaptor.forClass(HttpPost.class);
        verify(http).executeOpen(isNull(), captured.capture(), isNull());
        assertThat(captured.getValue().getUri().toString()).isEqualTo("https://vision.example.test/api/v1/chat/completions");
        assertThat(captured.getValue().getFirstHeader(HttpHeaders.AUTHORIZATION).getValue())
                .isEqualTo("Bearer vision-test-key");
    }

    @Test
    void localOllamaUsesLoopbackAndDoesNotSendTheOpenRouterKey() throws Exception {
        vision.setEnabled(true);
        vision.setProvider(ChecklistVisionProperties.Provider.OLLAMA);
        vision.setModel("qwen3-vl:2b-instruct");
        shared.setApiKey("shared-test-key");
        client = new ChecklistImageVisionClient(vision, shared, mapper, http);
        respond("stop", FINDING);

        client.assess(JPEG_PREFIX, "vi", CRITERIA);

        var captured = ArgumentCaptor.forClass(HttpPost.class);
        verify(http).executeOpen(isNull(), captured.capture(), isNull());
        var request = captured.getValue();
        assertThat(request.getUri().toString()).isEqualTo("http://127.0.0.1:11434/v1/chat/completions");
        assertThat(request.getFirstHeader(HttpHeaders.AUTHORIZATION)).isNull();
        var body = requestBody(request);
        assertThat(body.path("model").asText()).isEqualTo("qwen3-vl:2b-instruct");
        assertThat(body.has("provider")).isFalse();
        assertThat(body.at("/response_format/type").asText()).isEqualTo("json_schema");
        assertThat(body.path("max_tokens").asInt()).isEqualTo(248);
        assertThat(body.at("/response_format/json_schema/schema/properties/findings/properties/landing_area/properties/observation/maxLength")
                .asInt()).isEqualTo(160);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://192.0.2.1:11434/v1", "https://example.test/v1", "http://user@127.0.0.1:11434/v1"})
    void localOllamaRejectsNonLocalOrCredentialedEndpoints(String url) {
        vision.setEnabled(true);
        vision.setProvider(ChecklistVisionProperties.Provider.OLLAMA);
        vision.setModel("qwen3-vl:2b");
        vision.setBaseUrl(url);
        client = new ChecklistImageVisionClient(vision, shared, mapper, http);
        assertThat(client.available()).isFalse();
    }

    @Test
    void disabledByDefaultAndDoesNotInheritTheEstimateModel() {
        shared.setApiKey("shared-test-key");
        shared.setModel("vendor/estimate-model");
        client = new ChecklistImageVisionClient(vision, shared, mapper, http);
        assertThat(client.available()).isFalse();

        vision.setEnabled(true);
        client = new ChecklistImageVisionClient(vision, shared, mapper, http);
        assertThat(client.available()).isFalse();
        assertThatThrownBy(() -> client.assess(JPEG_PREFIX, "vi", CRITERIA))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.AI_PROVIDER_UNAVAILABLE));
        verifyNoInteractions(http);
    }

    @ParameterizedTest
    @ValueSource(strings = {"openrouter/auto", "openrouter/free", "vision", "vendor/vision:latest"})
    void rejectsRoutingAliases(String model) {
        vision.setEnabled(true);
        vision.setModel(model);
        shared.setApiKey("shared-test-key");
        client = new ChecklistImageVisionClient(vision, shared, mapper, http);

        assertThat(client.available()).isFalse();
        verifyNoInteractions(http);
    }

    @Test
    void acceptsAnExplicitFreeModelWithoutEnablingAliases() {
        vision.setEnabled(true);
        vision.setModel("vendor/vision:free");
        shared.setApiKey("shared-test-key");
        client = new ChecklistImageVisionClient(vision, shared, mapper, http);
        assertThat(client.available()).isTrue();
    }

    @Test
    void rejectsOversizedPreviewBeforeContactingProvider() {
        client = enabledClient();
        byte[] oversized = new byte[ChecklistImageVisionClient.MAX_JPEG_BYTES + 1];
        System.arraycopy(JPEG_PREFIX, 0, oversized, 0, JPEG_PREFIX.length);

        assertThatThrownBy(() -> client.assess(oversized, "vi", CRITERIA))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
        verifyNoInteractions(http);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "provider-private-content",
            "{\"findings\":{}}",
            "{\"findings\":{\"unexpected\":{\"verdict\":\"MATCH\",\"observation\":\"visible\"}}}",
            "{\"findings\":{\"landing_area\":{\"verdict\":\"YES\",\"observation\":\"visible\"}}}",
            "{\"findings\":{\"landing_area\":{\"verdict\":\"MATCH\",\"observation\":5}}}",
            "{\"findings\":{\"landing_area\":{\"verdict\":\"MATCH\",\"verdict\":\"MATCH\",\"observation\":\"visible\"}}}",
            "{\"findings\":{\"landing_area\":{\"verdict\":\"MATCH\",\"observation\":\"visible\"}}} {}"
    })
    void rejectsInvalidProviderResponsesWithASanitizedError(String content) throws Exception {
        client = enabledClient();
        respond("stop", content);

        assertInvalidResponse();
    }

    @Test
    void rejectsTruncatedCompletionEvenWhenItsContentIsValid() throws Exception {
        client = enabledClient();
        respond("length", FINDING);

        assertInvalidResponse();
    }

    @Test
    void limitsResponseBodyWithUnknownContentLength() throws Exception {
        client = enabledClient();
        var response = new BasicClassicHttpResponse(200);
        response.setEntity(new InputStreamEntity(new ByteArrayInputStream(new byte[64 * 1024 + 1]),
                -1, ContentType.APPLICATION_JSON));
        when(http.executeOpen(isNull(), any(HttpPost.class), isNull())).thenReturn(response);

        assertInvalidResponse();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 503})
    void neverReturnsProviderErrorsOrRetries(int status) throws Exception {
        client = enabledClient();
        var response = new BasicClassicHttpResponse(status);
        response.setEntity(new StringEntity("provider-private-content", ContentType.APPLICATION_JSON));
        when(http.executeOpen(isNull(), any(HttpPost.class), isNull())).thenReturn(response);

        assertThatThrownBy(() -> client.assess(JPEG_PREFIX, "vi", CRITERIA))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.AI_PROVIDER_UNAVAILABLE);
                    assertThat(error).hasMessage("Checklist vision provider rejected the request").hasNoCause();
                });
        verify(http).executeOpen(isNull(), any(HttpPost.class), isNull());
    }

    @Test
    void initializesRegistryBeforeCheckingLiveSwitchAndBlocksCachedDisabledPrompt() {
        client = enabledClient();
        var prompts = mock(com.ondemandmonitoring.prompt.PromptManagementService.class);
        var runtime = mock(com.ondemandmonitoring.aiops.service.ChecklistVisionRuntime.class);
        org.springframework.test.util.ReflectionTestUtils.setField(client, "promptManagement", prompts);
        org.springframework.test.util.ReflectionTestUtils.setField(client, "runtime", runtime);
        when(runtime.config()).thenReturn(com.ondemandmonitoring.aiops.service.ChecklistVisionRuntime.Config.DEFAULT);
        org.mockito.Mockito.doThrow(new ApiException(ErrorCode.CHECKLIST_IMAGE_ASSESSMENT_DISABLED))
                .when(runtime).requirePromptEnabled();
        assertThatThrownBy(() -> client.assess(JPEG_PREFIX, "vi", CRITERIA))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(ErrorCode.CHECKLIST_IMAGE_ASSESSMENT_DISABLED));
        var ordered = org.mockito.Mockito.inOrder(prompts, runtime);
        ordered.verify(prompts).ensureSchema();
        ordered.verify(runtime).requirePromptEnabled();
        org.mockito.Mockito.verify(prompts, org.mockito.Mockito.never()).resolve(any(), any());
        verifyNoInteractions(http);
    }

    @Test
    void registryOutageFailsClosedWithoutBundledFallbackOrPrivateException() {
        client = enabledClient();
        var prompts = mock(com.ondemandmonitoring.prompt.PromptManagementService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(client, "promptManagement", prompts);
        when(prompts.resolve(any(), any())).thenThrow(new IllegalStateException("private registry address or prompt"));
        assertThatThrownBy(() -> client.assess(JPEG_PREFIX, "vi", CRITERIA))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_PROVIDER_UNAVAILABLE);
                    assertThat(e).hasMessage("Checklist vision prompt is unavailable").hasNoCause();
                });
        verifyNoInteractions(http);
    }

    @Test
    void rejectsUnboundedChecklistContextBeforeInference() {
        client = enabledClient();
        assertThatThrownBy(() -> client.assess(JPEG_PREFIX, "vi", CRITERIA,
                new ChecklistImageVisionClient.ChecklistContext("x".repeat(4001), null, List.of())))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
        verifyNoInteractions(http);
    }

    @Test
    void cpuModeUsesNativeOllamaOnTheSameServerWithStrictSchemaAndRuntimeOptions() throws Exception {
        vision.setEnabled(true);
        vision.setProvider(ChecklistVisionProperties.Provider.OLLAMA);
        vision.setModel("qwen3-vl:2b-instruct");
        vision.setOllamaCpuOnly(true);
        client = new ChecklistImageVisionClient(vision, shared, mapper, http);
        var runtime = mock(com.ondemandmonitoring.aiops.service.ChecklistVisionRuntime.class);
        when(runtime.config()).thenReturn(new com.ondemandmonitoring.aiops.service.ChecklistVisionRuntime.Config(
                true, 0.2, 600, 60));
        org.springframework.test.util.ReflectionTestUtils.setField(client, "runtime", runtime);
        when(runtime.enabled()).thenReturn(true);
        var response = new BasicClassicHttpResponse(200);
        response.setEntity(new StringEntity(mapper.writeValueAsString(Map.of(
                "done", true, "done_reason", "stop", "message", Map.of("content", FINDING))), ContentType.APPLICATION_JSON));
        when(http.executeOpen(isNull(), any(HttpPost.class), isNull())).thenReturn(response);

        assertThat(client.assessWithPrompt(JPEG_PREFIX, "vi", CRITERIA, "Draft instructions"))
                .containsEntry("landing_area", new Finding(Verdict.MATCH, "Có dấu hiệu."));
        var captured = ArgumentCaptor.forClass(HttpPost.class);
        verify(http).executeOpen(isNull(), captured.capture(), isNull());
        var request = captured.getValue();
        assertThat(request.getUri().toString()).isEqualTo("http://127.0.0.1:11434/api/chat");
        assertThat(request.getFirstHeader(HttpHeaders.AUTHORIZATION)).isNull();
        var body = requestBody(request);
        assertThat(body.at("/options/num_gpu").asInt(-1)).isZero();
        assertThat(body.at("/options/num_predict").asInt()).isEqualTo(600);
        assertThat(body.at("/options/temperature").asDouble()).isEqualTo(0.2);
        assertThat(body.at("/messages/0/content").asText()).isEqualTo("Draft instructions");
        assertThat(body.at("/messages/1/images/0").asText()).isEqualTo("/9j/2Q==");
        assertThat(mapper.readTree(body.at("/messages/1/content").asText()).path("criteria"))
                .isEqualTo(mapper.valueToTree(CRITERIA));
        assertThat(body.at("/format/properties/findings/required/0").asText()).isEqualTo("landing_area");
        assertThat(body.at("/format/additionalProperties").asBoolean(true)).isFalse();
        assertThat(body.has("response_format")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"length", "", "load"})
    void cpuModeRejectsIncompleteNativeResponses(String reason) throws Exception {
        vision.setEnabled(true);
        vision.setProvider(ChecklistVisionProperties.Provider.OLLAMA);
        vision.setModel("qwen3-vl:2b-instruct");
        vision.setOllamaCpuOnly(true);
        client = new ChecklistImageVisionClient(vision, shared, mapper, http);
        var response = new BasicClassicHttpResponse(200);
        response.setEntity(new StringEntity(mapper.writeValueAsString(Map.of(
                "done", true, "done_reason", reason, "message", Map.of("content", FINDING))), ContentType.APPLICATION_JSON));
        when(http.executeOpen(isNull(), any(HttpPost.class), isNull())).thenReturn(response);
        assertInvalidResponse();
    }

    @Test
    void cpuOptionDoesNotChangeOpenRouterRequests() throws Exception {
        vision.setOllamaCpuOnly(true);
        client = enabledClient();
        respond("stop", FINDING);
        client.assess(JPEG_PREFIX, "vi", CRITERIA);
        var captured = ArgumentCaptor.forClass(HttpPost.class);
        verify(http).executeOpen(isNull(), captured.capture(), isNull());
        assertThat(captured.getValue().getUri().toString()).isEqualTo("https://openrouter.ai/api/v1/chat/completions");
        assertThat(requestBody(captured.getValue()).has("options")).isFalse();
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "ODMS_TEST_OLLAMA", matches = "true")
    void realOllamaCpuReturnsFindingsForEveryCriterion() throws Exception {
        vision.setEnabled(true);
        vision.setProvider(ChecklistVisionProperties.Provider.OLLAMA);
        vision.setModel("qwen3-vl:2b-instruct");
        vision.setOllamaCpuOnly(true);
        client = new ChecklistImageVisionClient(vision, shared, mapper);
        var image = new java.awt.image.BufferedImage(224, 224, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(java.awt.Color.RED);
        graphics.fillRect(0, 0, 224, 224);
        graphics.dispose();
        var bytes = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "jpeg", bytes);
        var criteria = List.of(new CriterionInput("RED", "Red", "The image is predominantly red."),
                new CriterionInput("BUILDING", "Building", "A building is clearly visible."));
        var findings = client.assess(bytes.toByteArray(), "vi", criteria);
        assertThat(findings).containsOnlyKeys("RED", "BUILDING");
        assertThat(findings.get("RED").verdict()).isEqualTo(Verdict.MATCH);
        assertThat(findings.get("BUILDING").verdict()).isNotEqualTo(Verdict.MATCH);
        assertThat(findings.values()).allSatisfy(finding -> assertThat(finding.observation()).isNotBlank());
    }

    private ChecklistImageVisionClient enabledClient() {
        vision.setEnabled(true);
        vision.setModel("vendor/vision-v1");
        shared.setApiKey("shared-test-key");
        return new ChecklistImageVisionClient(vision, shared, mapper, http);
    }

    private void respond(String finishReason, String content) throws IOException {
        String body = mapper.writeValueAsString(Map.of("choices", List.of(
                Map.of("finish_reason", finishReason, "message", Map.of("content", content)))));
        var response = new BasicClassicHttpResponse(200);
        response.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
        when(http.executeOpen(isNull(), any(HttpPost.class), isNull())).thenReturn(response);
    }

    private JsonNode requestBody(HttpPost request) throws IOException {
        var bytes = new ByteArrayOutputStream();
        request.getEntity().writeTo(bytes);
        return mapper.readTree(bytes.toByteArray());
    }

    private void assertInvalidResponse() {
        assertThatThrownBy(() -> client.assess(JPEG_PREFIX, "vi", CRITERIA))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.AI_RESPONSE_INVALID);
                    assertThat(error).hasMessage("Invalid checklist vision response").hasNoCause();
                });
    }
}
