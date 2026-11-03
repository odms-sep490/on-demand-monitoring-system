package com.ondemandmonitoring.finance.service.impl;

import com.ondemandmonitoring.finance.domain.Invoice;
import com.ondemandmonitoring.finance.repository.InvoiceRepository;
import com.ondemandmonitoring.finance.service.IMissionPaymentEligibilityService;
import com.ondemandmonitoring.order.repository.OrderRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Assigns a stable final-payment deadline the first time the balance becomes payable. */
@Service
@RequiredArgsConstructor
public class FinalPaymentDeadlineService {
    @PersistenceContext
    private EntityManager entityManager;
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
        Instant currentDeadline = invoice.getFinalPaymentExpiresAt();
        if (currentDeadline != null || !eligibility.isFinalPaymentEligible(invoice)) return currentDeadline;
        var order = orders.findByIdForUpdate(invoice.getOrder().getId()).orElse(null);
        if (order == null) return null;
        var lockedInvoice = invoices.findByIdForUpdate(invoice.getId()).orElse(null);
        if (lockedInvoice == null) return null;
        entityManager.refresh(lockedInvoice, LockModeType.PESSIMISTIC_WRITE);
        currentDeadline = lockedInvoice.getFinalPaymentExpiresAt();
        if (currentDeadline != null) return currentDeadline;
        if (eligibility.isFinalPaymentEligible(lockedInvoice)) {
            Instant assignedAt = Instant.now(clock).plus(settings.current().finalPaymentTimeout());
            lockedInvoice.setFinalPaymentExpiresAt(assignedAt);
            invoices.save(lockedInvoice);
        }
        return lockedInvoice.getFinalPaymentExpiresAt();
    }
}
