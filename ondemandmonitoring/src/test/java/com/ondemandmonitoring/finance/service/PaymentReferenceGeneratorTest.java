package com.ondemandmonitoring.finance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import com.ondemandmonitoring.finance.util.PaymentReferenceGenerator;
import org.junit.jupiter.api.Test;

class PaymentReferenceGeneratorTest {
    @Test
    void combinesEpochMillisecondsWithSixDigitSecureRandomSuffix() {
        SecureRandom random = mock(SecureRandom.class);
        when(random.nextInt(1_000_000)).thenReturn(42);
        Clock clock = Clock.fixed(Instant.ofEpochMilli(1_760_000_000_000L), ZoneOffset.UTC);

        String reference = new PaymentReferenceGenerator(random, clock).generate();

        assertThat(reference).isEqualTo("1760000000000000042").matches("[0-9]{19}");
    }
}
