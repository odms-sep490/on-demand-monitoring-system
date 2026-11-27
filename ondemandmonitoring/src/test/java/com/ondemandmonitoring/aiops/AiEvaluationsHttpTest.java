package com.ondemandmonitoring.aiops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ondemandmonitoring.aiops.controller.AiOperationsController;
import com.ondemandmonitoring.aiops.service.impl.AiOperationsServiceImpl;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AiEvaluationsHttpTest {
    @TempDir
    Path artifactDirectory;

    @Test
    void returnsAnEmptyListWhenDeepEvalHasNoResults() throws Exception {
        Path summary = Files.writeString(artifactDirectory.resolve("deepeval-summary.json"), "{}");
        JdbcTemplate db = mock(JdbcTemplate.class);
        when(db.queryForList(startsWith("SELECT * FROM ai_evaluation_runs"))).thenReturn(List.of());
        var service = new AiOperationsServiceImpl(db, new ObjectMapper(), mock(AuthenticatedUserResolver.class));
        ReflectionTestUtils.setField(service, "evalSummaryPath", summary.toString());
        var mvc = MockMvcBuilders.standaloneSetup(new AiOperationsController(service)).build();

        mvc.perform(get("/api/admin/ai-ops/evaluations"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"success": true, "data": []}
                        """));

        verify(db, times(2)).queryForList(startsWith("SELECT * FROM ai_evaluation_runs"));
    }
}
