package com.ondemandmonitoring.mission.dto.response;

import com.ondemandmonitoring.order.enums.OrderChecklistSourceType;
import com.ondemandmonitoring.order.enums.RequiredEvidenceType;

public record ChecklistAssignmentResponse(String assignmentId, String orderChecklistItemId,
        String content, OrderChecklistSourceType sourceType, int displayOrder, boolean active, Long version,
        int minimumEvidenceCount, RequiredEvidenceType requiredEvidenceType) {}
