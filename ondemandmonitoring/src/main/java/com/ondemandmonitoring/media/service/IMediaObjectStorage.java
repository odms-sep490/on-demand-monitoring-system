package com.ondemandmonitoring.media.service;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

public interface IMediaObjectStorage {

    StoredObject put(String key, String contentType, long contentLength,
                     InputStream inputStream, String diagnosticPrefix);

    default StoredObject put(String bucket, String key, String contentType, long contentLength,
                             InputStream inputStream, String diagnosticPrefix) {
        if (!bucket().equals(bucket)) throw new IllegalArgumentException("Storage provider does not support this bucket");
        return put(key, contentType, contentLength, inputStream, diagnosticPrefix);
    }

    StoredObjectStream open(String bucket, String key);

    String createPresignedGetUrl(String bucket, String key);

    default String createPresignedDownloadUrl(String bucket, String key, String fileName) {
        return createPresignedGetUrl(bucket, key);
    }

    PresignedUpload createPresignedPutUrl(String key, String contentType,
                                          long contentLength, Map<String, String> metadata);

    String createMultipartUpload(String key, String contentType, Map<String, String> metadata);

    PresignedUpload createPresignedPartUrl(String key, String uploadId, int partNumber);

    void completeMultipartUpload(String key, String uploadId, List<PartETag> parts);

    void abortMultipartUpload(String key, String uploadId);

    StoredObjectInfo inspect(String bucket, String key);

    void copy(String bucket, String sourceKey, String targetKey);

    void deleteQuietly(String bucket, String key);

    void delete(String bucket, String key);

    String bucket();

    String prefix();

    long presignedUrlExpiresSeconds();

    record StoredObject(String bucket, String key, String url) {}

    record PresignedUpload(String url, Map<String, List<String>> headers, long expiresInSeconds) {}

    record PartETag(int partNumber, String eTag) {}

    record StoredObjectInfo(Long contentLength, String contentType, Map<String, String> metadata) {}

    record StoredObjectStream(InputStream inputStream, Long contentLength, String contentType) {}
}
