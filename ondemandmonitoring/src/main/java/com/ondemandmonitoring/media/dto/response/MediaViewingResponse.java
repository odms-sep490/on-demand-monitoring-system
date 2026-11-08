package com.ondemandmonitoring.media.dto.response;

import java.time.Instant;

/** Fresh customer viewing URL for a derivative, or the original when no derivative is usable. */
public record MediaViewingResponse(String mediaId, String viewUrl, Instant expiresAt, long fileSize) {}
