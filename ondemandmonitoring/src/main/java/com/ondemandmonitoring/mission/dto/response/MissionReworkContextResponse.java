package com.ondemandmonitoring.mission.dto.response;

import com.ondemandmonitoring.delivery.enums.ResultRevisionReasonCode;
import java.time.Instant;
import java.util.List;

public record MissionReworkContextResponse(String missionId, String missionCode, String caseId,
        List<Checklist> checklists) {
    public record Checklist(String caseItemId, String orderChecklistItemId, String title,
            String managerInstructions, Guidance guidance, List<Feedback> customerFeedback) {}
    public record Guidance(String description, List<String> executionSteps,
            List<String> inputRequirements, List<String> expectedOutputs) {}
    public record Feedback(int roundNumber, ResultRevisionReasonCode reasonCode, String customerNote,
            Instant submittedAt, List<Evidence> evidence) {}
    public record Evidence(String roundEvidenceId, String mediaAssetId, String fileName,
            String mediaType, String url, Instant urlExpiresAt, String unavailableReason) {}
}
