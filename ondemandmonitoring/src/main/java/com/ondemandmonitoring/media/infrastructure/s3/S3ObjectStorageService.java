package com.ondemandmonitoring.media.infrastructure.s3;

import com.ondemandmonitoring.media.service.IMediaObjectStorage;

import java.io.InputStream;
import java.time.Duration;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class S3ObjectStorageService implements IMediaObjectStorage {

    private static final long PRESIGNED_URL_EXPIRES_SECONDS = 900;

    S3Client s3Client;
    S3Presigner s3Presigner;
    AwsS3Properties awsS3Properties;

    @Override
    public StoredObject put(
            String key,
            String contentType,
            long contentLength,
            InputStream inputStream,
            String diagnosticPrefix) {
        String bucket = bucket();
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .contentLength(contentLength)
                .build();


        s3Client.putObject(putObjectRequest, RequestBody.fromInputStream(inputStream, contentLength));
        return new StoredObject(bucket, key, "s3://" + bucket + "/" + key);
    }

    @Override
    public StoredObjectStream open(String bucket, String key) {
        ResponseInputStream<GetObjectResponse> inputStream = s3Client.getObject(GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build());
        GetObjectResponse response = inputStream.response();
        return new StoredObjectStream(inputStream, response.contentLength(), response.contentType());
    }

    @Override
    public String createPresignedGetUrl(String bucket, String key) {
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(PRESIGNED_URL_EXPIRES_SECONDS))
                .getObjectRequest(getObjectRequest)
                .build();

        return s3Presigner.presignGetObject(presignRequest).url().toString();
    }

    @Override
    public PresignedUpload createPresignedPutUrl(String key, String contentType, long contentLength,
                                                 Map<String, String> metadata) {
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(bucket()).key(key).contentType(contentType).contentLength(contentLength)
                .metadata(metadata).build();
        var signed = s3Presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(PRESIGNED_URL_EXPIRES_SECONDS))
                .putObjectRequest(put).build());
        return new PresignedUpload(signed.url().toString(), signed.signedHeaders(), PRESIGNED_URL_EXPIRES_SECONDS);
    }

    @Override
    public String createMultipartUpload(String key, String contentType, Map<String, String> metadata) {
        return s3Client.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(bucket()).key(key).contentType(contentType).metadata(metadata).build()).uploadId();
    }

    @Override
    public PresignedUpload createPresignedPartUrl(String key, String uploadId, int partNumber) {
        UploadPartRequest part = UploadPartRequest.builder()
                .bucket(bucket()).key(key).uploadId(uploadId).partNumber(partNumber).build();
        var signed = s3Presigner.presignUploadPart(UploadPartPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(PRESIGNED_URL_EXPIRES_SECONDS))
                .uploadPartRequest(part).build());
        return new PresignedUpload(signed.url().toString(), signed.signedHeaders(), PRESIGNED_URL_EXPIRES_SECONDS);
    }

    @Override
    public void completeMultipartUpload(String key, String uploadId, List<PartETag> parts) {
        List<CompletedPart> completed = parts.stream()
                .map(part -> CompletedPart.builder().partNumber(part.partNumber()).eTag(part.eTag()).build())
                .toList();
        s3Client.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                .bucket(bucket()).key(key).uploadId(uploadId)
                .multipartUpload(CompletedMultipartUpload.builder().parts(completed).build()).build());
    }

    @Override
    public void abortMultipartUpload(String key, String uploadId) {
        try {
            s3Client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(bucket()).key(key).uploadId(uploadId).build());
        } catch (software.amazon.awssdk.services.s3.model.S3Exception failure) {
            if (failure.statusCode() != 404) throw failure;
        }
    }

    @Override
    public StoredObjectInfo inspect(String bucket, String key) {
        var head = s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
        return new StoredObjectInfo(head.contentLength(), head.contentType(), head.metadata());
    }

    @Override
    public void copy(String bucket, String sourceKey, String targetKey) {
        s3Client.copyObject(CopyObjectRequest.builder()
                .sourceBucket(bucket).sourceKey(sourceKey)
                .destinationBucket(bucket).destinationKey(targetKey).build());
    }

    @Override
    public void deleteQuietly(String bucket, String key) {
        try {
            delete(bucket, key);
        } catch (RuntimeException exception) {
            log.warn("Failed to cleanup S3 object. bucket={}, key={}", bucket, key, exception);
        }
    }

    @Override
    public void delete(String bucket, String key) {
        s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    @Override
    public String bucket() {
        return clean(awsS3Properties.getBucket());
    }

    @Override
    public String prefix() {
        String prefix = clean(awsS3Properties.getPrefix());
        return prefix == null ? "" : prefix.replaceAll("^/+|/+$", "");
    }

    @Override
    public long presignedUrlExpiresSeconds() {
        return PRESIGNED_URL_EXPIRES_SECONDS;
    }

    private String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() >= 2
                && ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'")))) {
            return trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

}
