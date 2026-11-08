package com.ondemandmonitoring.media.dto.response;

import java.time.Instant;

/** Fresh original viewing fallback, authorized independently of the official download action. */
public record MediaViewingResponse(String mediaId, String viewUrl, Instant expiresAt, long fileSize) {}
