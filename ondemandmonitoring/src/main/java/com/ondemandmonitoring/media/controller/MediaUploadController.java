package com.ondemandmonitoring.media.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.media.dto.request.CompleteMultipartRequest;
import com.ondemandmonitoring.media.dto.request.PrepareMediaUploadRequest;
import com.ondemandmonitoring.media.dto.request.ReportUploadFailureRequest;
import com.ondemandmonitoring.media.dto.response.MediaUploadResponse;
import com.ondemandmonitoring.media.dto.response.ManualMediaUploadResponse;
import com.ondemandmonitoring.media.dto.request.ManualMediaFileRequest;
import java.util.List;
import com.ondemandmonitoring.media.service.IMediaUploadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Media Upload Workflow", description = "APIs for managing device media upload workflow and S3 presigned URLs")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('MANAGER', 'STAFF', 'ADMIN')")
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class MediaUploadController {

    IMediaUploadService uploads;
    com.ondemandmonitoring.media.service.IUploadFailureEventService failureEvents;

    @Operation(summary = "List unresolved manual media uploads for an authorized mission")
    @GetMapping("/missions/{missionId}/manual-media-uploads")
    public ResponseEntity<ApiResponse<List<ManualMediaUploadResponse>>> manualTasks(
            @PathVariable String missionId) {
        return ResponseEntity.ok(ApiResponse.ok(uploads.manualTasks(missionId)));
    }

    @Operation(summary = "Prepare manual upload of an exact PC backup")
    @PostMapping("/media/{mediaId}/manual-file-upload")
    public ResponseEntity<ApiResponse<MediaUploadResponse>> manualFile(@PathVariable String mediaId,
            @Valid @RequestBody ManualMediaFileRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(uploads.prepareManualFile(mediaId, request)));
    }

    @Operation(summary = "Prepare media upload", description = "Validates media metadata and creates presigned S3 upload URL or multipart upload session")
    @PostMapping("/missions/{missionId}/media-uploads")
    public ResponseEntity<ApiResponse<MediaUploadResponse>> prepare(
            @PathVariable String missionId, @Valid @RequestBody PrepareMediaUploadRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(uploads.prepare(missionId, request)));
    }

    @Operation(summary = "Get upload status", description = "Retrieves the current upload attempt status for a media asset")
    @GetMapping("/media/{mediaId}/upload-status")
    public ResponseEntity<ApiResponse<MediaUploadResponse>> status(@PathVariable String mediaId) {
        return ResponseEntity.ok(ApiResponse.ok(uploads.status(mediaId)));
    }

    @Operation(summary = "Retry automatic upload", description = "Creates a new automatic upload attempt for a failed media upload")
    @PostMapping("/media/{mediaId}/upload-attempts")
    public ResponseEntity<ApiResponse<MediaUploadResponse>> retry(@PathVariable String mediaId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(uploads.retry(mediaId, false)));
    }

    @Operation(summary = "Retry manual upload", description = "Creates a new manual upload attempt for a failed media upload requiring operator intervention")
    @PostMapping("/media/{mediaId}/manual-upload-attempts")
    public ResponseEntity<ApiResponse<MediaUploadResponse>> manual(@PathVariable String mediaId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(uploads.retry(mediaId, true)));
    }

    @Operation(summary = "Presign multipart upload part URL", description = "Generates presigned S3 upload URL for a specific part number in a multipart upload")
    @PostMapping("/media/{mediaId}/upload-attempts/{attemptId}/parts/{partNumber}/url")
    public ResponseEntity<ApiResponse<MediaUploadResponse>> partUrl(@PathVariable String mediaId,
                                                                    @PathVariable String attemptId,
                                                                    @PathVariable int partNumber) {
        return ResponseEntity.ok(ApiResponse.ok(uploads.presignPart(mediaId, attemptId, partNumber)));
    }

    @Operation(summary = "Complete multipart upload", description = "Completes a multipart upload by submitting part ETag metadata")
    @PostMapping("/media/{mediaId}/upload-attempts/{attemptId}/complete-multipart")
    public ResponseEntity<ApiResponse<Void>> completeMultipart(@PathVariable String mediaId,
                                                               @PathVariable String attemptId,
                                                               @Valid @RequestBody CompleteMultipartRequest request) {
        uploads.completeMultipart(mediaId, attemptId, request);
        return ResponseEntity.ok(ApiResponse.ok("Multipart upload completed.", null));
    }

    @Operation(summary = "Mark upload completed", description = "Acknowledges that S3 object upload was finished successfully")
    @PostMapping("/media/{mediaId}/upload-attempts/{attemptId}/uploaded")
    public ResponseEntity<ApiResponse<Void>> uploaded(@PathVariable String mediaId,
                                                       @PathVariable String attemptId) {
        uploads.markUploaded(mediaId, attemptId);
        return ResponseEntity.ok(ApiResponse.ok("Upload acknowledged.", null));
    }

    @Operation(summary = "Report upload failure", description = "Reports an upload failure code and message for an attempt")
    @PostMapping("/media/{mediaId}/upload-attempts/{attemptId}/failures")
    public ResponseEntity<ApiResponse<Object>> failure(@PathVariable String mediaId,
                                                       @PathVariable String attemptId,
                                                       @Valid @RequestBody ReportUploadFailureRequest request) {
        request.setMediaId(mediaId);
        request.setAttemptId(attemptId);
        if (request.getEventId() != null && !request.getEventId().isBlank()) {
            // Ledger mode: durably records before applying, returning an acknowledgment.
            return ResponseEntity.ok(ApiResponse.ok(failureEvents.ingest(request)));
        }
        return ResponseEntity.ok(ApiResponse.ok(uploads.reportFailure(mediaId, attemptId, request)));
    }
}
