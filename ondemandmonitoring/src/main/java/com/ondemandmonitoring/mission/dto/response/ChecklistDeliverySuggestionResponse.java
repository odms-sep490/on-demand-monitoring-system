package com.ondemandmonitoring.mission.dto.response;

import java.util.List;

public record ChecklistDeliverySuggestionResponse(
        long executionVersion,
        String policyVersion,
        int requiredImageCount,
        int suggestedImageCount,
        List<String> suggestedImageEvidenceIds,
        List<String> warnings,
        List<Candidate> candidates,
        boolean contentAssessmentAvailable) {

    public ChecklistDeliverySuggestionResponse withContentAssessmentAvailable(boolean available) {
        if (contentAssessmentAvailable == available) return this;
        return new ChecklistDeliverySuggestionResponse(executionVersion, policyVersion, requiredImageCount,
                suggestedImageCount, suggestedImageEvidenceIds, warnings, candidates, available);
    }

    public record Candidate(String evidenceId, Recommendation recommendation, List<String> reasonCodes,
            String qualityCheckId) {}

    public enum Recommendation {
        SUGGESTED, RESERVE, REVIEW
    }
}
