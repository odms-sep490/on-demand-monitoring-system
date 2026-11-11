package com.ondemandmonitoring.media.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.common.api.PageResponse;
import com.ondemandmonitoring.media.dto.response.CustomerMissionMediaStatusResponse;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import com.ondemandmonitoring.media.dto.response.CustomerMediaResponse;
import com.ondemandmonitoring.media.dto.response.OriginalMediaDownloadResponse;
import com.ondemandmonitoring.media.dto.response.MediaViewingResponse;
import com.ondemandmonitoring.media.service.ICustomerMediaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Customer Media", description = "APIs for customer media retrieval and notifications")
@RestController
@Validated
@RequestMapping("/api")
@RequiredArgsConstructor
@PreAuthorize("hasRole('CUSTOMER')")
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class CustomerMediaController {

    ICustomerMediaService customerMedia;

    @GetMapping("/customer/missions/{missionId}/media-status")
    @Operation(summary = "Get mission result availability and processing counts")
    public ResponseEntity<ApiResponse<CustomerMissionMediaStatusResponse>> missionMediaStatus(
            @PathVariable String missionId) {
        return ResponseEntity.ok(ApiResponse.ok(customerMedia.getMissionMediaStatus(missionId)));
    }

    @GetMapping("/customer/missions/{missionId}/media")
    @Operation(summary = "Page available media in customer-owned mission")
    public ResponseEntity<ApiResponse<PageResponse<CustomerMediaResponse>>> missionMedia(
            @PathVariable String missionId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "12") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(ApiResponse.ok(customerMedia.listAvailablePage(missionId, page, size)));
    }

    @GetMapping("/customer/missions/{missionId}/media/{mediaId}")
    @Operation(summary = "Get a fresh viewing URL for available media in customer-owned mission")
    public ResponseEntity<ApiResponse<CustomerMediaResponse>> missionMediaItem(
            @PathVariable String missionId, @PathVariable String mediaId) {
        return ResponseEntity.ok(ApiResponse.ok(customerMedia.getAvailableInMission(missionId, mediaId)));
    }

    @Operation(summary = "List all available media for customer", description = "Retrieves all ready media assets belonging to customer's orders")
    @GetMapping("/customer/available-media")
    public ResponseEntity<ApiResponse<List<CustomerMediaResponse>>> allAvailable() {
        return ResponseEntity.ok(ApiResponse.ok(customerMedia.listAllAvailable()));
    }


    @Operation(summary = "List available media by mission", description = "Retrieves available media assets for a specific mission")
    @GetMapping("/missions/{missionId}/available-media")
    public ResponseEntity<ApiResponse<List<CustomerMediaResponse>>> list(@PathVariable String missionId) {
        return ResponseEntity.ok(ApiResponse.ok(customerMedia.listAvailable(missionId)));
    }

    @Operation(summary = "Get customer media metadata", description = "Returns preview derivatives and download eligibility; use the explicit download-url endpoint for an authorized original download")
    @GetMapping("/media/{mediaId}/download")
    public ResponseEntity<ApiResponse<CustomerMediaResponse>> download(@PathVariable String mediaId) {
        return ResponseEntity.ok(ApiResponse.ok(customerMedia.getAvailable(mediaId)));
    }

    @Operation(summary = "Create an authorized short-lived original media download URL")
    @PostMapping("/customer/media/{mediaId}/download-url")
    public ResponseEntity<ApiResponse<OriginalMediaDownloadResponse>> originalDownload(@PathVariable String mediaId) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store, private")
                .body(ApiResponse.ok(customerMedia.createOriginalDownload(mediaId)));
    }

    @PostMapping("/customer/media/{mediaId}/view-url")
    @Operation(summary = "Refresh a customer media viewing URL, using a derivative when available")
    public ResponseEntity<ApiResponse<MediaViewingResponse>> viewingFallback(
            @PathVariable String mediaId) {
        return ResponseEntity.ok().header("Cache-Control", "no-store, private")
                .body(ApiResponse.ok(customerMedia.createViewingFallback(mediaId)));
    }

}
