package com.ondemandmonitoring.ai.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ondemandmonitoring.ai.config.OpenRouterProperties;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/** Thin OpenAI-compatible chat-completions client for OpenRouter. */
@Component
@Slf4j
public class OpenRouterChatClient {
    private final RestTemplate restTemplate;
    private final OpenRouterProperties properties;
    private final ObjectMapper objectMapper;

    @Autowired
    public OpenRouterChatClient(OpenRouterProperties properties, ObjectMapper objectMapper) {
        this(buildRestTemplate(properties), properties, objectMapper);
    }

    OpenRouterChatClient(RestTemplate restTemplate, OpenRouterProperties properties, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public Completion complete(String systemPrompt, String userPrompt) {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new ApiException(ErrorCode.AI_PROVIDER_UNAVAILABLE,
                    "OpenRouter API key is not configured");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(properties.getApiKey());
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        Map<String, Object> body = Map.of(
                "model", properties.getModel(),
                "max_completion_tokens", properties.getMaxTokens(),
                "reasoning", Map.of("effort", "low"),
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)));

        long startedAt = System.nanoTime();
        try {
            ResponseEntity<String> httpResponse = restTemplate.postForEntity(
                    chatCompletionsUri(), new HttpEntity<>(body, headers), String.class);
            String responseBody = httpResponse.getBody();
            JsonNode response;
            try {
                response = responseBody == null ? null : objectMapper.readTree(responseBody);
            } catch (JsonProcessingException exception) {
                log.warn("OpenRouter returned a non-JSON chat completion response; model={}, elapsedMs={}",
                        properties.getModel(), elapsedMs(startedAt));
                throw new ApiException(ErrorCode.AI_PROVIDER_UNAVAILABLE,
                        "OpenRouter returned an invalid response");
            }

            JsonNode providerError = response == null ? null : response.get("error");
            if (providerError != null && !providerError.isNull() && !providerError.isMissingNode()) {
                String errorCode = providerError.path("code").asText("unknown");
                String errorMessage = providerError.path("message").asText("unspecified provider error");
                log.warn("OpenRouter returned an error object; model={}, code={}, message={}, elapsedMs={}",
                        properties.getModel(), safeLogValue(errorCode), safeLogValue(errorMessage), elapsedMs(startedAt));
                throw new ApiException(ErrorCode.AI_PROVIDER_UNAVAILABLE,
                        "OpenRouter could not generate a resource estimate (provider error "
                                + safeClientCode(errorCode) + ")");
            }

            JsonNode choices = response == null ? null : response.path("choices");
            JsonNode choice = choices != null && choices.isArray() && !choices.isEmpty()
                    ? choices.get(0) : null;
            String finishReason = choice == null ? "missing_choice" : choice.path("finish_reason").asText("unknown");
            JsonNode message = choice == null ? null : choice.path("message");
            JsonNode content = message == null ? null : message.path("content");
            JsonNode usage = response == null ? null : response.path("usage");
            int completionTokens = usage == null ? -1 : usage.path("completion_tokens").asInt(-1);
            int reasoningTokens = usage == null ? -1
                    : usage.path("completion_tokens_details").path("reasoning_tokens").asInt(-1);
            String text = responseText(content);
            if (text == null || text.isBlank()) {
                boolean refusal = message != null && !message.path("refusal").isMissingNode()
                        && !message.path("refusal").isNull();
                log.warn("OpenRouter returned no assistant content; model={}, finishReason={}, refusal={}, "
                                + "completionTokens={}, reasoningTokens={}, elapsedMs={}",
                        response == null ? properties.getModel() : response.path("model").asText(properties.getModel()),
                        finishReason, refusal, completionTokens, reasoningTokens, elapsedMs(startedAt));
                throw new ApiException(ErrorCode.AI_PROVIDER_UNAVAILABLE,
                        "OpenRouter returned no assistant response (finish reason: "
                                + safeClientCode(finishReason) + ")");
            }
            String actualModel = response.path("model").asText(properties.getModel());
            log.info("OpenRouter chat completion finished; model={}, finishReason={}, outputChars={}, "
                            + "completionTokens={}, reasoningTokens={}, elapsedMs={}",
                    actualModel, finishReason, text.length(), completionTokens, reasoningTokens, elapsedMs(startedAt));
            return new Completion(actualModel, text, finishReason, completionTokens, reasoningTokens);
        } catch (HttpStatusCodeException exception) {
            String responseBody = exception.getResponseBodyAsString();
            String providerCode = providerErrorCode(responseBody);
            log.warn("OpenRouter chat completion failed; status={}, code={}, requestId={}, elapsedMs={}",
                    exception.getStatusCode(), providerCode,
                    exception.getResponseHeaders() == null ? null
                            : exception.getResponseHeaders().getFirst("x-request-id"),
                    elapsedMs(startedAt));
            throw new ApiException(ErrorCode.AI_PROVIDER_UNAVAILABLE,
                    "OpenRouter could not generate a resource estimate"
                            + ("unknown".equals(providerCode) ? "" : " (provider error " + providerCode + ")"));
        } catch (RestClientException exception) {
            log.warn("OpenRouter chat completion request failed; exceptionType={}, elapsedMs={}",
                    exception.getClass().getSimpleName(), elapsedMs(startedAt));
            throw new ApiException(ErrorCode.AI_PROVIDER_UNAVAILABLE,
                    "OpenRouter could not be reached");
        }
    }

    private URI chatCompletionsUri() {
        String baseUrl = properties.getBaseUrl().replaceAll("/+$", "");
        return URI.create(baseUrl + "/chat/completions");
    }

    private String responseText(JsonNode content) {
        if (content == null || content.isMissingNode() || content.isNull()) return null;
        if (content.isTextual()) return content.asText();
        if (content.isArray()) {
            return java.util.stream.StreamSupport.stream(content.spliterator(), false)
                    .map(part -> part.isTextual() ? part.asText() : part.path("text").asText(""))
                    .filter(part -> !part.isBlank())
                    .collect(java.util.stream.Collectors.joining("\n"));
        }
        return objectMapper.valueToTree(content).toString();
    }

    private String providerErrorCode(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) return "unknown";
        try {
            String code = objectMapper.readTree(responseBody).path("error").path("code").asText("unknown");
            return safeClientCode(code);
        } catch (JsonProcessingException ignored) {
            return "unknown";
        }
    }

    private String safeLogValue(String value) {
        String sanitized = Objects.toString(value, "").replaceAll("[\\r\\n\\t]", " ").strip();
        return sanitized.length() <= 240 ? sanitized : sanitized.substring(0, 240);
    }

    private String safeClientCode(String value) {
        String sanitized = Objects.toString(value, "unknown").replaceAll("[^A-Za-z0-9_.-]", "");
        return sanitized.isBlank() ? "unknown" : sanitized.substring(0, Math.min(80, sanitized.length()));
    }

    private long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private static RestTemplate buildRestTemplate(OpenRouterProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeoutMs());
        factory.setReadTimeout(properties.getReadTimeoutMs());
        return new RestTemplate(factory);
    }

    public record Completion(
            String model, String content, String finishReason, int completionTokens, int reasoningTokens) {
    }
}
