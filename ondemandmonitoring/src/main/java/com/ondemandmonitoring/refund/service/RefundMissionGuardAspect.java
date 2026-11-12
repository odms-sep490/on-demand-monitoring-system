package com.ondemandmonitoring.refund.service;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.order.repository.OrderRepository;
import java.util.Set;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps the Refund hold outside the teammate-owned MissionService implementation.
 * The Order row is locked before checking the Refund, matching the lock order used
 * when a Manager creates the Refund request.
 */
@Aspect
@Component
public class RefundMissionGuardAspect {
    private static final Set<String> ORDER_ID_METHODS = Set.of(
            "createMission", "createAdditionalMission", "transitionToPendingConfirmation");
    private static final Set<String> MISSION_ID_METHODS = Set.of(
            "confirmMissionPlanning", "rescheduleMission", "acceptCurrentStaffMission", "acceptMission",
            "connectGcs", "runPreDeviceCheck", "handoverCurrentStaffControl", "handoverControl",
            "startMission", "markReturning", "startPostDeviceChecking", "completeMission",
            "failMission", "requestMissionFailure", "updatePostFlightStatus", "recordPostFlightInspection");

    private final OrderRepository orders;
    private final MissionRepository missions;
    private final IRefundHoldService refundHold;
    private final TransactionTemplate transactionTemplate;

    public RefundMissionGuardAspect(
            OrderRepository orders,
            MissionRepository missions,
            IRefundHoldService refundHold,
            PlatformTransactionManager transactionManager) {
        this.orders = orders;
        this.missions = missions;
        this.refundHold = refundHold;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Around("execution(* com.ondemandmonitoring.mission.service.IMissionService+.createMission(..))"
            + " || execution(* com.ondemandmonitoring.mission.service.IMissionService+.createAdditionalMission(..))"
            + " || execution(* com.ondemandmonitoring.mission.service.IMissionService+.transitionToPendingConfirmation(..))"
            + " || execution(* com.ondemandmonitoring.mission.service.IMissionService+.confirmMissionPlanning(..))"
            + " || execution(* com.ondemandmonitoring.mission.service.IMissionService+.rescheduleMission(..))"
            + " || execution(* com.ondemandmonitoring.mission.service.IMissionService+.acceptCurrentStaffMission(..))"
            + " || execution(* com.ondemandmonitoring.mission.service.IMissionService+.acceptMission(..))"
            + " || execution(* com.ondemandmonitoring.mission.service.IMissionService+.connectGcs(..))"
            + " || execution(* com.ondemandmonitoring.mission.service.IMissionService+.runPreDeviceCheck(..))"
            + " || execution(* com.ondemandmonitoring.mission.service.IMissionService+.handoverCurrentStaffControl(..))"
            + " || execution(* com.ondemandmonitoring.mission.service.IMissionService+.handoverControl(..))"
            + " || execution(* com.ondemandmonitoring.mission.service.IMissionService+.startMission(..))"
            + " || execution(* com.ondemandmonitoring.mission.service.IMissionService+.markReturning(..))"
            + " || execution(* com.ondemandmonitoring.mission.service.IMissionService+.startPostDeviceChecking(..))"
            + " || execution(* com.ondemandmonitoring.mission.service.IMissionService+.completeMission(..))"
            + " || execution(* com.ondemandmonitoring.mission.service.IMissionService+.failMission(..))"
            + " || execution(* com.ondemandmonitoring.mission.service.IMissionService+.requestMissionFailure(..))"
            + " || execution(* com.ondemandmonitoring.mission.service.IMissionService+.updatePostFlightStatus(..))"
            + " || execution(* com.ondemandmonitoring.mission.service.IMissionService+.recordPostFlightInspection(..))")
    public Object guardMissionWorkflow(ProceedingJoinPoint invocation) throws Throwable {
        String methodName = invocation.getSignature().getName();
        Object[] arguments = invocation.getArgs();
        return transactionTemplate.execute(status -> {
            String orderId = resolveOrderId(methodName, arguments);
            lockAndCheck(orderId);
            try {
                return invocation.proceed();
            } catch (RuntimeException | Error exception) {
                throw exception;
            } catch (Throwable exception) {
                throw new IllegalStateException("Mission workflow failed", exception);
            }
        });
    }

    private String resolveOrderId(String methodName, Object[] arguments) {
        if (arguments.length == 0 || !(arguments[0] instanceof String id) || id.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Mission workflow identifier is missing");
        }
        if (ORDER_ID_METHODS.contains(methodName)) return id;
        if (!MISSION_ID_METHODS.contains(methodName)) return null;
        Mission mission = missions.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
        return mission.getOrder().getId();
    }

    private void lockAndCheck(String orderId) {
        orders.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found"));
        refundHold.requireOrderNotOnRefundHold(orderId);
    }
}
