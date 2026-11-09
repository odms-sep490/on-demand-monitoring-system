package com.ondemandmonitoring.checklist.service;

import com.ondemandmonitoring.checklist.dto.request.ChecklistRequest;
import com.ondemandmonitoring.checklist.dto.response.ChecklistResponse;
import com.ondemandmonitoring.common.api.PageResponse;
import org.springframework.data.domain.Pageable;

public interface IChecklistService {
    ChecklistResponse create(ChecklistRequest request);
    ChecklistResponse getById(String id);
    PageResponse<ChecklistResponse> getAll(String search, Boolean active, Pageable pageable);
    ChecklistResponse update(String id, ChecklistRequest request);
    ChecklistResponse updateStatus(String id, boolean active);
}
