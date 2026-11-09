package com.ondemandmonitoring.media.dto.request;

import com.ondemandmonitoring.media.enums.UploadFailureCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

/**
 * A client-journaled upload-failure event being (re)played to the Backend.
 * <p>
 * Every field except {@code code}/{@code message} is optional so existing synchronous callers
 * keep working. When {@code eventId} is supplied the request becomes an idempotent ledger
 * entry: the Backend durably records it before applying it and the client may delete its
 * journal copy only after a {@code durablyAccepted} acknowledgment.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class ReportUploadFailureRequest {

    /** Client-generated idempotency key. Presence switches the request into ledger mode. */
    @Size(max = 64)
    String eventId;

    /** Client category; only TRANSFER_FAILED counts as an actual failed transfer. */
    UploadFailureCategory category;

    @Size(max = 100)
    String missionId;

    @Size(max = 100)
    String mediaId;

    /** Attempt this event refers to; proves an attempt row existed for a real transfer. */
    @Size(max = 100)
    String attemptId;

    @Size(max = 100)
    String localMediaId;

    @Positive
    Integer attemptNumber;

    /** Capture manifest reference, retained for reconciliation diagnostics only. */
    @Size(max = 64)
    String checksumSha256;

    /** Client clock instant the failure was observed; used for ordering. */
    Instant occurredAt;

    @NotBlank
    @Size(max = 100)
    String code;

    @NotBlank
    @Size(max = 1000)
    String message;
}