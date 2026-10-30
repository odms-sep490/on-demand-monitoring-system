package com.ondemandmonitoring.finance.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.finance.dto.PaymentResponse;
import com.ondemandmonitoring.finance.enums.PaymentType;
import com.ondemandmonitoring.finance.enums.PaymentWebhookResult;
import com.ondemandmonitoring.finance.gateway.*;
import java.util.List;
import java.util.Map;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import com.ondemandmonitoring.finance.record.PaymentReconciliationContext;
import com.ondemandmonitoring.finance.record.PreparedPayment;
import com.ondemandmonitoring.finance.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/** Orchestrates payment use cases without containing provider checksum details. */
@Service
@RequiredArgsConstructor
public class PaymentService implements IPaymentService {
    private final IPaymentPersistenceService persistence;
    private final IPaymentWebhookService webhookProcessor;
    private final PaymentGateway gateway;
    private final PaymentStatusGateway statusGateway;
    private final Environment environment;

    public PaymentResponse createDeposit(String invoiceId, String clientIp) { return create(invoiceId, PaymentType.DEPOSIT, clientIp); }
    public PaymentResponse completeTestDeposit(String invoiceId) {
        return completeTestPayment(invoiceId, PaymentType.DEPOSIT);
    }
    public PaymentResponse completeTestFinal(String invoiceId) {
        return completeTestPayment(invoiceId, PaymentType.FINAL_PAYMENT);
    }
    private PaymentResponse completeTestPayment(String invoiceId, PaymentType type) {
        if (!environment.acceptsProfiles(Profiles.of("!prod"))) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Test payment is disabled in production");
        }
        PreparedPayment prepared = persistence.prepare(invoiceId, type);
        webhookProcessor.process(new VerifiedWebhook(
                prepared.transactionReference(), prepared.amount(), "VND",
                "TEST-" + prepared.transactionReference(), null, "00", "00", true));
        return persistence.getByReference(prepared.transactionReference());
    }
    public PaymentResponse createFinal(String invoiceId, String clientIp) { return create(invoiceId, PaymentType.FINAL_PAYMENT, clientIp); }
    public PaymentResponse get(String paymentId) { return persistence.get(paymentId); }
    public PaymentResponse getByReference(String transactionReference) {
        return persistence.getByReference(transactionReference);
    }

    /**
     * Reconciles a pending payment directly with VNPAY QueryDR. This is the safe
     * fallback when a development machine cannot expose its IPN URL publicly.
     */
    public PaymentResponse refreshByReference(String transactionReference, String requestIp) {
        return reconcile(transactionReference, requestIp, false);
    }

    public int refreshOrderPayments(String orderId, String requestIp) {
        List<String> references = persistence.pendingReferencesForOrder(orderId);
        for (String reference : references) reconcile(reference, requestIp, true);
        return references.size();
    }

    private PaymentResponse reconcile(String transactionReference, String requestIp, boolean staff) {
        PaymentResponse current = staff ? persistence.getByReferenceForStaff(transactionReference)
                : persistence.getByReference(transactionReference);
        if (current.status() != com.ondemandmonitoring.finance.enums.PaymentStatus.PENDING
                && current.status() != com.ondemandmonitoring.finance.enums.PaymentStatus.PROCESSING) {
            return current;
        }
        PaymentReconciliationContext context = staff ? persistence.reconciliationContextForStaff(transactionReference)
                : persistence.reconciliationContext(transactionReference);
        String providerRequestDate = context.providerRequestDate() == null
                ? queryParameter(context.paymentUrl(), "vnp_CreateDate") : context.providerRequestDate();
        if (providerRequestDate == null || !providerRequestDate.matches("\\d{14}")) {
            throw new ApiException(ErrorCode.PAYMENT_PROVIDER_ERROR,
                    "Payment is missing the VNPAY transaction date required for reconciliation");
        }
        VerifiedWebhook verified;
        try {
            verified = statusGateway.queryPayment(new PaymentQueryCommand(
                    context.transactionReference(), context.providerTransactionId(), providerRequestDate, requestIp));
        } catch (PaymentGatewayException exception) {
            // QueryDR can briefly return "transaction not found" while VNPAY is
            // propagating a fresh payment. Keep PENDING so bounded polling can retry.
            return current;
        }
        if (verified.successful() || isTerminalFailure(verified.transactionStatus())) {
            webhookProcessor.process(verified);
        }
        return staff ? persistence.getByReferenceForStaff(transactionReference) : persistence.getByReference(transactionReference);
    }
    public List<PaymentResponse> list(String invoiceId) { return persistence.list(invoiceId); }

    public PaymentWebhookResult processVnPayWebhook(Map<String, String> payload) {
        VerifiedWebhook verified = gateway.verifyWebhook(payload);
        return webhookProcessor.process(verified);
    }

    public VerifiedWebhook verifyVnPayReturn(Map<String, String> payload) {
        return gateway.verifyWebhook(payload);
    }

    private PaymentResponse create(String invoiceId, PaymentType type, String clientIp) {
        PreparedPayment prepared = persistence.prepare(invoiceId, type);
        if (prepared.existing()) return prepared.response();
        try {
            PaymentLinkResult link = gateway.createPaymentLink(
                    new PaymentLinkCommand(prepared.transactionReference(), prepared.amount(),
                            prepared.description(), clientIp));
            return persistence.attachLink(prepared.id(), link);
        } catch (PaymentGatewayException exception) {
            persistence.fail(prepared.id(), exception.getMessage());
            throw new ApiException(ErrorCode.PAYMENT_PROVIDER_ERROR, exception.getMessage());
        } catch (RuntimeException exception) {
            persistence.fail(prepared.id(), exception.getMessage());
            throw exception;
        }
    }

    private boolean isTerminalFailure(String transactionStatus) {
        return List.of("02", "07", "09").contains(transactionStatus);
    }

    private String queryParameter(String url, String name) {
        if (url == null) return null;
        int queryStart = url.indexOf('?');
        if (queryStart < 0) return null;
        for (String pair : url.substring(queryStart + 1).split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2 && name.equals(URLDecoder.decode(parts[0], StandardCharsets.UTF_8))) {
                return URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
