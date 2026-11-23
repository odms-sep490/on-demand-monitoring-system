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

        assertThat(client.assess(JPEG_PREFIX, "vi", CRITERIA))
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
    @ValueSource(strings = {"openrouter/auto", "vendor/vision:free", "vision", "vendor/vision:latest"})
    void rejectsRoutingAliases(String model) {
        vision.setEnabled(true);
        vision.setModel(model);
        shared.setApiKey("shared-test-key");
        client = new ChecklistImageVisionClient(vision, shared, mapper, http);

        assertThat(client.available()).isFalse();
        verifyNoInteractions(http);
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

    @Test
    void neverReturnsProviderErrorsOrRetries() throws Exception {
        client = enabledClient();
        var response = new BasicClassicHttpResponse(503);
        response.setEntity(new StringEntity("provider-private-content", ContentType.APPLICATION_JSON));
        when(http.executeOpen(isNull(), any(HttpPost.class), isNull())).thenReturn(response);

        assertThatThrownBy(() -> client.assess(JPEG_PREFIX, "vi", CRITERIA))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.AI_PROVIDER_UNAVAILABLE);
                    assertThat(error).hasMessage("Checklist vision provider rejected the request").hasNoCause();
                });
        verify(http).executeOpen(isNull(), any(HttpPost.class), isNull());
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
