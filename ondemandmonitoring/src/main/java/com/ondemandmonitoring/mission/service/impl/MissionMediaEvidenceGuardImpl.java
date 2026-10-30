package com.ondemandmonitoring.mission.service.impl;
import com.ondemandmonitoring.mission.service.IMissionMediaEvidenceGuard;
import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.media.repository.MediaAssetRepository;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.enums.MissionResultApprovalStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

/** Evidence-affecting lock order: Mission → MediaAsset → attempt/execution/evidence. */
@Component
@RequiredArgsConstructor
public class MissionMediaEvidenceGuardImpl implements IMissionMediaEvidenceGuard {
    private final MediaAssetRepository media;
    private final MissionRepository missions;
    private final MissionResultRepository results;
    private final MissionChecklistEvidenceRepository evidence;
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(String mediaId) {
        String missionId = media.findMissionId(mediaId).orElseThrow(() -> new ApiException(ErrorCode.MEDIA_NOT_FOUND));
        lockMission(missionId);
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockMission(String missionId) {
        missions.findByIdForUpdate(missionId).orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
    }
    public void requireMutable(String missionId, String mediaId) {
        if (evidence.existsByMediaAsset_IdAndDetachedAtIsNull(mediaId)
                && results.findByMissionId(missionId).map(r -> r.getApprovalStatus() == MissionResultApprovalStatus.APPROVED).orElse(false))
            throw new ApiException(ErrorCode.CHECKLIST_EXECUTION_LOCKED, "Approved evidence package is immutable");
    }
    public void requireDeletable(String mediaId) {
        if (evidence.existsByMediaAsset_Id(mediaId)) throw new ApiException(ErrorCode.MEDIA_EVIDENCE_PROTECTED);
    }
    @org.springframework.transaction.annotation.Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    public boolean hasEvidenceOrResultReferences(String mediaId) {
        return evidence.existsByMediaAsset_IdAndDetachedAtIsNull(mediaId)
                || results.existsByMediaFiles_Id(mediaId);
    }
}
