package com.ondemandmonitoring.finance.service.impl;

import com.ondemandmonitoring.finance.domain.Invoice;
import com.ondemandmonitoring.finance.repository.InvoiceRepository;
import com.ondemandmonitoring.finance.service.IMissionPaymentEligibilityService;
import com.ondemandmonitoring.order.repository.OrderRepository;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Assigns a stable final-payment deadline the first time the balance becomes payable. */
@Service
@RequiredArgsConstructor
public class FinalPaymentDeadlineService {
    private final InvoiceRepository invoices;
    private final OrderRepository orders;
    private final CommercialExpirationSettingsService settings;
    private final IMissionPaymentEligibilityService eligibility;
    private final Clock clock;

    @Transactional
    public Instant assignIfEligible(String invoiceId) {
        return invoices.findById(invoiceId).map(this::assignIfEligible).orElse(null);
    }

    @Transactional
    public Instant assignIfEligible(Invoice invoice) {
        if (invoice == null || invoice.getOrder() == null) return null;
        Instant currentDeadline = invoice.getOrder().getFinalPaymentExpiresAt();
        if (currentDeadline != null || !eligibility.isFinalPaymentEligible(invoice)) return currentDeadline;
        var order = orders.findByIdForUpdate(invoice.getOrder().getId()).orElse(null);
        if (order == null) return null;
        if (order.getFinalPaymentExpiresAt() == null && eligibility.isFinalPaymentEligible(invoice)) {
            Instant assignedAt = Instant.now(clock).plus(settings.current().finalPaymentTimeout());
            order.setFinalPaymentExpiresAt(assignedAt);
            orders.save(order);
        }
        return order.getFinalPaymentExpiresAt();
    }
}
