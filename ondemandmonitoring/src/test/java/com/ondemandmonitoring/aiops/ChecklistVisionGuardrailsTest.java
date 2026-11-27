package com.ondemandmonitoring.aiops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ondemandmonitoring.aiops.service.ChecklistVisionAuditFilter;
import com.ondemandmonitoring.aiops.service.ChecklistVisionIncidentService;
import com.ondemandmonitoring.aiops.service.ChecklistVisionRuntime;
import com.ondemandmonitoring.aiops.service.ChecklistVisionRuntime.RequestContext;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import java.sql.Connection;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.invocation.Invocation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChecklistVisionGuardrailsTest {
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final Connection connection = mock(Connection.class);
    private final ChecklistVisionRuntime runtime = new ChecklistVisionRuntime(db, new ObjectMapper());
    private final ChecklistVisionIncidentService incidents = mock(ChecklistVisionIncidentService.class);

    @BeforeEach void transactions() throws Exception {
        var source = mock(DataSource.class);
        when(db.getDataSource()).thenReturn(source);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        @SuppressWarnings("unchecked") var provider = (ObjectProvider<ChecklistVisionIncidentService>) mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(incidents);
        ReflectionTestUtils.setField(runtime, "incidents", provider);
    }

    private List<Invocation> writes(String table) {
        return mockingDetails(db).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("update") && ((String) i.getArgument(0)).contains("INSERT INTO " + table))
                .toList();
    }

    @Test void sanitizesOpaqueIdsAndNeverStoresRawContent() throws Exception {
        var context = new RequestContext("newline\nsecret", "name@example.test", "ADMIN", "a".repeat(129), "execution", "evidence");
        try (var scope = runtime.openRequest(context, false)) {
            scope.prompt("vendor/model", null, "private prompt and image bytes", false);
            scope.succeeded("MISMATCH");
        }
        var args = writes("ai_execution_audits").getFirst().getArguments();
        assertThat(args[2]).as("server correlation").asString().startsWith("AI-");
        assertThat(args[3]).isNull();
        assertThat(args[9]).isNull();
        assertThat(args[10]).isEqualTo("execution");
        assertThat(args[11]).isEqualTo("evidence");
        assertThat(java.util.Arrays.toString(args)).doesNotContain("private prompt", "name@example.test", "newline");
        assertThat(args[16]).isEqualTo("ALLOW");
        assertThat(args[18].toString()).contains("MISMATCH");
        assertThat(writes("ai_security_events")).isEmpty();
        verifyNoInteractions(incidents);
        verify(connection).commit();
    }

    @Test void invalidOutputBlocksAndCorrelatesSecurityAndIncidentAfterCommit() throws Exception {
        String trace;
        try (var scope = runtime.openRequest(RequestContext.anonymous(), false)) {
            trace = scope.traceId();
            scope.inferenceStarted();
            scope.failed(new ApiException(ErrorCode.AI_RESPONSE_INVALID));
        }
        assertThat(writes("ai_execution_audits")).hasSize(1);
        assertThat(writes("ai_security_events")).hasSize(1);
        assertThat(writes("ai_security_events").getFirst().getArgument(2).toString()).isEqualTo(trace);
        assertThat(writes("ai_execution_audits").getFirst().getArgument(16).toString()).isEqualTo("BLOCK");
        var ordered = inOrder(connection, incidents);
        ordered.verify(connection).commit();
        ordered.verify(incidents).recordFailure(trace, ErrorCode.AI_RESPONSE_INVALID.getStatus().value(), "AI_RESPONSE_INVALID");
        assertThat(runtime.currentRequest()).isNull();
    }

    @Test void providerInfrastructureFailureIsNotContentMismatchOrSecurityBlock() {
        try (var scope = runtime.openRequest(RequestContext.anonymous(), false)) {
            scope.inferenceStarted();
            scope.failed(new ApiException(ErrorCode.AI_PROVIDER_UNAVAILABLE));
        }
        assertThat(writes("ai_security_events")).isEmpty();
        assertThat(writes("ai_execution_audits").getFirst().getArgument(18).toString()).contains("ERROR", "AI_PROVIDER_UNAVAILABLE");
        verify(incidents).recordFailure(anyString(), eq(ErrorCode.AI_PROVIDER_UNAVAILABLE.getStatus().value()), eq("AI_PROVIDER_UNAVAILABLE"));
    }

    @Test void pinnedBenchmarkPromptRetainsRegistryProvenanceWithoutPromptText() {
        try (var scope = runtime.openRequest(RequestContext.anonymous(), false)) {
            scope.pinnedPrompt(new com.ondemandmonitoring.prompt.PromptDtos.ResolvedPrompt(
                    "checklist.image-vision.system", "version-id", 7, "registry-hash", "private pinned prompt", java.util.Map.of(), false));
            scope.prompt("vendor/model", null, "private pinned prompt", true);
            scope.inferenceStarted();
            scope.succeeded("MATCH");
        }
        var args = writes("ai_execution_audits").getFirst().getArguments();
        assertThat(args[7]).isEqualTo("v7");
        assertThat(args[8]).isEqualTo("CHECKLIST_VISION");
        assertThat(args[18].toString()).contains("version-id", "registry-hash", "checklist.image-vision.system")
                .doesNotContain("private pinned prompt", "playground");
    }

    @Test void failedSecurityWriteRollsBackBothAndSkipsIncident() throws Exception {
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("private connection details"))
                .when(db).update(contains("INSERT INTO ai_security_events"), any(Object[].class));
        try (var scope = runtime.openRequest(RequestContext.anonymous(), false)) {
            scope.inferenceStarted();
            scope.failed(new ApiException(ErrorCode.AI_RESPONSE_INVALID));
        }
        verify(connection).rollback();
        verify(connection, never()).commit();
        verifyNoInteractions(incidents);
        assertThat(runtime.currentRequest()).isNull();
    }

    @ParameterizedTest @ValueSource(ints = {401, 403})
    void httpBoundaryCatchesRejectionBeforeControllerWithoutResourceDisclosure(int status) throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/missions/private-mission/checklist-executions/private-execution/evidence/private-evidence/content-assessment");
        request.addHeader("X-Correlation-ID", "attacker@example.test");
        var response = new MockHttpServletResponse();
        new ChecklistVisionAuditFilter(runtime).doFilter(request, response, (req, res) -> response.setStatus(status));
        assertThat(response.getHeader("X-Correlation-ID")).startsWith("AI-");
        assertThat(writes("ai_execution_audits")).hasSize(1);
        assertThat(writes("ai_security_events")).hasSize(1);
        assertThat(java.util.Arrays.toString(writes("ai_execution_audits").getFirst().getArguments()))
                .doesNotContain("private-mission", "private-execution", "private-evidence", "attacker@example.test");
        verifyNoInteractions(incidents);
    }

    @Test void successfulHttpScopeAndProviderShareOneAudit() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/admin/ai-ops/checklist-vision/playground");
        var response = new MockHttpServletResponse();
        new ChecklistVisionAuditFilter(runtime).doFilter(request, response, (req, res) -> {
            var scope = runtime.currentRequest();
            scope.authorized("admin-id", null, null, null);
            scope.inferenceStarted();
            scope.succeeded("MISMATCH");
        });
        assertThat(writes("ai_execution_audits")).hasSize(1);
        assertThat(writes("ai_execution_audits").getFirst().getArgument(3).toString()).isEqualTo("admin-id");
        verify(incidents).recovered(response.getHeader("X-Correlation-ID"));
    }

    @Test void unhandledMethodSecurityExceptionStillRecords403Block() {
        var request = new MockHttpServletRequest("POST", "/api/admin/ai-ops/checklist-vision/playground");
        assertThatThrownBy(() -> new ChecklistVisionAuditFilter(runtime).doFilter(request,
                new MockHttpServletResponse(), (req, res) -> { throw new org.springframework.security.access.AccessDeniedException("private detail"); }))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(writes("ai_execution_audits").getFirst().getArgument(13).toString()).isEqualTo("403");
        assertThat(writes("ai_security_events")).hasSize(1);
        assertThat(java.util.Arrays.toString(writes("ai_execution_audits").getFirst().getArguments())).doesNotContain("private detail");
    }

    @Test void boundaryIgnoresUnrelatedEndpoints() throws Exception {
        new ChecklistVisionAuditFilter(runtime).doFilter(new MockHttpServletRequest("GET", "/api/missions/m"),
                new MockHttpServletResponse(), (req, res) -> {});
        assertThat(writes("ai_execution_audits")).isEmpty();
    }
}
