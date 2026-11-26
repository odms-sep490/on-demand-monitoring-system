package com.ondemandmonitoring.prompt;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.prompt.PromptDtos.*;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
public class PromptManagementController {
    private final PromptManagementService prompts;

    @Value("${DESCRIPTION_AI_TOKEN:}")
    private String internalToken;

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/api/admin/ai/prompts")
    public ApiResponse<?> listPrompts(
            @RequestParam(required = false) String feature,
            @RequestParam(required = false) String search
    ) {
        return ApiResponse.ok(prompts.listPrompts(feature, search));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/api/admin/ai/prompts")
    public ApiResponse<PromptSummaryView> createPrompt(@Valid @RequestBody CreatePromptRequest request) {
        return ApiResponse.ok(prompts.createPrompt(request));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/api/admin/ai/prompts/{promptKey}")
    public ApiResponse<PromptSummaryView> updatePrompt(
            @PathVariable String promptKey,
            @Valid @RequestBody UpdatePromptRequest request
    ) {
        return ApiResponse.ok(prompts.updatePrompt(promptKey, request));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/api/admin/ai/prompts/{promptKey}")
    public ApiResponse<Void> deletePrompt(@PathVariable String promptKey) {
        prompts.deletePrompt(promptKey);
        return ApiResponse.ok(null);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/api/admin/ai/prompts/{promptKey}/versions/{versionId}")
    public ApiResponse<PromptVersionView> updateDraft(
            @PathVariable String promptKey,
            @PathVariable UUID versionId,
            @Valid @RequestBody UpdateDraftRequest request
    ) {
        return ApiResponse.ok(prompts.updateDraft(promptKey, versionId, request));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/api/admin/ai/prompts/{promptKey}/versions/{versionId}")
    public ApiResponse<Void> deleteVersion(
            @PathVariable String promptKey,
            @PathVariable UUID versionId
    ) {
        prompts.deleteDraftVersion(promptKey, versionId);
        return ApiResponse.ok(null);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/api/admin/ai/prompts/{promptKey}/versions")
    public ApiResponse<?> listVersions(@PathVariable String promptKey) {
        return ApiResponse.ok(prompts.listVersions(promptKey));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/api/admin/ai/prompts/{promptKey}/versions")
    public ApiResponse<PromptVersionView> createDraft(
            @PathVariable String promptKey,
            @Valid @RequestBody CreateDraftRequest request
    ) {
        return ApiResponse.ok(prompts.createDraft(promptKey, request));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/api/admin/ai/prompts/{promptKey}/versions/{versionId}/validate")
    public ApiResponse<ValidationResult> validateVersion(
            @PathVariable String promptKey,
            @PathVariable UUID versionId,
            @RequestBody(required = false) PreviewRequest request
    ) {
        return ApiResponse.ok(prompts.validateVersion(promptKey, versionId, request != null ? request.variables() : null));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/api/admin/ai/prompts/{promptKey}/versions/{versionId}/preview")
    public ApiResponse<PreviewResult> previewVersion(
            @PathVariable String promptKey,
            @PathVariable UUID versionId,
            @RequestBody(required = false) PreviewRequest request
    ) {
        return ApiResponse.ok(prompts.previewVersion(promptKey, versionId, request != null ? request.variables() : Map.of()));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/api/admin/ai/prompts/{promptKey}/versions/{versionId}/test")
    public ApiResponse<TestRunView> testVersion(
            @PathVariable String promptKey,
            @PathVariable UUID versionId,
            @RequestBody(required = false) PlaygroundTestRequest request
    ) {
        PlaygroundTestRequest safe = request != null ? request : new PlaygroundTestRequest(Map.of(), null, null, null);
        return ApiResponse.ok(prompts.runPlaygroundTest(promptKey, versionId, safe));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/api/admin/ai/prompts/{promptKey}/versions/{versionId}/approve")
    public ApiResponse<PromptVersionView> approveVersion(
            @PathVariable String promptKey,
            @PathVariable UUID versionId
    ) {
        return ApiResponse.ok(prompts.approveVersion(promptKey, versionId));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/api/admin/ai/prompts/{promptKey}/versions/{versionId}/activate")
    public ApiResponse<PromptVersionView> activateVersion(
            @PathVariable String promptKey,
            @PathVariable UUID versionId,
            @RequestBody(required = false) ActivateRequest request
    ) {
        return ApiResponse.ok(prompts.activateVersion(promptKey, versionId, request != null ? request.reason() : null, false));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/api/admin/ai/prompts/{promptKey}/versions/{versionId}/rollback")
    public ApiResponse<PromptVersionView> rollbackVersion(
            @PathVariable String promptKey,
            @PathVariable UUID versionId,
            @RequestBody(required = false) ActivateRequest request
    ) {
        return ApiResponse.ok(prompts.activateVersion(promptKey, versionId, request != null ? request.reason() : null, true));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/api/admin/ai/prompts/{promptKey}/activation-history")
    public ApiResponse<?> activationHistory(@PathVariable String promptKey) {
        return ApiResponse.ok(prompts.listActivationHistory(promptKey));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/api/admin/ai/prompts/{promptKey}/test-runs")
    public ApiResponse<?> testRuns(@PathVariable String promptKey) {
        return ApiResponse.ok(prompts.listTestRuns(promptKey));
    }

    /**
     * Authenticated internal endpoint for Python AI workers to resolve the active prompt version
     * without restarting workers (hot-reload).
     */
    @GetMapping("/api/internal/ai/prompts/{promptKey}/active")
    public ApiResponse<ResolvedPrompt> resolveActiveInternal(
            @PathVariable String promptKey,
            @RequestHeader(name = "X-Internal-Token", defaultValue = "") String token
    ) {
        if (internalToken == null || internalToken.isBlank()
                || !MessageDigest.isEqual(internalToken.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid internal token");
        }
        return ApiResponse.ok(prompts.resolve(promptKey, Map.of()));
    }
}
