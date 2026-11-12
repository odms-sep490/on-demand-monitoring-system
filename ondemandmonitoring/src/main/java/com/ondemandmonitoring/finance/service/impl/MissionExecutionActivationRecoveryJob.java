package com.ondemandmonitoring.finance.service.impl;

import com.ondemandmonitoring.finance.repository.InvoiceRepository;
import com.ondemandmonitoring.finance.service.IMissionExecutionActivationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Recovers mission activation when payment settlement committed but activation could not complete. */
@Slf4j
@Component
@RequiredArgsConstructor
public class MissionExecutionActivationRecoveryJob {
    private static final int BATCH_SIZE = 500;

    private final InvoiceRepository invoices;
    private final IMissionExecutionActivationService activation;

    @Scheduled(fixedDelayString = "${omss.payment.mission-activation-recovery-delay-ms:60000}")
    public void recoverPendingMissionActivations() {
        recoverMilestoneActivations();
    }

    private void recoverMilestoneActivations() {
        String cursor = "";
        while (true) {
            var payableMilestoneOrders = invoices.findOrderIdsAwaitingMissionActivationAfter(
                    cursor, PageRequest.of(0, BATCH_SIZE));
            if (payableMilestoneOrders.isEmpty()) return;
            for (String orderId : payableMilestoneOrders) {
                try {
                    activation.activateIfEligible(orderId);
                } catch (RuntimeException exception) {
                    log.warn("Mission activation remains pending for Order {} and will be retried", orderId, exception);
                }
            }
            cursor = payableMilestoneOrders.get(payableMilestoneOrders.size() - 1);
            if (payableMilestoneOrders.size() < BATCH_SIZE) return;
        }
    }

}
