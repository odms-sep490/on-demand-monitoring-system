package com.ondemandmonitoring.media.enums;

/**
 * Why a client reported an upload-failure event.
 * <p>
 * {@link #TRANSFER_FAILED} is the only category that counts as an actual failed byte
 * transfer. {@link #TRANSFER_ACK_LOST} means the bytes may already be in storage and the
 * acknowledgement was lost, so the backend must re-inspect the object and reuse the normal
 * validation workflow instead of inventing a failure.
 */
public enum UploadFailureCategory {
    /** The S3 PUT / multipart part genuinely failed; this is a qualifying failed transfer. */
    TRANSFER_FAILED,
    /** Transfer outcome unknown (lost acknowledgement); the object must be re-inspected. */
    TRANSFER_ACK_LOST
}