package com.ondemandmonitoring.mission.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.enums.*;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.mission.repository.MissionResultRepository;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.enums.*;

/**
 * Shared existing cancellation preconditions for locked commands and
 * non-mutating capability reads.
 */
final class MissionCancellationGuard {
    private MissionCancellationGuard() {
    }

    static void requireAllowed(String orderId, Order order, Mission mission, long expectedRevision,
            MissionResultRepository results, MissionRepository missions) {
        if (!orderId.equals(mission.getOrder().getId()))
            throw new ApiException(ErrorCode.MISSION_CANCELLATION_NOT_ALLOWED);
        if (mission.getStatus() == MissionStatus.CANCELLED || mission.getCancellationAt() != null)
            throw new ApiException(ErrorCode.MISSION_ALREADY_CANCELLED);
        if (order.getOrderStatus() != OrderStatus.APPROVED && order.getOrderStatus() != OrderStatus.IN_PROGRESS)
            throw new ApiException(ErrorCode.MISSION_CANCELLATION_NOT_ALLOWED);
        if (order.getExecutionPlanStatus() != ExecutionPlanStatus.FINALIZED)
            throw new ApiException(ErrorCode.EXECUTION_PLAN_NOT_FINALIZED);
        if (order.getExecutionPlanRevision() != expectedRevision || order.getExecutionPlanRevision() == Long.MAX_VALUE)
            throw new ApiException(ErrorCode.EXECUTION_PLAN_REVISION_CONFLICT);
        if (results.existsByMissionIdAndApprovalStatus(mission.getId(), MissionResultApprovalStatus.APPROVED))
            throw new ApiException(ErrorCode.MISSION_RESULT_ALREADY_APPROVED);
    }
}
