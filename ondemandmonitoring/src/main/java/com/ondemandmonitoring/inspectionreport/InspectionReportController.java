package com.ondemandmonitoring.inspectionreport;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.inspectionreport.dto.ReportEditRequest;
import com.ondemandmonitoring.inspectionreport.dto.ReportRevisionRequest;
import com.ondemandmonitoring.inspectionreport.dto.ReportRewriteRequest;
import com.ondemandmonitoring.inspectionreport.dto.ReportRewriteResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class InspectionReportController {

    private final InspectionReportService reports;
    private final ReportGenerationJob generation;

    // Preserve report validation/conflict statuses instead of falling back to generic 500 advice.
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> handleReportError(ResponseStatusException error) {
        return ResponseEntity
                .status(error.getStatusCode())
                .body(ApiResponse.error("REPORT_REQUEST_REJECTED", error.getReason()));
    }

    @GetMapping("/api/missions/{missionId}/inspection-reports/capabilities")
    public ApiResponse<Map<String, Boolean>> capabilities(@PathVariable String missionId) {
        return ApiResponse.ok(reports.capabilities(missionId));
    }

    @GetMapping("/api/missions/{missionId}/inspection-reports")
    public ApiResponse<List<InspectionReportService.View>> list(@PathVariable String missionId) {
        return ApiResponse.ok(reports.list(missionId));
    }

    @PostMapping("/api/missions/{missionId}/inspection-reports")
    public ApiResponse<InspectionReportService.View> create(@PathVariable String missionId) {
        return ApiResponse.ok(reports.create(missionId));
    }

    @GetMapping("/api/inspection-reports/{id}")
    public ApiResponse<InspectionReportService.View> get(@PathVariable UUID id) {
        return ApiResponse.ok(reports.get(id));
    }

    @PatchMapping("/api/inspection-reports/{id}")
    public ApiResponse<InspectionReportService.View> edit(
            @PathVariable UUID id,
            @Valid @RequestBody ReportEditRequest request) {
        return ApiResponse.ok(
                reports.save(id, request.revision(), request.document())
        );
    }

    @PostMapping("/api/inspection-reports/{id}/generate")
    public ApiResponse<InspectionReportService.View> generate(
            @PathVariable UUID id,
            @Valid @RequestBody ReportRevisionRequest request) {
        return ApiResponse.ok(reports.generate(id, request.revision()));
    }

    @PostMapping("/api/inspection-reports/{id}/rewrite")
    public ApiResponse<ReportRewriteResponse> rewrite(
            @PathVariable UUID id,
            @Valid @RequestBody ReportRewriteRequest request) {
        return ApiResponse.ok(
                reports.rewriteProposal(id, request.revision(), request.instruction(), generation)
        );
    }

    @PostMapping("/api/inspection-reports/{id}/{action:submit-review|request-revision|approve|finalize|deliver|cancel-generation}")
    public ApiResponse<InspectionReportService.View> action(
            @PathVariable UUID id,
            @PathVariable String action,
            @Valid @RequestBody ReportRevisionRequest request) throws IOException {
        return ApiResponse.ok(reports.action(id, request.revision(), action));
    }

    @GetMapping("/api/inspection-reports/{id}/versions")
    public ApiResponse<List<Map<String, Object>>> history(@PathVariable UUID id) {
        return ApiResponse.ok(reports.history(id));
    }

    @GetMapping("/api/inspection-reports/{id}/videos/{mediaId}")
    public ResponseEntity<ApiResponse<Map<String, String>>> video(
            @PathVariable UUID id,
            @PathVariable String mediaId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(reports.video(id, mediaId)));
    }

    @GetMapping(value = "/api/inspection-reports/{id}/images/{mediaId}", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> image(
            @PathVariable UUID id,
            @PathVariable String mediaId) throws IOException {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(reports.image(id, mediaId));
    }

    @GetMapping(value = "/api/inspection-reports/{id}/{format:pdf|preview}", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(
            @PathVariable UUID id,
            @PathVariable String format) throws IOException {
        boolean isPreview = "preview".equals(format);
        String disposition = (isPreview ? "inline" : "attachment")
                + "; filename=inspection-" + id + ".pdf";

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .body(reports.artifact(id, isPreview));
    }
}
