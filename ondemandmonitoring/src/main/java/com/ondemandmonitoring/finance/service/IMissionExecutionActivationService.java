package com.ondemandmonitoring.finance.service;

public interface IMissionExecutionActivationService {
    /** Activates assigned Missions once the Deposit and milestone allocations are ready. */
    void activateIfEligible(String orderId);
}
