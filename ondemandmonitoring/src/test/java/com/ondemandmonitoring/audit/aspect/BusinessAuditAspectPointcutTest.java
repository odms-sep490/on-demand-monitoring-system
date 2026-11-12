package com.ondemandmonitoring.audit.aspect;

import static org.assertj.core.api.Assertions.assertThat;

import com.ondemandmonitoring.audit.dto.AuditLogFilter;
import com.ondemandmonitoring.audit.service.AdminAuditLogService;
import com.ondemandmonitoring.finance.service.impl.PaymentService;
import com.ondemandmonitoring.order.dto.request.OrderCreateRequest;
import com.ondemandmonitoring.order.service.impl.OrderService;
import java.lang.reflect.Method;
import java.util.Map;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.AspectJExpressionPointcut;

class BusinessAuditAspectPointcutTest {

    @Test
    void matchesBusinessServiceMutationsAndExcludesAuditInfrastructure() throws Exception {
        Method advice = BusinessAuditAspect.class.getMethod(
                "auditBusinessOperation", ProceedingJoinPoint.class);
        AspectJExpressionPointcut pointcut = new AspectJExpressionPointcut();
        pointcut.setExpression(advice.getAnnotation(Around.class).value());

        assertThat(matches(pointcut, OrderService.class, "createOrder",
                OrderCreateRequest.class)).isTrue();
        assertThat(matches(pointcut, PaymentService.class, "createDeposit",
                String.class, String.class)).isTrue();
        assertThat(matches(pointcut, PaymentService.class, "completeTestDeposit",
                String.class)).isTrue();
        assertThat(matches(pointcut, PaymentService.class, "processVnPayWebhook",
                Map.class)).isTrue();
        assertThat(matches(pointcut, AdminAuditLogService.class, "search",
                AuditLogFilter.class, Integer.class, Integer.class)).isFalse();
    }

    private static boolean matches(
            AspectJExpressionPointcut pointcut, Class<?> type, String methodName, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Method method = type.getDeclaredMethod(methodName, parameterTypes);
        return pointcut.matches(method, type);
    }
}
