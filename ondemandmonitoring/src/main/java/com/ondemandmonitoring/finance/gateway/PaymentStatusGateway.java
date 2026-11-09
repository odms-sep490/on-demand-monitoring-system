package com.ondemandmonitoring.finance.gateway;

/** Outbound port used when an IPN was delayed or could not reach the merchant. */
public interface PaymentStatusGateway {
    VerifiedWebhook queryPayment(PaymentQueryCommand command);
}
