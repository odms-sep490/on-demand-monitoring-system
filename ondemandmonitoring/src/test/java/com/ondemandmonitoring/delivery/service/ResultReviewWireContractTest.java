package com.ondemandmonitoring.delivery.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ondemandmonitoring.delivery.dto.response.ResultReviewResponse;
import com.ondemandmonitoring.delivery.dto.response.ResultRevisionTicketReviewResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResultReviewWireContractTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test void orderReviewSerializesFeFieldNames() throws Exception {
        var value = new ResultReviewResponse("round", 2, 7L, Instant.EPOCH, 3L, true, true, null,
                new ResultReviewResponse.RevisionSummary("request", "ticket", "TKT-1"), 0, 50, 1,
                List.of(new ResultReviewResponse.Item("item", "check", "Roof", "Inspect roof", List.of("Capture"),
                        List.of(), List.of(), "mission", "M-001", List.of(), false, true)));
        var json = mapper.readTree(mapper.writeValueAsString(value));
        assertThat(json.has("roundVersion")).isTrue();
        assertThat(json.has("existingRequest")).isTrue();
        assertThat(json.path("existingRequest").has("revisionRequestId")).isTrue();
        assertThat(json.path("items").get(0).has("title")).isTrue();
        assertThat(json.path("items").get(0).has("description")).isTrue();
        assertThat(json.path("items").get(0).has("executionSteps")).isTrue();
        assertThat(json.path("items").get(0).has("name")).isFalse();
        assertThat(json.path("items").get(0).has("guidance")).isFalse();
    }

    @Test void ticketReviewIncludesOnlySelectedEvidenceIdsAndSelectedFeedback() throws Exception {
        var evidence = new ResultReviewResponse.Evidence("e1", "media", "IMAGE", "roof.jpg", 123L,
                Instant.EPOCH, null, null, false, "RETENTION_EXPIRED", null, null);
        var item = new ResultRevisionTicketReviewResponse.Item("item", "check", "Roof", "Inspect roof", List.of(),
                List.of(), List.of(), "mission", "M-001", "POOR_QUALITY", "Too blurry", List.of("e1"), List.of(evidence));
        var value = new ResultRevisionTicketReviewResponse("ticket", "TKT-1", "order", "round", 2, 7L,
                Instant.EPOCH, null, Instant.EPOCH, List.of(item), List.of());
        var json = mapper.readTree(mapper.writeValueAsString(value));
        var row = json.path("items").get(0);
        assertThat(row.path("roundEvidenceIds").get(0).asText()).isEqualTo("e1");
        assertThat(row.path("reasonCode").asText()).isEqualTo("POOR_QUALITY");
        assertThat(row.path("note").asText()).isEqualTo("Too blurry");
        assertThat(row.has("checklistName")).isFalse();
    }
}
