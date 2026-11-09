package com.ondemandmonitoring.checklist.service;

import com.ondemandmonitoring.checklist.dto.request.ChecklistAssignmentRequest;
import com.ondemandmonitoring.checklist.dto.response.ServiceChecklistResponse;
import java.util.List;

public interface IServiceChecklistService {
    ServiceChecklistResponse updatePricing(String serviceId, String checklistId,
            com.ondemandmonitoring.checklist.dto.request.ServiceChecklistPricingRequest request);

    ServiceChecklistResponse assign(String serviceId, ChecklistAssignmentRequest request);
    ServiceChecklistResponse updateOrder(String serviceId, String checklistId, int displayOrder);
    void unassign(String serviceId, String checklistId);
    /** Gives the service its own copy of a shared checklist; the other services keep the original. */
    ServiceChecklistResponse fork(String serviceId, String checklistId);
    List<ServiceChecklistResponse> reorder(String serviceId, com.ondemandmonitoring.checklist.dto.request.ChecklistReorderRequest request);
    List<ServiceChecklistResponse> getByService(String serviceId, boolean activeOnly);
    List<ServiceChecklistResponse> getByChecklist(String checklistId);
}
