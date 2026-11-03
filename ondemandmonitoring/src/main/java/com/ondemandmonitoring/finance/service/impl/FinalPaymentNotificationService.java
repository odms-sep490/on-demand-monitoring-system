package com.ondemandmonitoring.finance.service.impl;

import com.ondemandmonitoring.finance.event.FinalPaymentRequiredEvent;
import com.ondemandmonitoring.finance.repository.InvoiceRepository;
import com.ondemandmonitoring.finance.service.IFinalPaymentNotificationService;
import com.ondemandmonitoring.finance.service.IMissionPaymentEligibilityService;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FinalPaymentNotificationService implements IFinalPaymentNotificationService {
    private final InvoiceRepository invoices;
    private final FinalPaymentDeadlineService deadlines;
    private final IMissionPaymentEligibilityService eligibility;
    private final ApplicationEventPublisher events;

    @Override
    @Transactional
    public void notifyIfEligible(String orderId) {
        var invoice = invoices.findByOrderId(orderId).orElse(null);
        if (invoice == null || invoice.getOrder() == null || invoice.getOrder().getCustomer() == null) return;

        // Start the payment window as soon as planning makes the remaining balance payable.
        if (deadlines.assignIfEligible(invoice) == null || !eligibility.isFinalPaymentEligible(invoice)) return;

        events.publishEvent(new FinalPaymentRequiredEvent(
                invoice.getOrder().getCustomer().getId(), orderId, invoice.getId(), Instant.now()));
    }
}
