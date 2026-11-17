package com.ondemandmonitoring.missiontemplate.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.missiontemplate.dto.MissionTemplateRequest;
import com.ondemandmonitoring.missiontemplate.dto.MissionTemplateResponse;
import com.ondemandmonitoring.missiontemplate.service.IMissionTemplateService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/services/{serviceId}/mission-templates")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class MissionTemplateController {
    private final IMissionTemplateService service;

    @GetMapping
    public ApiResponse<List<MissionTemplateResponse>> getAll(@PathVariable String serviceId) {
        return ApiResponse.ok(service.getAll(serviceId));
    }

    @GetMapping("/{templateId}")
    public ApiResponse<MissionTemplateResponse> get(
            @PathVariable String serviceId, @PathVariable String templateId) {
        return ApiResponse.ok(service.get(serviceId, templateId));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<MissionTemplateResponse>> create(
            @PathVariable String serviceId, @Valid @RequestBody MissionTemplateRequest request) {
        return ResponseEntity.status(201)
                .body(ApiResponse.created("Mission template created", service.create(serviceId, request)));
    }

    @PutMapping("/{templateId}")
    public ApiResponse<MissionTemplateResponse> update(
            @PathVariable String serviceId, @PathVariable String templateId,
            @Valid @RequestBody MissionTemplateRequest request) {
        return ApiResponse.ok(service.update(serviceId, templateId, request));
    }

    @DeleteMapping("/{templateId}")
    public ResponseEntity<Void> delete(
            @PathVariable String serviceId, @PathVariable String templateId) {
        service.delete(serviceId, templateId);
        return ResponseEntity.noContent().build();
    }
}
