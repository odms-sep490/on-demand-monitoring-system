package com.ondemandmonitoring.media.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.media.dto.request.RemoveMediaRequest;
import com.ondemandmonitoring.media.dto.response.MediaRemovalResponse;
import com.ondemandmonitoring.media.service.IMediaRemovalService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/operator/missions/{missionId}/media")
@PreAuthorize("hasAnyRole('STAFF', 'MANAGER', 'ADMIN')")
@RequiredArgsConstructor
public class MediaRemovalController {
    private final IMediaRemovalService removals;

    @PostMapping("/{mediaId}/removal")
    public ApiResponse<MediaRemovalResponse> remove(@PathVariable String missionId, @PathVariable String mediaId,
                                                   @Valid @RequestBody RemoveMediaRequest request) {
        return ApiResponse.ok(removals.remove(missionId, mediaId, request));
    }
}
