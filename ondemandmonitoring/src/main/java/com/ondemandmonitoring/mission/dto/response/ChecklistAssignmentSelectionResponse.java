package com.ondemandmonitoring.mission.dto.response;

import java.util.List;

public record ChecklistAssignmentSelectionResponse(String missionId, long revision,
        List<ChecklistAssignmentResponse> assignments) {}
