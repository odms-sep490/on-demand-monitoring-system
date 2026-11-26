package com.ondemandmonitoring.conversation;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Proxies landing-page questions to the AI worker's retrieval-augmented LLM endpoint.
 * The endpoint is public, so each client IP is limited to a small number of questions per minute.
 */
@Service
public class PublicLandingChatService {

    static final int MAX_REQUESTS_PER_MINUTE = 12;

    public record ChatTurn(String role, String content) {}

    public record ChatReply(String reply, List<String> sources) {}

    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build();
    private final Map<String, Deque<Long>> requestsByClient = new ConcurrentHashMap<>();

    @Value("${DESCRIPTION_AI_URL:http://localhost:8002}") private String workerUrl;
    @Value("${DESCRIPTION_AI_TOKEN:}") private String workerToken;

    public PublicLandingChatService(ObjectMapper json) {
        this.json = json;
    }

    public ChatReply chat(String clientKey, String message, String lang, List<ChatTurn> history) {
        enforceRateLimit(clientKey);
        if (workerToken == null || workerToken.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Trợ lý AI chưa được cấu hình.");
        }
        try {
            var body = Map.of(
                    "message", message,
                    "lang", "en".equals(lang) ? "en" : "vi",
                    "history", history == null ? List.of() : history.stream().limit(8).toList());
            var request = HttpRequest.newBuilder(URI.create(workerUrl + "/landing/chat"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Token", workerToken)
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "Trợ lý AI đang bận, bạn thử lại sau nhé.");
            }
            return json.readValue(response.body(), ChatReply.class);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Đã dừng trợ lý AI.");
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Chưa kết nối được trợ lý AI.");
        }
    }

    /** Sliding one-minute window per client. */
    void enforceRateLimit(String clientKey) {
        long now = System.currentTimeMillis();
        Deque<Long> window = requestsByClient.computeIfAbsent(clientKey == null ? "unknown" : clientKey, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() > 60_000) window.pollFirst();
            if (window.size() >= MAX_REQUESTS_PER_MINUTE) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "Bạn hỏi hơi nhanh, đợi một chút rồi hỏi tiếp nhé.");
            }
            window.addLast(now);
        }
        if (requestsByClient.size() > 10_000) requestsByClient.clear();
    }
}
