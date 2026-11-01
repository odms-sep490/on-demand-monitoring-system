package com.ondemandmonitoring.finance.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.finance.config.VnPayProperties;
import java.util.Map;

import com.ondemandmonitoring.finance.service.IPaymentService;
import com.ondemandmonitoring.finance.service.IVnPayCallbackService;
import com.ondemandmonitoring.finance.enums.PaymentWebhookResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** VNPAY-specific application service; the web controller remains transport-only. */
@Service
@RequiredArgsConstructor
public class VnPayCallbackService implements IVnPayCallbackService {
    private final IPaymentService paymentService;
    private final VnPayProperties properties;

    @Override
    public Map<String, String> handleIpn(Map<String, String> parameters) {
        try {
            return response(paymentService.processVnPayWebhook(parameters));
        } catch (ApiException exception) {
            if (exception.getErrorCode() == ErrorCode.PAYMENT_WEBHOOK_INVALID) {
                return Map.of("RspCode", "97", "Message", "Invalid signature");
            }
            return Map.of("RspCode", "99", "Message", "Unknown error");
        } catch (RuntimeException exception) {
            return Map.of("RspCode", "99", "Message", "Unknown error");
        }
    }

    @Override
    public String buildReturnRedirect(Map<String, String> parameters) {
        String target = properties.frontendResultUrl();
        try {
            var verified = paymentService.verifyVnPayReturn(parameters);
            if (!verified.successful()) target = target.replace("#payment/result", "#payment/cancel");
            return target + separator(target) + "transactionReference=" + verified.transactionReference();
        } catch (RuntimeException exception) {
            return target + separator(target) + "error=invalid-signature";
        }
    }

    private Map<String, String> response(PaymentWebhookResult result) {
        return switch (result) {
            case PROCESSED -> Map.of("RspCode", "00", "Message", "Confirm success");
            case ALREADY_CONFIRMED -> Map.of("RspCode", "02", "Message", "Order already confirmed");
            case ORDER_NOT_FOUND -> Map.of("RspCode", "01", "Message", "Order not found");
            case INVALID_AMOUNT -> Map.of("RspCode", "04", "Message", "Invalid amount");
            case IGNORED -> Map.of("RspCode", "00", "Message", "Payment result recorded");
            case LATE_PAYMENT_RECORDED -> Map.of("RspCode", "00", "Message", "Late payment recorded for reconciliation");
        };
    }

    private String separator(String target) {
        return target.contains("?") ? "&" : "?";
    }
}
