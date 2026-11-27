package com.ondemandmonitoring.aiops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ondemandmonitoring.aiops.controller.AiOperationsController;
import com.ondemandmonitoring.aiops.service.impl.AiOperationsServiceImpl;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AiTraceLookupTest {
    @Test void retrievesLinkedTraceWithoutRelyingOnTheRecentTraceLimit() throws Exception {
        var db = mock(JdbcTemplate.class);
        when(db.queryForList(contains("WHERE trace_id = ?"), eq("AI-older-trace")))
                .thenReturn(List.of(Map.of("trace_id", "AI-older-trace", "resource_id", "mission-id",
                        "user_id", "inspector-id", "model_code", "checklist-image-vision")));
        var service = new AiOperationsServiceImpl(db, new ObjectMapper(), mock(AuthenticatedUserResolver.class));
        var mvc = MockMvcBuilders.standaloneSetup(new AiOperationsController(service)).build();
        mvc.perform(get("/api/admin/ai-ops/traces/AI-older-trace"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.traceId").value("AI-older-trace"))
                .andExpect(jsonPath("$.data.resourceId").value("mission-id"));
        verify(db).queryForList(contains("WHERE trace_id = ?"), eq("AI-older-trace"));
        verifyNoMoreInteractions(db);
    }

    @Test void deniedAnonymousVisionRequestIsNotReportedAsTheSystemActor() {
        var db = mock(JdbcTemplate.class);
        when(db.queryForList(contains("WHERE trace_id = ?"), eq("AI-denied")))
                .thenReturn(List.of(Map.of("trace_id", "AI-denied", "feature", "CHECKLIST_VISION",
                        "security_decision", "BLOCK", "http_status", 401)));
        var service = new AiOperationsServiceImpl(db, new ObjectMapper(), mock(AuthenticatedUserResolver.class));
        var trace = service.getTrace("AI-denied");
        assertEquals("", trace.userId());
        assertEquals("", trace.userRole());
        assertEquals("BLOCK", trace.securityDecision());
    }

    @Test void rejectsInvalidTraceIdentifiersBeforeQuerying() {
        var db = mock(JdbcTemplate.class);
        var service = new AiOperationsServiceImpl(db, new ObjectMapper(), mock(AuthenticatedUserResolver.class));
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> service.getTrace("invalid trace")).getStatusCode().value());
        verifyNoInteractions(db);
    }

    @Test void reportsMissingTrace() {
        var db = mock(JdbcTemplate.class);
        var service = new AiOperationsServiceImpl(db, new ObjectMapper(), mock(AuthenticatedUserResolver.class));
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.getTrace("AI-missing")).getStatusCode().value());
    }
}
