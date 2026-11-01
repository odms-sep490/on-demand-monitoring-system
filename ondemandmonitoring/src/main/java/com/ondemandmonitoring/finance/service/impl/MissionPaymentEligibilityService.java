package com.ondemandmonitoring.finance.service.impl;

import com.ondemandmonitoring.finance.domain.Invoice;
import com.ondemandmonitoring.finance.service.IMissionPaymentEligibilityService;
import org.springframework.stereotype.Service;

@Service
public class MissionPaymentEligibilityService implements IMissionPaymentEligibilityService {
    public boolean isDepositSatisfied(Invoice invoice) {
        return invoice != null && invoice.getPaidAmount() != null && invoice.getDepositAmount() != null
                && invoice.getPaidAmount().compareTo(invoice.getDepositAmount()) >= 0;
    }
}
