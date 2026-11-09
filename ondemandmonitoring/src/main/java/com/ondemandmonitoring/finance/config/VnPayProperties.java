package com.ondemandmonitoring.finance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "vnpay")
public record VnPayProperties(
        String tmnCode,
        String hashSecret,
        String paymentUrl,
        String queryUrl,
        String returnUrl,
        String ipnUrl,
        String frontendResultUrl,
        String version,
        String command,
        String orderType,
        String locale,
        int expireMinutes,
        int queryConnectTimeoutMs,
        int queryReadTimeoutMs) {

    public VnPayProperties {
        version = isBlank(version) ? "2.1.0" : version;
        command = isBlank(command) ? "pay" : command;
        orderType = isBlank(orderType) ? "other" : orderType;
        locale = isBlank(locale) ? "vn" : locale;
        if (expireMinutes <= 0 || expireMinutes > 60) expireMinutes = 15;
        if (queryConnectTimeoutMs <= 0) queryConnectTimeoutMs = 3000;
        if (queryReadTimeoutMs <= 0) queryReadTimeoutMs = 5000;
    }

    public boolean configured() {
        return !isBlank(tmnCode) && !isBlank(hashSecret) && !isBlank(paymentUrl) && !isBlank(returnUrl);
    }

    public boolean queryConfigured() {
        return configured() && !isBlank(queryUrl);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
