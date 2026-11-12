package com.ondemandmonitoring.finance.config;

import java.net.URI;
import java.util.Locale;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Validates payment provider mode and callback URLs independently of any internal balance ledger. */
@Component
public class VnPayModeValidator {
    public VnPayModeValidator(VnPayProperties vnpay, Environment environment) {
        boolean productionProfile = environment.acceptsProfiles(Profiles.of("prod"));
        if (productionProfile && isBlank(environment.getProperty("VNPAY_MODE"))) {
            throw new IllegalStateException(
                    "The prod profile requires VNPAY_MODE to be explicitly set to SANDBOX or PRODUCTION");
        }
        String mode = vnpay.mode();
        if (!"SANDBOX".equals(mode) && !"PRODUCTION".equals(mode)) {
            throw new IllegalStateException("VNPAY_MODE must be SANDBOX or PRODUCTION");
        }
        boolean paymentSandboxUrl = isSandboxUrl(vnpay.paymentUrl());
        boolean querySandboxUrl = isSandboxUrl(vnpay.queryUrl());
        boolean sandboxUrls = paymentSandboxUrl && querySandboxUrl;
        if ("SANDBOX".equals(mode) && !sandboxUrls) {
            throw new IllegalStateException("VNPAY_MODE=SANDBOX requires VNPAY sandbox payment and query URLs");
        }
        if ("PRODUCTION".equals(mode) && (paymentSandboxUrl || querySandboxUrl)) {
            throw new IllegalStateException("VNPAY_MODE=PRODUCTION cannot use VNPAY sandbox URLs");
        }
        if ("PRODUCTION".equals(mode)
                && (!vnpay.configured() || !vnpay.queryConfigured() || isBlank(vnpay.ipnUrl()))) {
            throw new IllegalStateException(
                    "VNPAY production mode requires production credentials, payment/query URLs and an IPN URL");
        }
        boolean testPaymentsEnabled = environment.getProperty(
                "omss.payment.test-payments-enabled", Boolean.class, false);
        if (testPaymentsEnabled && ("PRODUCTION".equals(mode)
                || productionProfile)) {
            throw new IllegalStateException(
                    "Test payments must be disabled with VNPAY production mode or the prod profile");
        }
        if (productionProfile
                && (!isPublicHttpsUrl(vnpay.returnUrl()) || !isPublicHttpsUrl(vnpay.ipnUrl()))) {
            throw new IllegalStateException(
                    "The production profile requires public HTTPS VNPAY return and IPN URLs");
        }
    }

    private boolean isSandboxUrl(String value) {
        if (isBlank(value)) return false;
        try {
            URI uri = URI.create(value);
            return uri.getHost() != null
                    && "sandbox.vnpayment.vn".equals(uri.getHost().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean isPublicHttpsUrl(String value) {
        if (isBlank(value)) return false;
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null) return false;
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            return !normalizedHost.equals("localhost") && !normalizedHost.equals("127.0.0.1")
                    && !normalizedHost.equals("::1") && !normalizedHost.endsWith(".local");
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
