package com.ondemandmonitoring.media.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ondemandmonitoring.media.infrastructure.s3.*;

import org.junit.jupiter.api.Test;

import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class S3MultipartAbortTest {
    @Test
    void onlyMissingUploadIsIdempotentAndBucketOrPermissionErrorsPropagate() {
        var client = mock(S3Client.class);
        var properties = new AwsS3Properties();
        properties.setBucket("bucket");
        var storage = new S3ObjectStorageService(client, mock(S3Presigner.class), properties);
        var missing = error("NoSuchUpload", 404);
        when(client.abortMultipartUpload(any(AbortMultipartUploadRequest.class)))
                .thenThrow(missing);
        assertDoesNotThrow(() -> storage.abortMultipartUpload("key", "upload"));
        for (var failure :
                java.util.List.of(
                        error("NoSuchBucket", 404),
                        error("AccessDenied", 403),
                        error("Unknown", 404))) {
            when(client.abortMultipartUpload(any(AbortMultipartUploadRequest.class)))
                    .thenThrow(failure);
            assertSame(
                    failure,
                    assertThrows(
                            S3Exception.class,
                            () -> storage.abortMultipartUpload("key", "upload")));
        }
    }

    private S3Exception error(String code, int status) {
        var builder = S3Exception.builder();
        builder.statusCode(status);
        builder.awsErrorDetails(AwsErrorDetails.builder().errorCode(code).build());
        return (S3Exception) builder.build();
    }
}
