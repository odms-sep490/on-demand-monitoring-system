package com.ondemandmonitoring.pricing.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class AdminPricingPolicyControllerTest {

    @Test
    void allPricingPolicyOperationsRequireTheAdminRole() {
        PreAuthorize authorization = AdminPricingPolicyController.class.getAnnotation(PreAuthorize.class);

        assertThat(authorization).isNotNull();
        assertThat(authorization.value()).isEqualTo("hasRole('ADMIN')");
    }
}
