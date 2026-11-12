package com.ondemandmonitoring.finance.service;

import static org.mockito.Mockito.*;

import com.ondemandmonitoring.finance.repository.InvoiceRepository;
import com.ondemandmonitoring.finance.service.IMissionExecutionActivationService;
import com.ondemandmonitoring.finance.service.impl.MissionExecutionActivationRecoveryJob;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

class MissionExecutionActivationRecoveryJobTest {
    private final InvoiceRepository invoices = mock(InvoiceRepository.class);
    private final IMissionExecutionActivationService activation = mock(IMissionExecutionActivationService.class);
    private final MissionExecutionActivationRecoveryJob job =
            new MissionExecutionActivationRecoveryJob(invoices, activation);

    @Test
    void retriesOrdersAwaitingMissionMilestoneActivation() {
        when(invoices.findOrderIdsAwaitingMissionActivationAfter("", PageRequest.of(0, 500)))
                .thenReturn(List.of("order-1"));

        job.recoverPendingMissionActivations();

        verify(activation).activateIfEligible("order-1");
    }
}
