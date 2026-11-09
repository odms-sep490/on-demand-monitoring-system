package com.ondemandmonitoring.finance.gateway;

import java.util.Map;

public interface PaymentGateway {
    PaymentLinkResult createPaymentLink(PaymentLinkCommand command);
    VerifiedWebhook verifyWebhook(Map<String, String> payload);
}
