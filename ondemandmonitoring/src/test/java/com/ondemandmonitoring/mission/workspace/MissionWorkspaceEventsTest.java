package com.ondemandmonitoring.mission.workspace;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.*;

class MissionWorkspaceEventsTest {
    @Test void coalescesOnlyAfterCommitAndDiscardsRollback() {
        var publisher = mock(ApplicationEventPublisher.class);
        var events = new MissionWorkspaceEvents(publisher);
        var manager = new AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object tx, org.springframework.transaction.TransactionDefinition d) {}
            @Override protected void doCommit(DefaultTransactionStatus s) {}
            @Override protected void doRollback(DefaultTransactionStatus s) {}
        };
        var tx = new TransactionTemplate(manager);
        tx.executeWithoutResult(status -> {
            events.changed("m", Set.of("checklist"));
            events.changed("m", Set.of("permissions"), Set.of("revoked"));
            verifyNoInteractions(publisher);
        });
        verify(publisher).publishEvent(new MissionWorkspaceEvents.Changed("m", Set.of("checklist", "permissions"), Set.of("revoked")));
        clearInvocations(publisher);
        tx.executeWithoutResult(status -> { events.changed("m", MissionWorkspaceEvents.ALL); status.setRollbackOnly(); });
        events.changed("m", MissionWorkspaceEvents.ALL);
        verifyNoInteractions(publisher);
    }

    @Test void tokensAreDeterministicAndDoNotCoupleChecklistToResult() {
        var empty = MissionWorkspaceVersionService.tokens("m", Map.of());
        assertThat(empty).isEqualTo(MissionWorkspaceVersionService.tokens("m", Map.of()));
        assertThat(List.of(empty.checklist(), empty.result(), empty.permissions(), empty.media(), empty.corrections()))
                .allMatch(t -> t.matches("[a-f0-9]{64}"));
        var attached = MissionWorkspaceVersionService.tokens("m", Map.of("evidence", "new-row"));
        assertThat(attached.checklist()).isNotEqualTo(empty.checklist());
        assertThat(attached.result()).isEqualTo(empty.result());
        var quality = MissionWorkspaceVersionService.tokens("m", Map.of("quality_reviews", "new-review"));
        assertThat(quality.checklist()).isNotEqualTo(empty.checklist());
        assertThat(quality.media()).isEqualTo(empty.media());
        assertThat(quality.result()).isEqualTo(empty.result());
        var approved = MissionWorkspaceVersionService.tokens("m", Map.of("results", "approved-inspector-confirmed"));
        assertThat(approved.permissions()).isNotEqualTo(empty.permissions());
        assertThat(MissionWorkspaceVersionService.sql(false, false)).doesNotContain("from image_quality_check", "from image_quality_review");
    }
}
