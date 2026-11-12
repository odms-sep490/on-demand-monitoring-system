package com.ondemandmonitoring.audit.aspect;

import com.ondemandmonitoring.audit.domain.AdminAuditLog;
import com.ondemandmonitoring.audit.service.AdminAuditLogService;
import com.ondemandmonitoring.audit.service.AuditInvocationSnapshot;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Audits successful mutating service operations without requiring audit calls in each service method.
 */
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class BusinessAuditAspect {
    private static final ThreadLocal<AuditContext> CURRENT_CONTEXT = new ThreadLocal<>();

    private final AdminAuditLogService auditLog;
    private final AuditInvocationSnapshot snapshots;
    private final TransactionTemplate transactions;

    public BusinessAuditAspect(AdminAuditLogService auditLog, AuditInvocationSnapshot snapshots,
            PlatformTransactionManager transactionManager) {
        this.auditLog = auditLog;
        this.snapshots = snapshots;
        this.transactions = new TransactionTemplate(transactionManager);
        // Run the audited mutation and its audit writes in a fresh writable transaction.
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Around("execution(public * com.ondemandmonitoring..service..*(..))"
            + " && !within(com.ondemandmonitoring.audit..*)"
            + " && !within(com.ondemandmonitoring..repository..*)")
    public Object auditBusinessOperation(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = AopUtils.getMostSpecificMethod(signature.getMethod(), joinPoint.getTarget().getClass());
        Class<?> serviceType = AopUtils.getTargetClass(joinPoint.getTarget());

        if (!snapshots.isCriticalBusinessMutation(serviceType, method)
                || isHighVolumeTelemetry(serviceType, method)
                || isScheduledEntryPoint(method)) {
            return joinPoint.proceed();
        }

        AuditContext existing = CURRENT_CONTEXT.get();
        if (existing != null) {
            return captureNestedOperation(joinPoint, serviceType, method, existing);
        }

        InvocationOutcome outcome = transactions.execute(status -> {
            AuditContext context = new AuditContext();
            CURRENT_CONTEXT.set(context);
            try {
                Map<String, Object> request = snapshots.request(serviceType, method, joinPoint.getArgs());
                String entityId = snapshots.entityId(serviceType, method, joinPoint.getArgs(), null);
                AdminAuditLog entry = auditLog.begin(
                        snapshots.action(method),
                        snapshots.entityType(serviceType),
                        entityId,
                        request);

                Object result = joinPoint.proceed();
                auditLog.complete(
                        entry,
                        snapshots.entityId(serviceType, method, joinPoint.getArgs(), result),
                        snapshots.result(serviceType, method, result, context.operations()));
                return InvocationOutcome.success(result);
            } catch (Throwable failure) {
                status.setRollbackOnly();
                return InvocationOutcome.failure(failure);
            } finally {
                CURRENT_CONTEXT.remove();
            }
        });

        if (outcome != null && outcome.failure() != null) throw outcome.failure();
        return outcome == null ? null : outcome.result();
    }

    private Object captureNestedOperation(ProceedingJoinPoint joinPoint, Class<?> serviceType,
            Method method, AuditContext context) throws Throwable {
        Map<String, Object> operation = snapshots.nestedRequest(serviceType, method, joinPoint.getArgs());
        try {
            Object result = joinPoint.proceed();
            context.add(snapshots.nestedSuccess(operation, result));
            return result;
        } catch (Throwable failure) {
            context.add(snapshots.nestedFailure(operation, failure));
            throw failure;
        }
    }

    private static boolean isHighVolumeTelemetry(Class<?> serviceType, Method method) {
        return serviceType.getSimpleName().contains("Telemetry")
                || "record".equals(method.getName());
    }

    private static boolean isScheduledEntryPoint(Method method) {
        return AnnotatedElementUtils.findMergedAnnotation(method, Scheduled.class) != null;
    }

    private record InvocationOutcome(Object result, Throwable failure) {
        static InvocationOutcome success(Object result) {
            return new InvocationOutcome(result, null);
        }

        static InvocationOutcome failure(Throwable failure) {
            return new InvocationOutcome(null, failure);
        }
    }

    private static final class AuditContext {
        private final List<Map<String, Object>> operations = new ArrayList<>();

        void add(Map<String, Object> operation) {
            if (operations.size() < 100) operations.add(operation);
        }

        List<Map<String, Object>> operations() {
            return List.copyOf(operations);
        }
    }
}
