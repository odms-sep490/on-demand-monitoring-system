package com.ondemandmonitoring.finance.util;

import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.stereotype.Component;

/** Generates a numeric VNPAY merchant reference without relying on database sequences. */
@Component
public class PaymentReferenceGenerator {
    private static final int SUFFIX_BOUND = 1_000_000;
    private final SecureRandom random;
    private final Clock clock;

    public PaymentReferenceGenerator() {
        this(new SecureRandom(), Clock.systemUTC());
    }

    public PaymentReferenceGenerator(SecureRandom random, Clock clock) {
        this.random = random;
        this.clock = clock;
    }

    public String generate() {
        long timestamp = clock.millis();
        int suffix = random.nextInt(SUFFIX_BOUND);
        return timestamp + String.format("%06d", suffix);
    }
}
