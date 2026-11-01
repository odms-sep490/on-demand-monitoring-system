package com.ondemandmonitoring.finance.config;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "omss.payment")
public record PaymentProperties(BigDecimal depositRate, Duration pendingPaymentGrace, int expirationBatchSize) {
    @ConstructorBinding
    public PaymentProperties {
        if (depositRate == null) depositRate = new BigDecimal("0.30");
        if (pendingPaymentGrace == null) pendingPaymentGrace = Duration.ofMinutes(30);
        if (expirationBatchSize <= 0) expirationBatchSize = 100;
        if (depositRate.signum() <= 0 || depositRate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("omss.payment.deposit-rate must be in (0, 1]");
        }
        if (pendingPaymentGrace.isNegative()) {
            throw new IllegalArgumentException("omss.payment.pending-payment-grace must not be negative");
        }
    }

    public PaymentProperties(BigDecimal depositRate) {
        this(depositRate, null, 100);
    }
}
