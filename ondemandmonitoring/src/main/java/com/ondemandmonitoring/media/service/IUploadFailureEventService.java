package com.ondemandmonitoring.media.service;

import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.dto.request.ReportUploadFailureRequest;
import com.ondemandmonitoring.media.dto.response.UploadFailureAckResponse;

public interface IUploadFailureEventService {

    /**
     * Durably records and applies a client-journaled upload-failure event.
     * <p>
     * Idempotent by {@code eventId}: concurrent and repeated delivery collapse onto one
     * ledger row, so a replay can never double-count a failed transfer.
     *
     * @return an acknowledgment whose {@code durablyAccepted} authorises client deletion
     */
    UploadFailureAckResponse ingest(ReportUploadFailureRequest request);

    /**
     * Applies a previously accepted but unprocessed event. Used by the reconciliation job and
     * by retries when storage inspection was unavailable at ingest time.
     */
    UploadFailureAckResponse retryPending();
}