package com.ondemandmonitoring.mission.dto.response;

/** Effective permissions for the authenticated account, not global role labels. */
public record MissionPermissionsResponse(
        boolean canRespond,
        boolean canStartMission,
        boolean canCancelMission,
        boolean canControlFlight,
        boolean canOperatePayload,
        boolean canInspectDevice,
        boolean canMaintainDevice,
        boolean canUploadMedia,
        boolean canCompleteMission,
        boolean canSubmitMissionResult,
        boolean canExecuteMonitoringChecklist,
        boolean canAttachChecklistEvidence,
        boolean canDetachChecklistEvidence,
        boolean canAssessMonitoringChecklist,
        boolean canConfirmMissionFailure) {
}
