package com.ondemandmonitoring.finance.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ondemandmonitoring.common.exception.*;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class QuoteAgentClient {
    private final SmartQuoteService service;
    private final ObjectMapper json;
    private final String url;
    private final String token;
    private final java.util.concurrent.ConcurrentHashMap<String, Long> cooldowns = new java.util.concurrent.ConcurrentHashMap<>();
    private final Semaphore slots = new Semaphore(2);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    public QuoteAgentClient(SmartQuoteService service, ObjectMapper json,
            @Value("${QUOTE_ASSISTANT_URL:http://127.0.0.1:8002}") String url,
            @Value("${QUOTE_ASSISTANT_TOKEN:}") String token) {
        this.service=service; this.json=json; this.url=url; this.token=localToken(token);
    }
        private static String localToken(String configured) {
        if (!configured.isBlank()) return configured;
        for (String name : new String[]{".env", "../.env"}) {
            var path=java.nio.file.Path.of(name);
            if (!java.nio.file.Files.isRegularFile(path)) continue;
            try(var reader=java.nio.file.Files.newBufferedReader(path)) {
                var properties=new java.util.Properties(); properties.load(reader);
                String value=properties.getProperty("QUOTE_ASSISTANT_TOKEN", "");
                if(!value.isBlank()) return value;
            } catch(java.io.IOException ignored) { }
        }
        return "";
    }
    public record Message(@NotBlank @Size(max=1500) String question, @NotBlank @Size(max=300) String revision) {}
    public record Answer(String status, String answer, String revision) {}
    private boolean onCooldown(String key) {
        long now = System.currentTimeMillis();
        var previous = cooldowns.putIfAbsent(key, now);
        if (previous != null && now - previous < 3000) return true;
        cooldowns.put(key, now);
        if (cooldowns.size() > 1000) cooldowns.entrySet().removeIf(e -> now - e.getValue() > 60000);
        return false;
    }
    private Answer post(String endpoint, Map<String, Object> payload, String revision) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url + endpoint))
                .timeout(Duration.ofSeconds(90)).header("Content-Type", "application/json")
                .header("X-Internal-Token", token)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 409) throw new ApiException(ErrorCode.CONCURRENT_UPDATE);
        if (response.statusCode() != 200 || response.body().length() > 16000)
            return new Answer("UNAVAILABLE", null, revision);
        String answer = json.readTree(response.body()).path("answer").asText();
        return answer.isBlank() ? new Answer("UNAVAILABLE", null, revision) : new Answer("READY", answer, revision);
    }
    /** Context is assembled by the server from the customer-safe pricing preview. */
    public Answer explainRequest(String question, Object context, String customerId) {
        if (onCooldown("request:" + customerId)) return new Answer("BUSY", null, "draft");
        if (token.isBlank() || !slots.tryAcquire()) return new Answer("UNAVAILABLE", null, "draft");
        try {
            return post("/request-assistant/messages", Map.of("question", question, "context", context), "draft");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Answer("UNAVAILABLE", null, "draft");
        } catch (Exception e) {
            return new Answer("UNAVAILABLE", null, "draft");
        } finally { slots.release(); }
    }
    public Answer ask(String orderId, String quoteId, boolean customer, Message message, String authorization) {
        var current=service.read(orderId, quoteId, customer);
        if (!current.revision().equals(message.revision())) throw new ApiException(ErrorCode.CONCURRENT_UPDATE);
        String key=orderId+":"+Integer.toHexString(java.util.Objects.hashCode(authorization));
        if(onCooldown(key)) return new Answer("BUSY",null,current.revision());
        if (token.isBlank() || authorization == null || !slots.tryAcquire()) return new Answer("UNAVAILABLE", null, current.revision());
        try {
            var path="/api/"+(customer?"customer":"manager")+"/orders/"+orderId+"/quotes/"+quoteId;
            var answer = post("/quote-assistant/messages", Map.of("question",message.question(),
                    "revision",message.revision(),"path",path,"authorization",authorization), current.revision());
            if (!"READY".equals(answer.status())) return answer;
            var latest=service.read(orderId,quoteId,customer);
            if(!latest.revision().equals(current.revision())) throw new ApiException(ErrorCode.CONCURRENT_UPDATE);
            return answer;
        } catch (ApiException e) { throw e; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return new Answer("UNAVAILABLE",null,current.revision()); }
        catch (Exception e) { return new Answer("UNAVAILABLE",null,current.revision()); }
        finally { slots.release(); }
    }
}


