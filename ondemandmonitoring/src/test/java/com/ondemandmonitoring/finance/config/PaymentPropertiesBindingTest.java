package com.ondemandmonitoring.finance.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class PaymentPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class)
            .withPropertyValues(
                    "omss.payment.deposit-rate=0.25",
                    "omss.payment.pending-payment-grace=PT45M",
                    "omss.payment.expiration-batch-size=25");

    @Test
    void bindsUsingCanonicalRecordConstructorWhenConvenienceConstructorExists() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();

            PaymentProperties properties = context.getBean(PaymentProperties.class);
            assertThat(properties.depositRate()).isEqualByComparingTo(new BigDecimal("0.25"));
            assertThat(properties.pendingPaymentGrace()).isEqualTo(Duration.ofMinutes(45));
            assertThat(properties.expirationBatchSize()).isEqualTo(25);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PaymentProperties.class)
    static class TestConfiguration {}
}
