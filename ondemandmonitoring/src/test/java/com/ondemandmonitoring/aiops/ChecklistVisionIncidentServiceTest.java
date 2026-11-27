package com.ondemandmonitoring.aiops;

import com.ondemandmonitoring.aiops.service.ChecklistVisionIncidentService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ChecklistVisionIncidentServiceTest {
    @Test void contextWithMockJdbcTemplateDoesNotRequireDatasourceUntilPersistence() {
        var db = mock(JdbcTemplate.class);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(JdbcTemplate.class, () -> db);
            context.register(ChecklistVisionIncidentService.class);
            context.refresh();
            assertThat(context.getBean(ChecklistVisionIncidentService.class)).isNotNull();
            verify(db, never()).getDataSource();
        }
    }

    @Test void ignoredFailuresDoNotTouchDatabase() {
        var db = mock(JdbcTemplate.class);
        var service = new ChecklistVisionIncidentService(db);
        service.recordFailure("AI-safe", 403, "FORBIDDEN");
        service.recordFailure("AI-safe", 200, "MISMATCH");
        service.recordFailure("AI-safe", 502, "provider body containing secret");
        service.recordFailure("AI-safe", 502, null);
        service.recordFailure("unsafe\ntrace", 502, "AI_RESPONSE_INVALID");
        service.recovered(null);
        verifyNoInteractions(db);
    }

    @Test void unavailableDatasourceRemainsFailSoft() {
        var service = new ChecklistVisionIncidentService(mock(JdbcTemplate.class));
        assertThatCode(() -> {
            service.recordFailure("AI-down", 503, "AI_PROVIDER_UNAVAILABLE");
            service.recovered("AI-up");
        }).doesNotThrowAnyException();
    }
}
