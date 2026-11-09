package com.ondemandmonitoring.finance.service;

import com.ondemandmonitoring.finance.dto.PaymentResponse;
import com.ondemandmonitoring.finance.enums.PaymentType;
import com.ondemandmonitoring.finance.gateway.PaymentLinkResult;
import com.ondemandmonitoring.finance.record.PaymentReconciliationContext;
import com.ondemandmonitoring.finance.record.PreparedPayment;

import java.util.List;

/** Persistence boundary for payment creation and customer-owned reads. */
public interface IPaymentPersistenceService {
    PreparedPayment prepare(String invoiceId, PaymentType type);
    PaymentResponse attachLink(String paymentId, PaymentLinkResult link);
    void fail(String paymentId, String reason);
    PaymentResponse get(String paymentId);
    PaymentResponse getByReference(String transactionReference);
    PaymentReconciliationContext reconciliationContext(String transactionReference);
    List<PaymentResponse> list(String invoiceId);

    /** Staff reconciliation: references of the order's VNPAY payments still waiting for a result. */
    List<String> pendingReferencesForOrder(String orderId);
    /** Staff reconciliation: same as {@link #getByReference} without the customer-ownership check. */
    PaymentResponse getByReferenceForStaff(String transactionReference);
    /** Staff reconciliation: same as {@link #reconciliationContext} without the customer-ownership check. */
    PaymentReconciliationContext reconciliationContextForStaff(String transactionReference);
}
