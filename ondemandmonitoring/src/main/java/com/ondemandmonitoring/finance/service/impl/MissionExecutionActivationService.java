package com.ondemandmonitoring.finance.service.impl;

import com.ondemandmonitoring.finance.repository.InvoiceRepository;
import com.ondemandmonitoring.finance.service.IMissionExecutionActivationService;
import com.ondemandmonitoring.finance.service.IMissionPaymentEligibilityService;
import com.ondemandmonitoring.mission.service.IMissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
@Slf4j
public class MissionExecutionActivationService implements IMissionExecutionActivationService {
    private final InvoiceRepository invoices;
    private final IMissionPaymentEligibilityService eligibility;
    private final IMissionService missions;

    @Override
    @Transactional
    public void activateIfEligible(String orderId) {
        var invoice = invoices.findByOrderId(orderId).orElse(null);
        if (invoice == null || !eligibility.isMissionExecutionEligible(invoice)) return;
        Runnable activate = () -> {
            try {
                missions.transitionToPendingConfirmation(orderId);
            } catch (RuntimeException exception) {
                log.warn("Mission activation remains pending for Order {} and will be retried", orderId, exception);
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            activate.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                activate.run();
            }
        });
    }
}
