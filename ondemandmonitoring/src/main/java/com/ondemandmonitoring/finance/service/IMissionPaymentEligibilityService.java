package com.ondemandmonitoring.finance.service;

import com.ondemandmonitoring.finance.domain.Invoice;
import com.ondemandmonitoring.mission.domain.Mission;

/** Central business policy for deposit satisfaction. */
public interface IMissionPaymentEligibilityService {
    boolean isDepositSatisfied(Invoice invoice);

    boolean isMissionExecutionEligible(Invoice invoice);

    /** Validates current crew, equipment, schedule, and persisted reservations. */
    boolean isResourceAssignmentReady(Mission mission);
}
