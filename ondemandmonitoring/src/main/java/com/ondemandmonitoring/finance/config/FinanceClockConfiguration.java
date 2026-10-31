package com.ondemandmonitoring.finance.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FinanceClockConfiguration {
    @Bean
    Clock financeClock() {
        return Clock.systemUTC();
    }
}
