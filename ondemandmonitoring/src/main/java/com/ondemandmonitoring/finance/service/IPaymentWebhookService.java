package com.ondemandmonitoring.finance.service;

import com.ondemandmonitoring.finance.enums.PaymentWebhookResult;
import com.ondemandmonitoring.finance.gateway.VerifiedWebhook;

/** Transactional boundary that applies a verified provider result exactly once. */
public interface IPaymentWebhookService {
    PaymentWebhookResult process(VerifiedWebhook webhook);
}
