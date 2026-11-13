package com.ondemandmonitoring.finance.service.impl;

import com.ondemandmonitoring.finance.repository.InvoiceRepository;
import com.ondemandmonitoring.finance.service.IMissionExecutionActivationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Recovers confirmation when a paid deposit and finalized plan were not activated successfully. */
@Slf4j
@Component
@RequiredArgsConstructor
public class MissionExecutionActivationRecoveryJob {
    private static final int BATCH_SIZE = 500;

    private final InvoiceRepository invoices;
    private final IMissionExecutionActivationService activation;

    @Scheduled(fixedDelayString = "${omss.payment.mission-activation-recovery-delay-ms:60000}")
    public void recoverPendingMissionActivations() {
        recoverPendingConfirmations();
    }

    private void recoverPendingConfirmations() {
        String cursor = "";
        while (true) {
            var eligibleOrders = invoices.findOrderIdsAwaitingMissionActivationAfter(
                    cursor, PageRequest.of(0, BATCH_SIZE));
            if (eligibleOrders.isEmpty()) return;
            for (String orderId : eligibleOrders) {
                try {
                    activation.activateIfEligible(orderId);
                } catch (RuntimeException exception) {
                    log.warn("Mission activation remains pending for Order {} and will be retried", orderId, exception);
                }
            }
            cursor = eligibleOrders.get(eligibleOrders.size() - 1);
            if (eligibleOrders.size() < BATCH_SIZE) return;
        }
    }

}
