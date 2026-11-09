package com.ondemandmonitoring.mission.dto.response;

import java.util.List;

public record CustomerMissionReport(CustomerMissionSummary mission, boolean reportAvailable,
        CustomerPublishedMissionResult result, List<CustomerChecklistReportItem> checklistItems) {}
