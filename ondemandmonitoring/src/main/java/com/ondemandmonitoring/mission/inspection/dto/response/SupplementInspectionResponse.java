package com.ondemandmonitoring.mission.inspection.dto.response;

import com.ondemandmonitoring.mission.dto.response.ChecklistExecutionResponse;
import java.time.Instant;
import java.util.List;

/** Read-only accepted result, scoped to its source correction. */
public record SupplementInspectionResponse(
    String missionId, String missionCode, Instant completedAt,
    List<ChecklistExecutionResponse> executions) {}
