package com.ondemandmonitoring.prompt;

import com.ondemandmonitoring.common.api.ApiResponse;
import java.io.IOException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
public class ChecklistVisionPlaygroundController {
    private final ChecklistVisionPlaygroundService playground;

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping(value = "/api/admin/ai-ops/checklist-vision/playground", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ChecklistVisionPlaygroundService.Result> assess(
            @RequestParam MultipartFile image, @RequestParam UUID promptVersionId,
            @RequestParam String criteria, @RequestParam String language) throws IOException {
        return ApiResponse.ok(playground.assess(image, promptVersionId, criteria, language));
    }
}
