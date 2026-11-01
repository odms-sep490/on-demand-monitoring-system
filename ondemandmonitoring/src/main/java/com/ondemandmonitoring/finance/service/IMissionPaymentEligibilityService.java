package com.ondemandmonitoring.finance.service;

import com.ondemandmonitoring.finance.domain.Invoice;

/** Central business policy for deposit satisfaction. */
public interface IMissionPaymentEligibilityService {
    boolean isDepositSatisfied(Invoice invoice);
}
