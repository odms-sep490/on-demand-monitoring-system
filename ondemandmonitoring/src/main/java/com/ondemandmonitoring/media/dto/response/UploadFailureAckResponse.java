package com.ondemandmonitoring.media.dto.response;

import com.ondemandmonitoring.media.enums.MediaStatus;
import com.ondemandmonitoring.media.enums.UploadFailureCategory;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

/**
 * Acknowledgment for a reported upload failure.
 * <p>
 * CONTRACT: {@code durablyAccepted} means <strong>the Backend has durably stored this event and
 * now owns every subsequent retry</strong> — it does NOT mean the event has been fully applied.
 * Once it is true the client is authorised to delete its journal entry and MUST NOT resend.
 * <p>
 * {@code retryLater} reports that the stored event is still awaiting processing (for example a
 * {@code TRANSFER_ACK_LOST} event whose object could not be inspected). Such an event is
 * completed by the Backend's own retry job, never by the client. {@code duplicate} marks an
 * idempotent re-delivery that was already recorded.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class UploadFailureAckResponse {

    /** The only value that authorises the client to delete its journal entry. */
    boolean durablyAccepted;

    /** True when this event had already been recorded by an earlier or concurrent delivery. */
    boolean duplicate;

    String mediaId;

    /** Effective media status after applying the event, when it could be applied. */
    MediaStatus status;

    /** Classification actually applied; TRANSFER_ACK_LOST may resolve to no failure at all. */
    UploadFailureCategory category;

    /** Namespaced idempotency key the Backend recorded. */
    String eventKey;

    /** Set when the event is recorded but still awaiting inspection/validation. */
    boolean retryLater;
}