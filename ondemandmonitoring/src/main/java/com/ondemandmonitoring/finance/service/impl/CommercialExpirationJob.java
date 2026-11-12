package com.ondemandmonitoring.finance.service.impl;

import com.ondemandmonitoring.finance.config.PaymentProperties;
import com.ondemandmonitoring.finance.repository.InvoiceRepository;
import com.ondemandmonitoring.finance.repository.MissionPaymentMilestoneRepository;
import com.ondemandmonitoring.finance.repository.QuoteRepository;
import com.ondemandmonitoring.finance.enums.MissionPaymentMilestoneStatus;
import java.util.List;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CommercialExpirationJob {
    private final QuoteRepository quotes;
    private final InvoiceRepository invoices;
    private final MissionPaymentMilestoneRepository milestones;
    private final CommercialExpirationProcessor processor;
    private final PaymentProperties properties;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${omss.payment.expiration-job-delay-ms:60000}")
    public void run() {
        Instant now = Instant.now(clock);
        var page = PageRequest.of(0, properties.expirationBatchSize());
        quotes.findExpiredApprovedIds(now, page).forEach(id -> processQuote(id));
        invoices.findExpiredDepositInvoiceIds(now, page).forEach(id -> processInvoice(id));
        milestones.findExpiredPaymentMilestoneIds(now,
                List.of(MissionPaymentMilestoneStatus.UNPAID, MissionPaymentMilestoneStatus.PENDING), page)
                .forEach(this::processMissionPayment);
    }

    private void processQuote(String id) {
        try { processor.expireQuote(id); }
        catch (RuntimeException exception) { log.error("Could not expire quote {}", id, exception); }
    }

    private void processInvoice(String id) {
        try { processor.expireDeposit(id); }
        catch (RuntimeException exception) { log.error("Could not expire deposit invoice {}", id, exception); }
    }

    private void processMissionPayment(String id) {
        try { processor.expireMissionPayment(id); }
        catch (RuntimeException exception) { log.error("Could not expire Mission payment milestone {}", id, exception); }
    }
}
