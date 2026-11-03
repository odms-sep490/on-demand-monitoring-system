package com.ondemandmonitoring.stafflicense.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.stafflicense.dto.request.LicenseVerificationRequest;
import com.ondemandmonitoring.stafflicense.dto.request.StaffLicenseRequest;
import com.ondemandmonitoring.stafflicense.dto.response.StaffLicenseDocumentResponse;
import com.ondemandmonitoring.stafflicense.dto.response.StaffLicenseResponse;
import com.ondemandmonitoring.stafflicense.service.IStaffLicenseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Staff Licenses", description = "Staff UAV pilot and technical license records")
@RestController
@RequestMapping("/api/staff-licenses")
@RequiredArgsConstructor
public class StaffLicenseController {
    private final IStaffLicenseService service;

    @Operation(summary = "List licenses for a staff member")
    @GetMapping("/users/{userId}")
    public ResponseEntity<ApiResponse<List<StaffLicenseResponse>>> getForUser(@PathVariable String userId) {
        return ResponseEntity.ok(ApiResponse.ok(service.getForUser(userId)));
    }

    @Operation(summary = "Get a staff license")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<StaffLicenseResponse>> get(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.ok(service.get(id)));
    }

    @Operation(summary = "Create a staff license record")
    @PostMapping
    public ResponseEntity<ApiResponse<StaffLicenseResponse>> create(@Valid @RequestBody StaffLicenseRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created("Staff license created", service.create(request)));
    }

    @Operation(summary = "Update a staff license record")
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<StaffLicenseResponse>> update(
            @PathVariable String id, @Valid @RequestBody StaffLicenseRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Staff license updated", service.update(id, request)));
    }

    @Operation(summary = "Verify or reject a staff license")
    @PostMapping("/{id}/verification")
    public ResponseEntity<ApiResponse<StaffLicenseResponse>> verify(
            @PathVariable String id, @Valid @RequestBody LicenseVerificationRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Staff license verification updated", service.verify(id, request)));
    }

    @Operation(summary = "Revoke a staff license")
    @PostMapping("/{id}/revoke")
    public ResponseEntity<ApiResponse<StaffLicenseResponse>> revoke(
            @PathVariable String id, @RequestParam(required = false) String notes) {
        return ResponseEntity.ok(ApiResponse.ok("Staff license revoked", service.revoke(id, notes)));
    }

    @Operation(summary = "Upload a license document")
    @PostMapping(value = "/{id}/document", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<StaffLicenseResponse>> upload(
            @PathVariable String id, @RequestPart("file") MultipartFile file) {
        return ResponseEntity.ok(ApiResponse.ok("License document uploaded", service.uploadDocument(id, file)));
    }

    @Operation(summary = "Get a short-lived URL for viewing a license document")
    @GetMapping("/{id}/document")
    public ResponseEntity<ApiResponse<StaffLicenseDocumentResponse>> document(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.ok(service.getDocument(id)));
    }
}
