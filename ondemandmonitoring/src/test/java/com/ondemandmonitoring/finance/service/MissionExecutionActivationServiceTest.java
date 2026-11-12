package com.ondemandmonitoring.finance.service;

import static org.mockito.Mockito.*;

import com.ondemandmonitoring.finance.domain.Invoice;
import com.ondemandmonitoring.finance.repository.InvoiceRepository;
import com.ondemandmonitoring.finance.service.impl.MissionExecutionActivationService;
import com.ondemandmonitoring.mission.service.IMissionService;
import com.ondemandmonitoring.order.domain.Order;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class MissionExecutionActivationServiceTest {
    private final InvoiceRepository invoices = mock(InvoiceRepository.class);
    private final IMissionPaymentEligibilityService eligibility = mock(IMissionPaymentEligibilityService.class);
    private final IMissionService missions = mock(IMissionService.class);
    private final MissionExecutionActivationService service =
            new MissionExecutionActivationService(invoices, eligibility, missions);

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void activatesMissionsAfterDepositAndAllocationEligibilityCommits() {
        Order order = new Order();
        order.setId("order-1");
        Invoice invoice = new Invoice();
        invoice.setId("invoice-1");
        invoice.setOrder(order);
        when(invoices.findByOrderId("order-1")).thenReturn(Optional.of(invoice));
        when(eligibility.isMissionExecutionEligible(invoice)).thenReturn(true);
        TransactionSynchronizationManager.initSynchronization();

        service.activateIfEligible("order-1");

        verifyNoInteractions(missions);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
        verify(missions).transitionToPendingConfirmation("order-1");
    }

    @Test
    void doesNotActivateWhenDepositOrAllocationIsNotReady() {
        Order order = new Order();
        order.setId("order-1");
        Invoice invoice = new Invoice();
        invoice.setId("invoice-1");
        invoice.setOrder(order);
        when(invoices.findByOrderId("order-1")).thenReturn(Optional.of(invoice));
        when(eligibility.isMissionExecutionEligible(invoice)).thenReturn(false);

        service.activateIfEligible("order-1");

        verify(eligibility).isMissionExecutionEligible(invoice);
        verifyNoInteractions(missions);
    }
}
