package com.ondemandmonitoring.finance.mapper;

import com.ondemandmonitoring.finance.domain.*;
import com.ondemandmonitoring.finance.dto.*;
import java.util.Comparator;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import com.ondemandmonitoring.order.enums.OrderChecklistSourceType;
import com.ondemandmonitoring.finance.enums.QuoteItemType;

public final class FinanceMapper {
    private FinanceMapper() {}

    public static QuoteResponse quote(Quote quote) {
        return new QuoteResponse(quote.getId(), quote.getOrder().getId(), quote.getQuoteVersion(), quote.getStatus(),
                quote.getPackagePrice(), quote.getAdditionalAmount(), quote.getDiscountAmount(),
                quote.getAdjustmentAmount(), quote.getTotalAmount(), quote.getManagerNote(),
                quote.getApprovedBy() == null ? null : quote.getApprovedBy().getFullName(),
                quote.getApprovedAt(), quote.getAcceptedAt(), quote.getItems().stream()
                .sorted(Comparator.comparingInt(QuoteItem::getDisplayOrder)).map(FinanceMapper::quoteItem).toList(),
                quote.getPricingPolicyId(), quote.getPricingPolicyVersion(), quote.getSystemCalculatedAmount(),
                quote.getManagerAdjustmentAmount(), quote.getAdjustmentType(), quote.getAdjustmentReason(),
                quote.getAdjustmentRequestedBy() == null ? null : quote.getAdjustmentRequestedBy().getId(),
                quote.getAdjustmentRequestedBy() == null ? null : quote.getAdjustmentRequestedBy().getFullName(),
                quote.getAdjustmentRequestedAt(), quote.getPricingApprovalStatus(),
                quote.getPricingApprovedBy() == null ? null : quote.getPricingApprovedBy().getFullName(),
                quote.getPricingApprovedAt(), quote.getPricingApprovalNote(), quote.getPricingRevision(),
                quote.getPricingRiskFlags() == null ? java.util.List.of() : quote.getPricingRiskFlags(),
                quote.getChecklistRevision(), quote.getChecklistGuidanceSnapshot(), quote.getExpiresAt());
    }

    public static QuoteItemResponse quoteItem(QuoteItem item) {
        return new QuoteItemResponse(item.getId(), item.getOrderChecklistItem() == null ? null : item.getOrderChecklistItem().getId(),
                item.getType(), item.getDescription(), item.getQuantity(), item.getUnitPrice(), item.getAmount(), item.getDisplayOrder());
    }

    public static CustomerQuoteResponse customerQuote(Quote quote) {
        List<QuoteItemResponse> safeItems = new ArrayList<>();
        Map<String, OrderChecklistItem> additional = new LinkedHashMap<>();
        for (QuoteItem line : quote.getItems().stream().sorted(Comparator.comparingInt(QuoteItem::getDisplayOrder)).toList()) {
            OrderChecklistItem item = line.getOrderChecklistItem();
            if (item != null && item.getSourceType() != OrderChecklistSourceType.SERVICE_TEMPLATE) {
                additional.putIfAbsent(item.getId(), item);
                continue;
            }
            safeItems.add(quoteItem(line));
        }
        BigDecimal approvedAdditional = BigDecimal.ZERO;
        for (OrderChecklistItem item : additional.values()) {
            BigDecimal amount = item.getFinalAmount() == null ? BigDecimal.ZERO : item.getFinalAmount();
            approvedAdditional = approvedAdditional.add(amount);
            safeItems.add(new QuoteItemResponse("additional-" + item.getId(), item.getId(), QuoteItemType.ADDITIONAL,
                    item.getContent(), BigDecimal.ONE, amount, amount, safeItems.size()));
        }
        BigDecimal estimatedBase = quote.getTotalAmount().subtract(approvedAdditional);
        return new CustomerQuoteResponse(quote.getId(), quote.getOrder().getId(), quote.getQuoteVersion(), quote.getStatus(),
                estimatedBase, approvedAdditional, quote.getTotalAmount(), true,
                quote.getApprovedAt(), quote.getAcceptedAt(), quote.getExpiresAt(), List.copyOf(safeItems),
                quote.getChecklistRevision(), quote.getChecklistGuidanceSnapshot(),
                quote.getOrder().getCancellationReason() == null ? null : quote.getOrder().getCancellationReason().name());
    }

    public static InvoiceResponse invoice(Invoice invoice, boolean finalPaymentAllowed) {
        return new InvoiceResponse(invoice.getId(), invoice.getInvoiceNumber(), invoice.getOrder().getId(),
                invoice.getQuote().getId(), invoice.getTotalAmount(), invoice.getDepositAmount(),
                invoice.getPaidAmount(), invoice.getRemainingAmount(), invoice.getStatus(), invoice.getIssuedAt(),
                finalPaymentAllowed, invoice.getOrder().getDepositExpiresAt(),
                invoice.getOrder().getCancellationReason() == null ? null : invoice.getOrder().getCancellationReason().name());
    }

    public static PaymentResponse payment(Payment payment) {
        return new PaymentResponse(payment.getId(), payment.getInvoice().getId(), payment.getPaymentCode(),
                payment.getPaymentType(), payment.getAmount(), payment.getCurrency(), payment.getStatus(),
                payment.getProvider(), payment.getPaymentUrl(), payment.getTransactionReference(),
                payment.getProviderTransactionId(), payment.getPaidAt());
    }
}
