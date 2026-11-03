package com.ondemandmonitoring.mission.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.mission.domain.Mission;

/** Structural metadata gate; runtime callers must additionally verify Assignment/Execution integrity. */
public final class MissionRuntimeGate {
    private MissionRuntimeGate() {}
    public static boolean isStructurallyFinalized(Mission mission) {
        return mission.getOrder() != null
                && mission.getOrder().getExecutionPlan().getStatus()
                    == com.ondemandmonitoring.order.enums.ExecutionPlanStatus.FINALIZED
                && mission.getOrder().getExecutionPlan().getRevision() > 0;
    }
    public static void requireFinalizedStructure(Mission mission) {
        if (!isStructurallyFinalized(mission)) throw new ApiException(
                com.ondemandmonitoring.common.exception.ErrorCode.CHECKLIST_ASSIGNMENT_NOT_FINALIZED);
    }

    public static void requireOperationalCommandsAllowed(Mission mission) {
        if (mission.getStatus() == com.ondemandmonitoring.mission.enums.MissionStatus.CANCELLED)
            throw new ApiException(com.ondemandmonitoring.common.exception.ErrorCode.MISSION_CANCELLATION_NOT_ALLOWED);
    }

    public static void requireSessionAllowed(Mission mission) {
        if (mission.getStatus() == com.ondemandmonitoring.mission.enums.MissionStatus.CANCELLED
                || mission.getStatus() == com.ondemandmonitoring.mission.enums.MissionStatus.FAILED
                || mission.getActualEndAt() != null
                || mission.getStatus() == com.ondemandmonitoring.mission.enums.MissionStatus.AWAITING_REVIEW
                || mission.getStatus() == com.ondemandmonitoring.mission.enums.MissionStatus.PENDING_REVIEW
                || mission.getStatus() == com.ondemandmonitoring.mission.enums.MissionStatus.COMPLETED) {
            throw new ApiException(com.ondemandmonitoring.common.exception.ErrorCode.MISSION_STATUS_INVALID,
                    "Mission is already closed and cannot open a device session");
        }
    }
}
