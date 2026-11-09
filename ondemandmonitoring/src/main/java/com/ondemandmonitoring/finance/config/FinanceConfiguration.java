package com.ondemandmonitoring.finance.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({PaymentProperties.class, VnPayProperties.class})
public class FinanceConfiguration {}
