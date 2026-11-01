package com.ondemandmonitoring.finance.service;

import static org.mockito.Mockito.*;

import com.ondemandmonitoring.finance.repository.InvoiceRepository;
import com.ondemandmonitoring.finance.service.impl.FinalPaymentMissionActivationRecoveryJob;
import com.ondemandmonitoring.mission.service.IMissionService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

class FinalPaymentMissionActivationRecoveryJobTest {
    private final InvoiceRepository invoices = mock(InvoiceRepository.class);
    private final IMissionService missions = mock(IMissionService.class);
    private final FinalPaymentMissionActivationRecoveryJob job =
            new FinalPaymentMissionActivationRecoveryJob(invoices, missions);

    @Test
    void retriesEveryOrderWithASettledInvoiceAndPendingMissionActivation() {
        when(invoices.findOrderIdsPendingFinalPaymentActivationAfter("", PageRequest.of(0, 500)))
                .thenReturn(List.of("order-1", "order-2"));

        job.recoverPendingMissionActivations();

        verify(missions).transitionToPendingConfirmation("order-1");
        verify(missions).transitionToPendingConfirmation("order-2");
    }

    @Test
    void continuesRetryingOtherOrdersWhenOneActivationFails() {
        when(invoices.findOrderIdsPendingFinalPaymentActivationAfter("", PageRequest.of(0, 500)))
                .thenReturn(List.of("order-1", "order-2"));
        doThrow(new IllegalStateException("temporary database failure"))
                .when(missions).transitionToPendingConfirmation("order-1");

        job.recoverPendingMissionActivations();

        verify(missions).transitionToPendingConfirmation("order-1");
        verify(missions).transitionToPendingConfirmation("order-2");
    }
}
