package com.ondemandmonitoring.mission.dto.response;

import com.ondemandmonitoring.ai.client.ChecklistImageVisionClient.Verdict;
import java.util.List;

public record ChecklistImageContentAssessmentResponse(
        long executionVersion, String evidenceId, Verdict verdict, List<Criterion> criteria) {
    public record Criterion(String code, String label, Verdict verdict, String observation) {}
}
