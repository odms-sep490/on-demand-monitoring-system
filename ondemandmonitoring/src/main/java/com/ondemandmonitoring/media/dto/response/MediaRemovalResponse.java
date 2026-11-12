package com.ondemandmonitoring.media.dto.response;

public record MediaRemovalResponse(String mediaId, boolean removed, boolean cleanupScheduled) {}
