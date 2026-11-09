package com.ondemandmonitoring.media.dto.response;

import java.time.Instant;

/** A short-lived, explicitly requested original download link. */
public record OriginalMediaDownloadResponse(String mediaId, String fileName, String downloadUrl, Instant expiresAt) {}
