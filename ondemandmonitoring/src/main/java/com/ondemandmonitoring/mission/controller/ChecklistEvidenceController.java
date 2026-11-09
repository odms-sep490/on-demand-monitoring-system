package com.ondemandmonitoring.mission.controller;
import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.mission.dto.request.*;
import com.ondemandmonitoring.mission.dto.response.*;
import com.ondemandmonitoring.mission.service.IChecklistEvidenceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/missions/{missionId}")
@PreAuthorize("hasAnyRole('STAFF','MANAGER','ADMIN')")
@RequiredArgsConstructor
@Validated
public class ChecklistEvidenceController {
    private final IChecklistEvidenceService service;
    @GetMapping("/checklist-evidence/candidates")
    public ApiResponse<List<ChecklistEvidenceCandidate>> candidates(@PathVariable String missionId,
            @RequestParam(defaultValue="0") @Min(0) int page, @RequestParam(defaultValue="50") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(service.candidates(missionId, page, size));
    }
    @PutMapping("/checklist-executions/{executionId}/evidence/{mediaId}")
    public ApiResponse<List<ChecklistEvidenceResponse>> attach(@PathVariable String missionId, @PathVariable String executionId,
            @PathVariable String mediaId, @Valid @RequestBody AttachChecklistEvidenceRequest request) {
        return ApiResponse.ok(service.attach(missionId, new BatchChecklistEvidenceRequest(mediaId,
                List.of(new BatchChecklistEvidenceRequest.Target(executionId, request.expectedVersion())), request.note())));
    }
    @PutMapping("/checklist-executions/{executionId}/customer-selection")
    public ApiResponse<List<ChecklistEvidenceResponse>> selectForCustomer(@PathVariable String missionId,
            @PathVariable String executionId, @Valid @RequestBody ChecklistCustomerSelectionRequest request) {
        return ApiResponse.ok(service.selectForCustomer(missionId, executionId, request));
    }
    @PostMapping("/checklist-evidence/batch")
    public ApiResponse<List<ChecklistEvidenceResponse>> batch(@PathVariable String missionId, @Valid @RequestBody BatchChecklistEvidenceRequest request) {
        return ApiResponse.ok(service.attach(missionId, request));
    }
    @DeleteMapping("/checklist-executions/{executionId}/evidence/{evidenceId}")
    public ApiResponse<Void> detach(@PathVariable String missionId, @PathVariable String executionId, @PathVariable String evidenceId,
            @RequestParam @Min(0) long expectedVersion, @RequestParam(required=false) @Size(max=1000) String reason) {
        service.detach(missionId, executionId, evidenceId, expectedVersion, reason);
        return ApiResponse.ok("Evidence detached", null);
    }
}
