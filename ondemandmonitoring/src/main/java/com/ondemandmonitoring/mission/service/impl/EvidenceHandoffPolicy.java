package com.ondemandmonitoring.mission.service.impl;

import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.mission.repository.MissionEvidenceSubmissionRepository;
import com.ondemandmonitoring.mission.inspection.enums.InspectionCorrectionStatus;
import com.ondemandmonitoring.mission.inspection.repository.InspectionCorrectionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EvidenceHandoffPolicy {
    private final MissionEvidenceSubmissionRepository submissions;
    private final InspectionCorrectionRepository corrections;

    public boolean editable(String missionId) {
        // Initial inspection does not require a separate crew handoff command.
        // Only a correction resubmission locks its evidence for reinspection.
        var history = corrections.findByMission_IdOrderByRequestedAtDescIdDesc(missionId);
        return history.isEmpty() || history.stream()
            .anyMatch(c -> c.getStatus() == InspectionCorrectionStatus.OPEN);
    }

    public boolean submitted(String missionId) {
        return submissions.findFirstByMission_IdOrderByRevisionDesc(missionId).isPresent();
    }

    public boolean reviewable(String missionId) {
        return corrections.findByMission_IdOrderByRequestedAtDescIdDesc(missionId).stream()
                .noneMatch(c -> c.getStatus() == InspectionCorrectionStatus.OPEN);
    }

    public void requireReviewable(String missionId) {
        if (!reviewable(missionId)) throw new ApiException(ErrorCode.CHECKLIST_NOT_READY,
            "Complete the requested corrections before inspection");
    }
}
