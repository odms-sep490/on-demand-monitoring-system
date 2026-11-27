package com.ondemandmonitoring.ai.controller;

import com.ondemandmonitoring.ai.service.ChecklistImageAiSettingsService;
import com.ondemandmonitoring.ai.service.ChecklistImageAiSettingsService.Status;
import com.ondemandmonitoring.ai.service.ChecklistImageAiSettingsService.UpdateRequest;
import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/config/checklist-image-ai")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminChecklistImageAiSettingsController {
    private final ChecklistImageAiSettingsService settings;
    private final AuthenticatedUserResolver currentUser;

    @GetMapping
    public ApiResponse<Status> current() {
        return ApiResponse.ok(settings.current());
    }

    @PutMapping
    public ApiResponse<Status> update(@Valid @RequestBody UpdateRequest request) {
        return ApiResponse.ok(settings.update(request, currentUser.getCurrentUserId()));
    }
}
