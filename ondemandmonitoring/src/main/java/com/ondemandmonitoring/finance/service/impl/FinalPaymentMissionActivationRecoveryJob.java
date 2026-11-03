package com.ondemandmonitoring.finance.service.impl;

import com.ondemandmonitoring.finance.repository.InvoiceRepository;
import com.ondemandmonitoring.mission.service.IMissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Recovers mission activation when payment settlement committed but activation could not complete. */
@Slf4j
@Component
@RequiredArgsConstructor
public class FinalPaymentMissionActivationRecoveryJob {
    private static final int BATCH_SIZE = 500;

    private final InvoiceRepository invoices;
    private final IMissionService missions;

    @Scheduled(fixedDelayString = "${omss.payment.mission-activation-recovery-delay-ms:60000}")
    public void recoverPendingMissionActivations() {
        String cursor = "";
        while (true) {
            var batch = invoices.findOrderIdsPendingFinalPaymentActivationAfter(
                    cursor, PageRequest.of(0, BATCH_SIZE));
            if (batch.isEmpty()) return;
            for (String orderId : batch) {
                try {
                    missions.transitionToPendingConfirmation(orderId);
                } catch (RuntimeException exception) {
                    log.warn("Mission activation remains pending for paid Order {} and will be retried", orderId, exception);
                }
            }
            cursor = batch.get(batch.size() - 1);
            if (batch.size() < BATCH_SIZE) return;
        }
    }
}
