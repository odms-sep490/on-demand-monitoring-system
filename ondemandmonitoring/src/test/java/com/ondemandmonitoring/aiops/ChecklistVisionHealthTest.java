package com.ondemandmonitoring.aiops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ondemandmonitoring.ai.client.ChecklistImageVisionClient;
import com.ondemandmonitoring.aiops.service.impl.AiOperationsServiceImpl;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChecklistVisionHealthTest {
    @Test void checksConfiguredOllamaPortAndModelInsteadOfInferenceUrl() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var payload = new AtomicReference<>("{\"models\":[{\"name\":\"qwen3-vl:2b-instruct\"}]}");
        server.createContext("/api/tags", exchange -> {
            byte[] response = payload.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            var db = mock(JdbcTemplate.class);
            when(db.queryForList(anyString(), eq("checklist-image-vision"))).thenReturn(List.of(
                    Map.of("code", "checklist-image-vision", "name", "Vision", "endpoint", "", "enabled", true)));
            var client = mock(ChecklistImageVisionClient.class);
            when(client.deployment()).thenReturn(new ChecklistImageVisionClient.Deployment(
                    "qwen3-vl:2b-instruct", "Ollama", "http://127.0.0.1:" + server.getAddress().getPort()
                    + "/v1/chat/completions", 90, true));
            @SuppressWarnings("unchecked") var provider = (ObjectProvider<ChecklistImageVisionClient>) mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(client);
            var service = new AiOperationsServiceImpl(db, new ObjectMapper(), mock(AuthenticatedUserResolver.class));
            ReflectionTestUtils.setField(service, "checklistVisionProvider", provider);
            var ready = service.probeModel("checklist-image-vision");
            assertEquals("HEALTHY", ready.status());
            assertTrue(ready.endpoint().endsWith("/api/tags"));
            payload.set("{\"models\":[]}");
            assertEquals("DEGRADED", service.probeModel("checklist-image-vision").status());
        } finally { server.stop(0); }
    }
}
