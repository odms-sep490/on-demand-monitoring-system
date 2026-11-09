package com.ondemandmonitoring.media.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.media.dto.response.MediaAssetResponse;
import com.ondemandmonitoring.media.dto.response.MediaResponse;
import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.media.enums.MediaStatus;
import com.ondemandmonitoring.media.service.IMediaAssetService;
import com.ondemandmonitoring.media.service.impl.MediaDerivativeService;
import com.ondemandmonitoring.media.service.IMediaAssetService.MediaContent;
import com.ondemandmonitoring.media.mapper.MediaAssetMapper;
import java.time.Instant;
import java.util.List;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Media & Assets",
        description = "APIs for uploading and retrieving images/media captured by devices")
@RestController
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class MediaController {

    IMediaAssetService mediaAssetService;
    MediaDerivativeService derivatives;
    MediaAssetMapper mediaAssetMapper;
    com.ondemandmonitoring.mission.service.IMissionMediaAccessService missionAccess;

    @Operation(summary = "Upload image for mission",
            description = "Uploads a photo captured during a specific mission to S3 storage")
    @PostMapping(path = "/api/missions/{missionId}/images",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<MediaAssetResponse>> uploadMissionImage(
            @PathVariable String missionId,
            @RequestParam("deviceId") String deviceId,
            @RequestParam("capturedAt") Instant capturedAt,
            @RequestPart("image") MultipartFile image) {
        MediaAsset uploaded = mediaAssetService.upload(missionId, deviceId, capturedAt, image);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.created("Image uploaded",
                        mediaAssetMapper.toResponse(uploaded)));
    }

    @Operation(summary = "Get media by ID",
            description = "Retrieves metadata and presigned Amazon S3 URL for a media asset")
    @GetMapping("/api/media/{mediaId}")
    public ResponseEntity<ApiResponse<MediaResponse>> getMedia(@PathVariable String mediaId) {
        MediaAsset image = mediaAssetService.getById(mediaId);
        missionAccess.authorizeOperator(image.getMissionId());
        requireLegacyMedia(image);
        String presignedUrl = mediaAssetService.createPresignedGetUrl(image);

        return ResponseEntity.ok(ApiResponse.ok(mediaAssetMapper.toMediaResponse(
                image,
                presignedUrl,
                mediaAssetService.presignedUrlExpiresSeconds(), derivatives.thumbnailUrl(image),
                derivatives.previewUrl(image))));
    }

    @Operation(summary = "List mission media",
            description = "Lists image/video assets captured for a mission")
    @GetMapping("/api/missions/{missionId}/media")
    public ResponseEntity<ApiResponse<List<MediaResponse>>> listMissionMedia(
            @PathVariable String missionId,
            @RequestParam(required = false) String mediaType) {
        String canonicalId = missionAccess.authorizeOperator(missionId).getId();
        List<MediaResponse> media = mediaAssetService
                .listByMission(canonicalId, mediaType)
                .stream()
                .filter(asset -> asset.getMediaStatus() == null || asset.getValidatedAt() != null
                        && java.util.Set.of(MediaStatus.PENDING_MANAGER_APPROVAL,
                                MediaStatus.AVAILABLE,
                                MediaStatus.REJECTED).contains(asset.getMediaStatus()))
                .map(asset -> mediaAssetMapper.toMediaResponse(
                        asset,
                        mediaAssetService.createPresignedGetUrl(asset),
                        mediaAssetService.presignedUrlExpiresSeconds(), derivatives.thumbnailUrl(asset),
                        derivatives.previewUrl(asset)))
                .toList();

        return ResponseEntity.ok(ApiResponse.ok(media));
    }

    @Operation(summary = "Get media file",
            description = "Streams the original image/video file from configured storage")
    @GetMapping("/api/media/{mediaId}/file")
    public ResponseEntity<InputStreamResource> getMediaFile(@PathVariable String mediaId) {
        MediaAsset image = mediaAssetService.getById(mediaId);
        missionAccess.authorizeOperator(image.getMissionId());
        requireLegacyMedia(image);
        MediaContent mediaContent = mediaAssetService.openMedia(image);
        MediaType contentType = MediaType.parseMediaType(mediaContent.contentType());

        return ResponseEntity.ok()
                .contentType(contentType)
                .contentLength(mediaContent.contentLength())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(mediaContent.fileName())
                        .build()
                        .toString())
                .body(new InputStreamResource(mediaContent.inputStream()));
    }

    private void requireLegacyMedia(MediaAsset asset) {
        if (asset.getMediaStatus() != null) {
            throw new ApiException(ErrorCode.MEDIA_NOT_FOUND);
        }
    }
}
