package com.ondemandmonitoring.audit.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ondemandmonitoring.auth.service.impl.AdminAccountServiceImpl;
import com.ondemandmonitoring.checklist.service.impl.ChecklistServiceImpl;
import com.ondemandmonitoring.finance.service.impl.PaymentService;
import com.ondemandmonitoring.media.service.impl.MediaValidationServiceImpl;
import com.ondemandmonitoring.mission.service.impl.MissionChecklistAssignmentServiceImpl;
import com.ondemandmonitoring.mission.service.impl.MissionResultService;
import com.ondemandmonitoring.order.service.impl.OrderService;
import com.ondemandmonitoring.pricing.domain.PricingPolicy;
import com.ondemandmonitoring.pricing.service.ServiceDefaultPriceService;
import com.ondemandmonitoring.pricing.service.ServicePriceResolver;
import com.ondemandmonitoring.service.domain.Service;
import com.ondemandmonitoring.user.service.impl.UserManagementServiceImpl;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class AuditInvocationSnapshotTest {

    private final AuditInvocationSnapshot snapshots = new AuditInvocationSnapshot(new ObjectMapper());

    @Test
    void includesHighImpactBusinessChanges() {
        assertThat(isAudited(PaymentService.class, "processVnPayWebhook")).isTrue();
        assertThat(isAudited(PaymentService.class, "createDeposit")).isTrue();
        assertThat(isAudited(OrderService.class, "createOrder")).isTrue();
        assertThat(isAudited(MissionResultService.class, "approve")).isTrue();
        assertThat(isAudited(MissionChecklistAssignmentServiceImpl.class, "replace")).isTrue();
        assertThat(isAudited(AdminAccountServiceImpl.class, "create")).isTrue();
        assertThat(isAudited(ChecklistServiceImpl.class, "create")).isTrue();
    }

    @Test
    void ignoresRoutineStatusChangesAndMediaProcessing() {
        assertThat(isAudited(UserManagementServiceImpl.class, "updateStatus")).isFalse();
        assertThat(isAudited(MediaValidationServiceImpl.class, "processObjectCreated")).isFalse();
    }

    @Test
    void doesNotAuditReadOnlyPricingResolverAsAMutation() {
        assertThat(isAudited(ServicePriceResolver.class, "resolve", Service.class, PricingPolicy.class)).isFalse();
    }

    @Test
    void ignoresTechnicalDefaultPriceRefreshes() {
        assertThat(isAudited(ServiceDefaultPriceService.class, "refresh")).isFalse();
    }

    private boolean isAudited(Class<?> serviceType, String methodName) {
        Method method = Arrays.stream(serviceType.getMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No method " + serviceType.getSimpleName() + "." + methodName));
        return snapshots.isCriticalBusinessMutation(serviceType, method);
    }

    private boolean isAudited(Class<?> serviceType, String methodName, Class<?>... parameterTypes) {
        try {
            return snapshots.isCriticalBusinessMutation(serviceType, serviceType.getMethod(methodName, parameterTypes));
        } catch (NoSuchMethodException failure) {
            throw new AssertionError("No method " + serviceType.getSimpleName() + "." + methodName, failure);
        }
    }
}
