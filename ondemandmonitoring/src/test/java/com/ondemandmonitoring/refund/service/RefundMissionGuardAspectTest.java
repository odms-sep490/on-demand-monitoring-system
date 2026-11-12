package com.ondemandmonitoring.refund.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.repository.OrderRepository;
import java.util.Optional;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

@ExtendWith(MockitoExtension.class)
class RefundMissionGuardAspectTest {
    @Mock private OrderRepository orders;
    @Mock private MissionRepository missions;
    @Mock private IRefundHoldService refundHold;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private TransactionStatus transactionStatus;
    @Mock private ProceedingJoinPoint invocation;
    @Mock private Signature signature;

    private RefundMissionGuardAspect aspect;

    @BeforeEach
    void setUp() {
        aspect = new RefundMissionGuardAspect(orders, missions, refundHold, transactionManager);
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        when(invocation.getSignature()).thenReturn(signature);
    }

    @Test
    void locksOrderAndBlocksMissionCreationWhenRefundHoldExists() throws Throwable {
        Order order = new Order();
        order.setId("order-1");
        when(signature.getName()).thenReturn("createMission");
        when(invocation.getArgs()).thenReturn(new Object[] {"order-1", new Object()});
        when(orders.findByIdForUpdate("order-1")).thenReturn(Optional.of(order));
        org.mockito.Mockito.doThrow(new ApiException(
                com.ondemandmonitoring.common.exception.ErrorCode.INVALID_REQUEST, "Refund hold"))
                .when(refundHold).requireOrderNotOnRefundHold("order-1");

        assertThrows(ApiException.class, () -> aspect.guardMissionWorkflow(invocation));

        verify(orders).findByIdForUpdate("order-1");
        verify(invocation, never()).proceed();
        verify(transactionManager).rollback(transactionStatus);
    }

    @Test
    void missionTransitionUsesMissionOrderAndAllowsProceedWhenNoHoldExists() throws Throwable {
        Order order = new Order();
        order.setId("order-1");
        com.ondemandmonitoring.mission.domain.Mission mission = new com.ondemandmonitoring.mission.domain.Mission();
        mission.setId("mission-1");
        mission.setOrder(order);
        when(signature.getName()).thenReturn("startMission");
        when(invocation.getArgs()).thenReturn(new Object[] {"mission-1"});
        when(missions.findById("mission-1")).thenReturn(Optional.of(mission));
        when(orders.findByIdForUpdate("order-1")).thenReturn(Optional.of(order));
        when(invocation.proceed()).thenReturn("started");

        Object result = aspect.guardMissionWorkflow(invocation);

        assertEquals("started", result);
        verify(refundHold).requireOrderNotOnRefundHold("order-1");
        verify(transactionManager).commit(transactionStatus);
    }
}
