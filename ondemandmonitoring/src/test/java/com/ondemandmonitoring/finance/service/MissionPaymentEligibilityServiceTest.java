package com.ondemandmonitoring.finance.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.ondemandmonitoring.finance.domain.Invoice;
import com.ondemandmonitoring.finance.service.impl.MissionPaymentEligibilityService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MissionPaymentEligibilityServiceTest {
    private final MissionPaymentEligibilityService service = new MissionPaymentEligibilityService();

    @Test void depositIsSatisfiedAtOrAboveRequirement() {
        Invoice invoice = invoice("300", "300");
        assertThat(service.isDepositSatisfied(invoice)).isTrue();
        invoice.setPaidAmount(new BigDecimal("299"));
        assertThat(service.isDepositSatisfied(invoice)).isFalse();
    }
    private Invoice invoice(String deposit, String paid) {
        Invoice invoice = new Invoice();
        invoice.setDepositAmount(new BigDecimal(deposit)); invoice.setPaidAmount(new BigDecimal(paid));
        invoice.setTotalAmount(new BigDecimal("1000"));
        return invoice;
    }
}
