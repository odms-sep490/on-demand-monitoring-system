package com.ondemandmonitoring.media.mapper;

import com.ondemandmonitoring.media.domain.*;
import com.ondemandmonitoring.media.dto.response.*;
import java.util.List;
import java.util.Map;

import com.ondemandmonitoring.media.enums.MediaStatus;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true), imports = MediaStatus.class)
public interface MediaWorkflowMapper {
    @Mapping(target = "mediaId", source = "asset.id")
    @Mapping(target = "mediaType", source = "asset.type")
    @Mapping(target = "fileName", source = "asset.originalFileName")
    @Mapping(target = "status", source = "asset.mediaStatus")
    OperatorMissionMediaResponse toOperatorResponse(MediaAsset asset, String downloadUrl,
            java.time.Instant urlExpiresAt);

    @Mapping(target = "manualTaskId", source = "id")
    @Mapping(target = "backendMediaId", source = "media.id")
    @Mapping(target = "localMediaId", source = "media.localMediaId")
    @Mapping(target = "missionId", expression = "java(task.getMedia().getMissionId())")
    @Mapping(target = "deviceId", expression = "java(task.getMedia().getDeviceId())")
    @Mapping(target = "mediaType", source = "media.type")
    @Mapping(target = "sourceType", source = "media.sourceType")
    @Mapping(target = "fileName", source = "media.originalFileName")
    @Mapping(target = "contentType", source = "media.contentType")
    @Mapping(target = "fileSize", source = "media.fileSize")
    @Mapping(target = "checksumSha256", source = "media.checksumSha256")
    @Mapping(target = "capturedAt", source = "media.capturedAt")
    @Mapping(target = "status", source = "media.mediaStatus")
    ManualMediaUploadResponse toManualResponse(ManualUploadTask task);

    @Mapping(target = "mediaId", source = "asset.id")
    @Mapping(target = "mediaType", source = "asset.type")
    @Mapping(target = "fileName", source = "asset.originalFileName")
    CustomerMediaResponse toCustomerResponse(MediaAsset asset, String downloadUrl);


    @Mapping(target = "mediaId", source = "asset.id")
    @Mapping(target = "attemptId", source = "attempt.id")
    @Mapping(target = "attemptNumber", source = "attempt.attemptNumber")
    @Mapping(target = "status", expression = "java(asset.getMediaStatus() == null ? MediaStatus.AVAILABLE : asset.getMediaStatus())")
    @Mapping(target = "expiresAt", source = "expiresAt")
    @Mapping(target = "uploadMethod", source = "uploadMethod")
    @Mapping(target = "uploadUrl", source = "uploadUrl")
    @Mapping(target = "uploadHeaders", source = "uploadHeaders")
    @Mapping(target = "partSizeBytes", source = "partSizeBytes")
    @Mapping(target = "partCount", source = "partCount")
    @Mapping(target = "manualTaskId", source = "manualTaskId")
    MediaUploadResponse toUploadResponse(MediaAsset asset, MediaUploadAttempt attempt,
            String uploadMethod, String uploadUrl, Map<String, List<String>> uploadHeaders,
            long partSizeBytes, int partCount, java.time.Instant expiresAt, String manualTaskId);
}
