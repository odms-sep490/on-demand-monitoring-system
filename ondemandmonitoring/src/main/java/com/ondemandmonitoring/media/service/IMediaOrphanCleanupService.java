package com.ondemandmonitoring.media.service;

/** Detach records intent in the caller's transaction; the worker deletes after commit. */
public interface IMediaOrphanCleanupService {
    void request(String mediaId);
    void cancel(String mediaId);
    int cleanup();
}
