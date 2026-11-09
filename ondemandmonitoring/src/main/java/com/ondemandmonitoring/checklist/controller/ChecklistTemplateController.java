package com.ondemandmonitoring.checklist.controller;

import com.ondemandmonitoring.checklist.dto.response.ServiceChecklistResponse;
import com.ondemandmonitoring.checklist.dto.response.ChecklistResponse;
import com.ondemandmonitoring.checklist.service.IChecklistService;
import com.ondemandmonitoring.checklist.service.IServiceChecklistService;
import com.ondemandmonitoring.common.api.ApiResponse;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class ChecklistTemplateController {
    private final IServiceChecklistService service;
    private final IChecklistService checklists;

    @GetMapping("/api/services/{serviceId}/checklists")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<ServiceChecklistResponse>> template(@PathVariable String serviceId) {
        return ApiResponse.ok(service.getByService(serviceId, true));
    }

    @GetMapping("/api/checklists/catalog")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<ChecklistResponse>> catalog() {
        return ApiResponse.ok(checklists.getAll(null, true,
                PageRequest.of(0, 100, Sort.by(Sort.Direction.ASC, "content"))).getItems());
    }

    @GetMapping("/api/admin/checklists/{checklistId}/services")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<List<ServiceChecklistResponse>> services(@PathVariable String checklistId) {
        return ApiResponse.ok(service.getByChecklist(checklistId));
    }
}
