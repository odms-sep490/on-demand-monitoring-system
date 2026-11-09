package com.ondemandmonitoring.mission.dto.response;

import com.ondemandmonitoring.mission.enums.ChecklistAssessmentStatus;
import com.ondemandmonitoring.mission.enums.ChecklistExecutionStatus;
import com.ondemandmonitoring.order.enums.OrderChecklistSourceType;

public record CustomerChecklistReportItem(String executionId, String orderChecklistItemId,
        String content, OrderChecklistSourceType sourceType, int displayOrder,
        ChecklistExecutionStatus status, ChecklistAssessmentStatus assessment,
        String observation, String unableToVerifyReason) {}
