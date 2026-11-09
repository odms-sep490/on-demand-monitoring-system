package com.ondemandmonitoring.delivery.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** Customer feedback requesting a new revision of the released preview. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RevisionRequest(String reviewRoundId, Long expectedRoundVersion, UUID clientRequestId,
        @Size(max = 2000) String generalNote, List<@jakarta.validation.Valid Item> items) {
    public record Item(String roundItemId, String reasonCode, @Size(max = 2000) String note,
            List<String> roundEvidenceIds) {}
}
