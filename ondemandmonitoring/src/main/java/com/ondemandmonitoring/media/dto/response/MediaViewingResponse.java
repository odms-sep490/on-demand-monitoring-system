package com.ondemandmonitoring.media.dto.response;

import java.time.Instant;

/** Fresh viewing URL. Customer recovery is derivative-only; internal original inspection is explicitly authorized. */
public record MediaViewingResponse(String mediaId, String viewUrl, Instant expiresAt, long fileSize) {}
