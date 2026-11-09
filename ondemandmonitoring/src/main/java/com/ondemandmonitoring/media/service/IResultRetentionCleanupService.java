package com.ondemandmonitoring.media.service;

public interface IResultRetentionCleanupService {
    CleanupSummary cleanupExpiredResults();

    record CleanupSummary(int processed, int deleted, int failed, long durationMs) {}
}
