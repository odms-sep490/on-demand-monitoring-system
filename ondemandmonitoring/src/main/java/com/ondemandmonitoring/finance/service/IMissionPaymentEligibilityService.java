package com.ondemandmonitoring.finance.service;

import com.ondemandmonitoring.finance.domain.Invoice;

/** Central business policy for deposit satisfaction. */
public interface IMissionPaymentEligibilityService {
    boolean isDepositSatisfied(Invoice invoice);

    /** The single business decision used by both the invoice API and payment creation. */
    boolean isFinalPaymentEligible(Invoice invoice);

    void requireFinalPaymentEligible(Invoice invoice);

    /** True only after an authoritative successful final-payment transaction fully settled the invoice. */
    boolean isFinalPaymentConfirmed(Invoice invoice);

    /** Resource mutations must not race an in-flight final payment. */
    boolean hasActiveFinalPayment(String orderId);

    /** Validates current crew, equipment, schedule, and persisted reservations. */
    boolean isResourceAssignmentReady(com.ondemandmonitoring.mission.domain.Mission mission);
}
