package com.ondemandmonitoring.finance.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.delivery.enums.DeliveryStatus;
import com.ondemandmonitoring.delivery.repository.OrderDeliveryRepository;
import com.ondemandmonitoring.finance.config.PaymentProperties;
import com.ondemandmonitoring.finance.domain.*;
import com.ondemandmonitoring.finance.dto.*;
import com.ondemandmonitoring.finance.enums.*;
import com.ondemandmonitoring.finance.repository.InvoiceRepository;
import com.ondemandmonitoring.finance.repository.QuoteRepository;
import com.ondemandmonitoring.finance.mapper.FinanceMapper;
import com.ondemandmonitoring.finance.service.IQuoteService;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.mission.service.IMissionChecklistExecutionService;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import com.ondemandmonitoring.order.dto.response.OrderChecklistItemResponse;
import com.ondemandmonitoring.order.enums.ChecklistReviewStatus;
import com.ondemandmonitoring.order.enums.ChecklistPricingStatus;
import com.ondemandmonitoring.order.enums.OrderStatus;
import com.ondemandmonitoring.order.enums.LegalReviewStatus;
import com.ondemandmonitoring.order.enums.OrderChecklistSourceType;
import com.ondemandmonitoring.order.mapper.OrderChecklistItemMapper;
import com.ondemandmonitoring.order.repository.OrderChecklistItemRepository;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.review.service.IOrderReadinessService;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import com.ondemandmonitoring.pricing.domain.PricingPolicy;
import com.ondemandmonitoring.pricing.domain.ChecklistPricingAudit;
import com.ondemandmonitoring.pricing.dto.*;
import com.ondemandmonitoring.pricing.enums.*;
import com.ondemandmonitoring.pricing.repository.PricingPolicyRepository;
import com.ondemandmonitoring.pricing.repository.ChecklistPricingAuditRepository;
import com.ondemandmonitoring.pricing.service.PricingEngine;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class QuoteService implements IQuoteService {
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(0);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private final OrderRepository orders;
    private final OrderChecklistItemRepository checklistItems;
    private final OrderChecklistItemMapper checklistMapper;
    private final QuoteRepository quotes;
    private final InvoiceRepository invoices;
    private final MissionRepository missions;
    private final IMissionChecklistExecutionService checklistExecutionService;
    private final AuthenticatedUserResolver currentUser;
    private final PaymentProperties paymentProperties;
    private final OrderDeliveryRepository deliveries;
    private final IOrderReadinessService readiness;
    private final PricingEngine pricingEngine;
    private final PricingPolicyRepository pricingPolicies;
    private final ChecklistPricingAuditRepository pricingAudits;

    @Transactional(readOnly = true)
    public PricingReviewResponse getPricingReview(String orderId) {
        Order order = orders.findById(orderId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        List<OrderChecklistItemResponse> items = checklistItems.findAllByOrderIdOrderByDisplayOrderAscIdAsc(orderId)
                .stream().map(checklistMapper::toResponse).toList();
        List<QuoteResponse> history = quotes.findHistory(orderId).stream().map(FinanceMapper::quote).toList();
        long effectiveOrderRev = order.getChecklistRevision() == 0 ? 1 : order.getChecklistRevision();
        QuoteResponse current = history.stream().filter(q -> (q.status() == QuoteStatus.DRAFT
                || q.status() == QuoteStatus.APPROVED || q.status() == QuoteStatus.PENDING_REVIEW) && q.checklistRevision() == effectiveOrderRev).findFirst().orElse(null);
        PricingBreakdown calculation = pricingEngine.calculate(order,
                checklistItems.findAllByOrderIdOrderByDisplayOrderAscIdAsc(orderId));
        Optional<PricingPolicy> policy = pricingPolicies.findById(calculation.policyId());
        // New orders have no package line: the checklists are the whole price.
        BigDecimal basePackagePrice = calculation.items().stream().filter(i -> i.category() == PricingCategory.PACKAGE)
                .map(PricingBreakdownItem::amount).findFirst().orElse(ZERO);
        return new PricingReviewResponse(orderId, order.getOrderCode(), order.getCustomer().getFullName(),
                order.getService().getName(), basePackagePrice, items, current, history, calculation,
                currentUser.getCurrentUserId(),
                policy.map(PricingPolicy::getManagerMaxDiscountPercent).orElse(null),
                policy.map(PricingPolicy::getManagerMaxMarkupPercent).orElse(null),
                effectiveOrderRev);
    }

    @Transactional
    public OrderChecklistItemResponse reviewChecklist(String orderId, String itemId, ChecklistReviewRequest request) {
        Order order = orders.findByIdForUpdate(orderId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        long effectiveOrderRev = order.getChecklistRevision() == 0 ? 1 : order.getChecklistRevision();
        if (request.expectedChecklistRevision() != null && request.expectedChecklistRevision() != effectiveOrderRev && request.expectedChecklistRevision() != order.getChecklistRevision()) {
            throw new ApiException(ErrorCode.STALE_PRICING_REVISION);
        }
        OrderChecklistItem item = checklistItems.findByIdAndOrderId(itemId, orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.CHECKLIST_NOT_FOUND));
        if (item.getChecklistRevision() != effectiveOrderRev) {
            throw new ApiException(ErrorCode.STALE_PRICING_REVISION);
        }
        if (item.getSourceType() == OrderChecklistSourceType.SERVICE_TEMPLATE) {
            throw new ApiException(ErrorCode.ORDER_CHECKLIST_REVIEW_INVALID,
                    "A service-package checklist is included and priced automatically");
        }
        if (request.status() == ChecklistReviewStatus.PENDING) {
            throw new ApiException(ErrorCode.ORDER_CHECKLIST_REVIEW_INVALID, "Manager must choose a final review status");
        }
        if (request.status() == ChecklistReviewStatus.INCLUDED) {
            throw new ApiException(ErrorCode.ORDER_CHECKLIST_REVIEW_INVALID,
                    "Customer-added work cannot be included without a pricing override approval");
        }
        if (request.status() == ChecklistReviewStatus.REJECTED && trimToNull(request.managerNote()) == null) {
            throw new ApiException(ErrorCode.ORDER_CHECKLIST_REVIEW_INVALID,
                    "A rejection reason is required");
        }
        item.setReviewStatus(request.status());
        item.setManagerNote(trimToNull(request.managerNote()));
        if (request.status() == ChecklistReviewStatus.DUPLICATE) {
            item.setPricingStatus(ChecklistPricingStatus.DUPLICATE);
            item.setManagerApprovedAmount(ZERO);
            item.setFinalAmount(ZERO);
        } else if (request.status() == ChecklistReviewStatus.REJECTED) {
            item.setPricingStatus(ChecklistPricingStatus.REJECTED);
            item.setManagerApprovedAmount(ZERO);
            item.setFinalAmount(ZERO);
        } else {
            item.setPricingStatus(ChecklistPricingStatus.NEEDS_MANAGER_REVIEW);
        }
        return checklistMapper.toResponse(item);
    }

    @Transactional
    public QuoteResponse saveDraft(String orderId, QuoteDraftRequest request) {
        Order order = orders.findByIdForUpdate(orderId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if (order.getOrderStatus() == OrderStatus.REJECTED || order.getOrderStatus() == OrderStatus.CANCELLED
                || invoices.findByOrderId(orderId).isPresent()) {
            throw new ApiException(ErrorCode.QUOTE_PRICING_INVALID, "Order can no longer be priced");
        }
        List<OrderChecklistItem> reviewed = checklistItems.findAllByOrderIdOrderByDisplayOrderAscIdAsc(orderId);
        normalizeServiceTemplateReviews(reviewed);
        List<String> pendingReviews = reviewed.stream()
                .filter(item -> item.getSourceType() != OrderChecklistSourceType.SERVICE_TEMPLATE)
                .filter(item -> item.getReviewStatus() == null || item.getReviewStatus() == ChecklistReviewStatus.PENDING)
                .map(OrderChecklistItem::getContent).toList();
        if (!pendingReviews.isEmpty()) {
            throw new ApiException(ErrorCode.ORDER_CHECKLIST_REVIEW_INVALID,
                    "Review every customer-added checklist before saving the quote: " + String.join("; ", pendingReviews));
        }
        validateMoney(request.adjustmentAmount(), "adjustmentAmount", true);
        PricingBreakdown calculation = pricingEngine.calculate(order, reviewed);
        if (!calculation.pendingQuoteItems().isEmpty())
            throw new ApiException(ErrorCode.CUSTOM_WORK_PRICING_INCOMPLETE,
                    "Complete the structured assessment before pricing these custom checklist items: "
                            + String.join("; ", calculation.pendingQuoteItems()));
        // The Manager priced against the policy on screen; if an Admin published a newer one meanwhile, nothing is saved.
        if (request.expectedPolicyVersion() != null && request.expectedPolicyVersion() != calculation.policyVersion())
            throw new ApiException(ErrorCode.STALE_PRICING_POLICY);
        long effectiveOrderRev = order.getChecklistRevision() == 0 ? 1 : order.getChecklistRevision();
        if (request.expectedChecklistRevision() != null && request.expectedChecklistRevision() != effectiveOrderRev && request.expectedChecklistRevision() != order.getChecklistRevision())
            throw new ApiException(ErrorCode.STALE_PRICING_REVISION);
        Map<String, BigDecimal> systemChecklistPrices = checklistSystemPrices(calculation);
        PricingPolicy policy = pricingPolicies.findById(calculation.policyId())
                .orElseThrow(() -> new ApiException(ErrorCode.PRICING_POLICY_NOT_FOUND));
        applyManagerQuotes(reviewed, request.additionalUnitPrices(), systemChecklistPrices,
                trimToNull(request.adjustmentReason()), policy);

        Optional<Quote> active = quotes.findFirstByOrderIdAndStatusInOrderByQuoteVersionDesc(orderId,
                List.of(QuoteStatus.DRAFT, QuoteStatus.PENDING_REVIEW, QuoteStatus.APPROVED));
        if (active.isPresent() && request.expectedPricingRevision() != null
                && request.expectedPricingRevision() != active.get().getPricingRevision())
            throw new ApiException(ErrorCode.STALE_PRICING_REVISION);
        long nextPricingRevision = active.map(Quote::getPricingRevision).orElse(0L) + 1;
        Quote quote;
        if (active.isPresent() && active.get().getStatus() == QuoteStatus.DRAFT && active.get().getChecklistRevision() == effectiveOrderRev) {
            quote = quotes.findByIdForUpdate(active.get().getId()).orElseThrow();
        } else {
            active.ifPresent(previous -> {
                previous.setStatus(QuoteStatus.SUPERSEDED);
                quotes.saveAndFlush(previous);
            });
            quote = new Quote();
            quote.setOrder(order);
            quote.setQuoteVersion(quotes.countByOrderId(orderId) + 1);
            quote.setStatus(QuoteStatus.DRAFT);
        }

        BigDecimal checklistAdjustment = reviewed.stream()
                .filter(item -> item.getReviewStatus() == ChecklistReviewStatus.ADDITIONAL)
                .filter(item -> item.getSourceType() != OrderChecklistSourceType.SERVICE_TEMPLATE)
                .map(item -> item.getManagerQuotedPrice().subtract(systemChecklistPrices.getOrDefault(item.getId(), ZERO)))
                .reduce(ZERO, BigDecimal::add);
        BigDecimal adjustment = vnd(request.adjustmentAmount()).add(checklistAdjustment);
        String reason = trimToNull(request.adjustmentReason());
        if (adjustment.signum() != 0 && (reason == null || reason.length() < 10))
            throw new ApiException(ErrorCode.PRICING_ADJUSTMENT_REASON_REQUIRED);
        List<PricingRiskFlag> risks = evaluateGuardrails(policy, calculation.systemCalculatedAmount(), adjustment,
                calculation.customWork(), orderId);
        List<QuoteItem> lines = buildLines(calculation, vnd(request.adjustmentAmount()), reviewed,
                systemChecklistPrices);
        BigDecimal packagePrice = lines.stream().filter(l -> l.getType() == QuoteItemType.PACKAGE)
                .map(QuoteItem::getAmount).findFirst().orElse(ZERO);
        BigDecimal discount = lines.stream().filter(l -> l.getType() == QuoteItemType.DISCOUNT)
                .map(QuoteItem::getAmount).reduce(ZERO, BigDecimal::add).abs();
        BigDecimal additional = calculation.systemCalculatedAmount().subtract(packagePrice).add(discount);
        BigDecimal total = calculation.systemCalculatedAmount().add(adjustment);

        quote.setPackagePrice(packagePrice);
        quote.setAdditionalAmount(additional);
        quote.setDiscountAmount(discount);
        quote.setAdjustmentAmount(adjustment);
        quote.setManagerAdjustmentAmount(adjustment);
        quote.setSystemCalculatedAmount(calculation.systemCalculatedAmount());
        quote.setPricingPolicyId(calculation.policyId());
        quote.setPricingPolicyVersion(calculation.policyVersion());
        quote.setTotalAmount(total);
        quote.setManagerNote(trimToNull(request.managerNote()));
        quote.setAdjustmentReason(reason);
        quote.setAdjustmentType(adjustment.signum() == 0 ? null
                : Optional.ofNullable(request.adjustmentType()).orElse(PricingAdjustmentType.CORRECTION));
        quote.setPricingRiskFlags(risks);
        quote.setPricingRevision(nextPricingRevision);
        quote.setChecklistRevision(effectiveOrderRev);
        quote.setPricingApprovedBy(null); quote.setPricingApprovedAt(null); quote.setPricingApprovalNote(null);
        if (adjustment.signum() == 0) {
            quote.setPricingApprovalStatus(PricingApprovalStatus.NOT_REQUIRED);
            quote.setAdjustmentRequestedBy(null); quote.setAdjustmentRequestedAt(null);
            quote.setStatus(QuoteStatus.DRAFT);
        } else {
            // A single Manager owns this review. Guardrails above are authoritative; there is no second-manager hop.
            quote.setPricingApprovalStatus(PricingApprovalStatus.APPROVED);
            quote.setAdjustmentRequestedBy(currentUser.getCurrentUser()); quote.setAdjustmentRequestedAt(Instant.now());
            quote.setPricingApprovedBy(currentUser.getCurrentUser()); quote.setPricingApprovedAt(Instant.now());
            quote.setStatus(QuoteStatus.DRAFT);
        }
        quote.replaceItems(lines);
        return FinanceMapper.quote(quotes.saveAndFlush(quote));
    }

    @Transactional
    public QuoteResponse approve(String orderId, String quoteId) {
        Order order = orders.findByIdForUpdate(orderId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        Quote quote = quotes.findByIdForUpdate(quoteId).orElseThrow(() -> new ApiException(ErrorCode.QUOTE_NOT_FOUND));
        if (!quote.getOrder().getId().equals(orderId) || quote.getStatus() != QuoteStatus.DRAFT
                || quote.getChecklistRevision() != (order.getChecklistRevision() == 0 ? 1 : order.getChecklistRevision())) {
            throw new ApiException(ErrorCode.QUOTE_NOT_CURRENT);
        }
        if (quote.getPricingApprovalStatus() != PricingApprovalStatus.NOT_REQUIRED
                && quote.getPricingApprovalStatus() != PricingApprovalStatus.APPROVED)
            throw new ApiException(ErrorCode.QUOTE_HAS_UNAPPROVED_PRICING);
        List<OrderChecklistItem> reviewed = checklistItems.findAllByOrderIdOrderByDisplayOrderAscIdAsc(orderId);
        normalizeServiceTemplateReviews(reviewed);
        if (reviewed.stream()
                .anyMatch(item -> item.getReviewStatus() == null || item.getReviewStatus() == ChecklistReviewStatus.PENDING)) {
            throw new ApiException(ErrorCode.ORDER_CHECKLIST_REVIEW_INVALID);
        }
        // Approval criteria: automatic checks + the manager's own answers.
        readiness.requireApprovable(orderId);
        quote.setStatus(QuoteStatus.APPROVED);
        quote.setApprovedBy(currentUser.getCurrentUser());
        quote.setApprovedAt(Instant.now());
        writeChecklistPricingAudits(quote, reviewed);
        reviewed.stream().filter(this::requiresManagerPrice).forEach(item -> {
            BigDecimal system = Optional.ofNullable(item.getSystemCalculatedPrice()).orElse(ZERO);
            BigDecimal approved = Optional.ofNullable(item.getManagerQuotedPrice()).orElse(system);
            item.setPricingStatus(approved.compareTo(system) == 0
                    ? ChecklistPricingStatus.APPROVED : ChecklistPricingStatus.ADJUSTED_AND_APPROVED);
        });
        if (order.getOrderStatus() == OrderStatus.PENDING) order.setOrderStatus(OrderStatus.APPROVED);
        return FinanceMapper.quote(quote);
    }

    @Transactional
    public QuoteResponse decidePricingAdjustment(String orderId, String quoteId, PricingApprovalRequest request) {
        Order order = orders.findByIdForUpdate(orderId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        Quote quote = quotes.findByIdForUpdate(quoteId).orElseThrow(() -> new ApiException(ErrorCode.QUOTE_NOT_FOUND));
        if (!quote.getOrder().getId().equals(orderId) || quote.getStatus() != QuoteStatus.PENDING_REVIEW
                || quote.getPricingApprovalStatus() != PricingApprovalStatus.PENDING_SECOND_MANAGER
                || quote.getChecklistRevision() != (order.getChecklistRevision() == 0 ? 1 : order.getChecklistRevision()))
            throw new ApiException(ErrorCode.QUOTE_NOT_CURRENT);
        if (request.pricingRevision() != quote.getPricingRevision())
            throw new ApiException(ErrorCode.STALE_PRICING_REVISION);
        User approver = currentUser.getCurrentUser();
        if (quote.getAdjustmentRequestedBy() != null
                && Objects.equals(quote.getAdjustmentRequestedBy().getId(), approver.getId()))
            throw new ApiException(ErrorCode.PRICING_SELF_APPROVAL_NOT_ALLOWED);
        quote.setPricingApprovedBy(approver); quote.setPricingApprovedAt(Instant.now());
        quote.setPricingApprovalNote(trimToNull(request.note()));
        if (!request.approved()) {
            quote.setPricingApprovalStatus(PricingApprovalStatus.REJECTED);
            quote.setStatus(QuoteStatus.REJECTED);
            return FinanceMapper.quote(quote);
        }
        readiness.requireApprovable(orderId);
        quote.setPricingApprovalStatus(PricingApprovalStatus.APPROVED);
        quote.setStatus(QuoteStatus.APPROVED); quote.setApprovedBy(approver); quote.setApprovedAt(Instant.now());
        if (order.getOrderStatus() == OrderStatus.PENDING) order.setOrderStatus(OrderStatus.APPROVED);
        return FinanceMapper.quote(quote);
    }

    @Transactional(readOnly = true)
    public CustomerQuoteResponse getCurrentForCustomer(String orderId) {
        assertOwner(orderId);
        return quotes.findFirstByOrderIdAndStatusInOrderByQuoteVersionDesc(orderId,
                        List.of(QuoteStatus.APPROVED, QuoteStatus.ACCEPTED_BY_CUSTOMER))
                .filter(q -> q.getStatus() == QuoteStatus.ACCEPTED_BY_CUSTOMER
                        || (orderForCustomerQuote(orderId).getOrderStatus() == OrderStatus.APPROVED
                            && q.getChecklistRevision() == effectiveRevForOrder(orderId)))
                .map(FinanceMapper::customerQuote).orElseThrow(() -> new ApiException(ErrorCode.QUOTE_NOT_FOUND));
    }

    @Transactional
    public InvoiceResponse accept(String orderId, String quoteId) {
        Order order = orders.findByIdForUpdate(orderId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        assertOwner(order);
        requireLegalReady(order);
        Quote quote = quotes.findByIdForUpdate(quoteId).orElseThrow(() -> new ApiException(ErrorCode.QUOTE_NOT_FOUND));
        if (!quote.getOrder().getId().equals(orderId)) throw new ApiException(ErrorCode.QUOTE_NOT_CURRENT);
        if (quote.getStatus() == QuoteStatus.ACCEPTED_BY_CUSTOMER) {
            return invoices.findByQuoteId(quoteId).map(this::invoiceResponse)
                    .orElseThrow(() -> new ApiException(ErrorCode.INVOICE_NOT_FOUND));
        }
        Quote current = quotes.findFirstByOrderIdAndStatusInOrderByQuoteVersionDesc(orderId, List.of(QuoteStatus.APPROVED))
                .orElseThrow(() -> new ApiException(ErrorCode.QUOTE_NOT_APPROVED));
        if (!current.getId().equals(quoteId)
                || current.getChecklistRevision() != (order.getChecklistRevision() == 0 ? 1 : order.getChecklistRevision()))
            throw new ApiException(ErrorCode.QUOTE_NOT_CURRENT);

        quote.setStatus(QuoteStatus.ACCEPTED_BY_CUSTOMER);
        quote.setAcceptedAt(Instant.now());
        Invoice invoice = new Invoice();
        invoice.setInvoiceNumber("INV-" + LocalDate.now(BUSINESS_ZONE).getYear() + "-"
                + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        invoice.setOrder(order);
        invoice.setQuote(quote);
        invoice.setTotalAmount(quote.getTotalAmount());
        invoice.setDepositAmount(quote.getTotalAmount().multiply(paymentProperties.depositRate()).setScale(0, RoundingMode.HALF_UP));
        invoice.setPaidAmount(ZERO);
        invoice.setRemainingAmount(quote.getTotalAmount());
        invoice.setStatus(InvoiceStatus.ISSUED);
        invoice.setIssuedAt(Instant.now());
        Invoice saved = invoices.save(invoice);
        ensureWaitingDepositMission(order);
        return invoiceResponse(saved);
    }

    private static void requireLegalReady(Order order) {
        // PENDING_PERMIT: the manager may proceed on experience while the permit is verified outside the system.
        if (order.getLegalReviewStatus() != LegalReviewStatus.NOT_REQUIRED
                && order.getLegalReviewStatus() != LegalReviewStatus.APPROVED
                && order.getLegalReviewStatus() != LegalReviewStatus.PENDING_PERMIT) {
            throw new ApiException(ErrorCode.ORDER_LEGAL_REVIEW_NOT_READY);
        }
    }

    @Transactional(readOnly = true)
    public InvoiceResponse getInvoiceByOrder(String orderId) {
        assertOwnerOrManager(orderId);
        return invoices.findByOrderId(orderId).map(this::invoiceResponse)
                .orElseThrow(() -> new ApiException(ErrorCode.INVOICE_NOT_FOUND));
    }

    private InvoiceResponse invoiceResponse(Invoice invoice) {
        boolean finalPaymentAllowed = deliveries.findByOrderId(invoice.getOrder().getId())
                .map(delivery -> delivery.getStatus() == DeliveryStatus.FINAL_PAYMENT_PENDING
                        && delivery.getCustomerAcceptedAt() != null)
                .orElse(false);
        return FinanceMapper.invoice(invoice, finalPaymentAllowed);
    }

    /**
     * The Manager's price for checklists the pricing policy cannot price (custom checklists with no CUSTOM profile rule).
     * Keys are order checklist item ids; entries for items that are priced by the policy are ignored, so a client can never
     * overwrite a policy price.
     */
    private void applyManagerQuotes(List<OrderChecklistItem> items, Map<String, BigDecimal> quotes,
            Map<String, BigDecimal> systemPrices, String adjustmentReason, PricingPolicy policy) {
        Map<String, BigDecimal> submitted = quotes == null ? Map.of() : quotes;
        List<String> missing = new ArrayList<>();
        for (OrderChecklistItem item : items) {
            if (!requiresManagerPrice(item)) continue;
            BigDecimal system = systemPrices.getOrDefault(item.getId(), ZERO);
            // Keep the stored system price on the policy in force: it is what the audit compares the Manager's price with.
            item.setSystemCalculatedPrice(system);
            BigDecimal quoted = submitted.get(item.getId());
            if (quoted == null) quoted = item.getManagerQuotedPrice();
            if (quoted == null) {
                missing.add(item.getContent());
                continue;
            }
            validateMoney(quoted, "additionalUnitPrices", false);
            BigDecimal approved = quoted.setScale(0, RoundingMode.HALF_UP);
            if (approved.compareTo(system) != 0 && (adjustmentReason == null || adjustmentReason.length() < 10))
                throw new ApiException(ErrorCode.PRICING_ADJUSTMENT_REASON_REQUIRED);
            requireWithinItemLimits(item, system, approved, policy);
            item.setManagerQuotedPrice(approved);
            item.setManagerApprovedAmount(approved);
            item.setFinalAmount(approved);
            BigDecimal difference = approved.subtract(system);
            item.setPriceDifferenceAmount(difference);
            item.setPriceDifferencePercent(differencePercent(difference, system));
            item.setManagerPriceAdjustmentReason(approved.compareTo(system) == 0 ? null : adjustmentReason);
        }
        if (!missing.isEmpty()) throw new ApiException(ErrorCode.CUSTOM_WORK_PRICING_INCOMPLETE,
                "Enter an approved price for: " + String.join("; ", missing));
    }

    /**
     * The Manager's room to move is per checklist, not only across the whole order: one item cannot be marked down (or up)
     * past the policy limit just because the other items in the order are large.
     */
    private void requireWithinItemLimits(OrderChecklistItem item, BigDecimal system, BigDecimal approved, PricingPolicy policy) {
        if (system.signum() <= 0 || approved.compareTo(system) == 0) return;
        BigDecimal lowest = system.multiply(BigDecimal.valueOf(100).subtract(policy.getManagerMaxDiscountPercent()))
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.CEILING);
        BigDecimal highest = system.multiply(BigDecimal.valueOf(100).add(policy.getManagerMaxMarkupPercent()))
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.FLOOR);
        if (approved.compareTo(lowest) < 0 || approved.compareTo(highest) > 0)
            throw new ApiException(ErrorCode.PRICING_OVERRIDE_EXCEEDS_LIMIT,
                    "Giá duyệt của \"" + shortenDescription(item.getContent()) + "\" phải từ " + lowest.toPlainString()
                            + " đến " + highest.toPlainString() + " VND (giá hệ thống " + system.toPlainString()
                            + " VND, giảm tối đa " + policy.getManagerMaxDiscountPercent().stripTrailingZeros().toPlainString()
                            + "%, tăng tối đa " + policy.getManagerMaxMarkupPercent().stripTrailingZeros().toPlainString() + "%).");
    }

    private Map<String, BigDecimal> checklistSystemPrices(PricingBreakdown calculation) {
        Map<String, BigDecimal> totals = new HashMap<>();
        for (PricingBreakdownItem line : calculation.items()) {
            if (line.orderChecklistItemId() != null)
                totals.merge(line.orderChecklistItemId(), line.amount(), BigDecimal::add);
        }
        return totals;
    }

    private void writeChecklistPricingAudits(Quote quote, List<OrderChecklistItem> items) {
        User reviewer = currentUser.getCurrentUser();
        Instant reviewedAt = Instant.now();
        List<ChecklistPricingAudit> audits = items.stream()
                .filter(this::requiresManagerPrice)
                .map(item -> {
                    ChecklistPricingAudit audit = new ChecklistPricingAudit();
                    BigDecimal system = Optional.ofNullable(item.getSystemCalculatedPrice()).orElse(ZERO);
                    BigDecimal approved = Optional.ofNullable(item.getManagerQuotedPrice()).orElse(system);
                    audit.setQuote(quote);
                    audit.setOrderId(quote.getOrder().getId());
                    audit.setOrderChecklistItem(item);
                    audit.setSystemAmount(system);
                    audit.setApprovedAmount(approved);
                    audit.setFinalAmount(approved);
                    audit.setDifferenceAmount(approved.subtract(system));
                    audit.setDifferencePercent(differencePercent(approved.subtract(system), system));
                    audit.setAdjustmentReason(item.getManagerPriceAdjustmentReason());
                    audit.setPricingPolicyId(quote.getPricingPolicyId());
                    audit.setPricingPolicyVersion(quote.getPricingPolicyVersion());
                    audit.setReviewedBy(reviewer);
                    audit.setReviewedAt(reviewedAt);
                    audit.setAction(approved.compareTo(system) == 0
                            ? ChecklistPricingAuditAction.SYSTEM_PRICE_APPROVED
                            : ChecklistPricingAuditAction.PRICE_ADJUSTED_AND_APPROVED);
                    return audit;
                }).toList();
        pricingAudits.saveAll(audits);
    }

    private boolean requiresManagerPrice(OrderChecklistItem item) {
        return item.getSourceType() != OrderChecklistSourceType.SERVICE_TEMPLATE
                && item.getReviewStatus() != ChecklistReviewStatus.REJECTED
                && item.getReviewStatus() != ChecklistReviewStatus.DUPLICATE
                && (item.isManagerQuoteRequired()
                    || item.getReviewStatus() == ChecklistReviewStatus.ADDITIONAL);
    }

    private BigDecimal differencePercent(BigDecimal difference, BigDecimal system) {
        if (system == null || system.signum() == 0) return null;
        return difference.multiply(new BigDecimal("100")).divide(system, 6, RoundingMode.HALF_UP);
    }

    /** Backward-compatible repair for snapshots created before package checklists became auto-included. */
    private void normalizeServiceTemplateReviews(List<OrderChecklistItem> items) {
        items.stream()
                .filter(item -> item.getSourceType() == OrderChecklistSourceType.SERVICE_TEMPLATE)
                .filter(item -> item.getReviewStatus() == null || item.getReviewStatus() == ChecklistReviewStatus.PENDING)
                .forEach(item -> item.setReviewStatus(ChecklistReviewStatus.INCLUDED));
    }

    private List<QuoteItem> buildLines(PricingBreakdown calculation, BigDecimal orderAdjustment,
            List<OrderChecklistItem> checklistRows, Map<String, BigDecimal> systemChecklistPrices) {
        List<QuoteItem> lines = new ArrayList<>();
        Map<String, OrderChecklistItem> itemsById = checklistRows.stream()
                .collect(java.util.stream.Collectors.toMap(OrderChecklistItem::getId, item -> item));
        for (PricingBreakdownItem item : calculation.items()) {
            QuoteItem line = line(QuoteItemType.valueOf(item.category().name()), item.description(), item.unitPrice(),
                    itemsById.get(item.orderChecklistItemId()), lines.size());
            line.setQuantity(item.quantity()); line.setAmount(item.amount()); lines.add(line);
        }
        checklistRows.stream()
                .filter(item -> item.getSourceType() != OrderChecklistSourceType.SERVICE_TEMPLATE)
                .filter(item -> item.getReviewStatus() == ChecklistReviewStatus.ADDITIONAL)
                .filter(item -> item.getManagerQuotedPrice() != null)
                .forEach(item -> {
                    BigDecimal difference = item.getManagerQuotedPrice()
                            .subtract(systemChecklistPrices.getOrDefault(item.getId(), ZERO));
                    if (difference.signum() != 0) lines.add(line(QuoteItemType.MANAGER_ADJUSTMENT,
                            "Manager price adjustment · " + shortenDescription(item.getContent()), difference,
                            item, lines.size()));
                });
        if (orderAdjustment.signum() != 0)
            lines.add(line(QuoteItemType.MANAGER_ADJUSTMENT, "Approved order adjustment", orderAdjustment, null,
                    lines.size()));
        return lines;
    }

    private String shortenDescription(String value) {
        if (value == null) return "";
        return value.length() <= 440 ? value : value.substring(0, 437) + "...";
    }

    private List<PricingRiskFlag> evaluateGuardrails(PricingPolicy policy, BigDecimal system, BigDecimal adjustment,
                                                      boolean customWork, String orderId) {
        List<PricingRiskFlag> flags = new ArrayList<>();
        BigDecimal finalAmount = system.add(adjustment);
        if (adjustment.signum() == 0) return flags;
        if (finalAmount.signum() == 0) {
            flags.add(PricingRiskFlag.ZERO_PRICE);
            throw new ApiException(ErrorCode.ZERO_PRICE_NOT_ALLOWED);
        }
        BigDecimal percent = adjustment.abs().multiply(new BigDecimal("100"))
                .divide(system.max(BigDecimal.ONE), 4, RoundingMode.HALF_UP);
        if (adjustment.signum() < 0) {
            if (percent.compareTo(policy.getHighRiskDiscountPercent()) >= 0) flags.add(PricingRiskFlag.HIGH_DISCOUNT);
            if (percent.compareTo(policy.getManagerMaxDiscountPercent()) > 0)
                throw new ApiException(ErrorCode.PRICING_OVERRIDE_EXCEEDS_LIMIT);
        } else if (percent.compareTo(policy.getManagerMaxMarkupPercent()) > 0) {
            flags.add(PricingRiskFlag.LARGE_MARKUP);
            throw new ApiException(ErrorCode.PRICING_OVERRIDE_EXCEEDS_LIMIT);
        }
        BigDecimal floor = system.multiply(policy.getMinimumPriceRatio()).setScale(0, RoundingMode.CEILING)
                .max(policy.getMinimumPrice());
        if (finalAmount.compareTo(floor) < 0) { flags.add(PricingRiskFlag.BELOW_MINIMUM_PRICE); throw new ApiException(ErrorCode.PRICE_BELOW_ALLOWED_MINIMUM); }
        if (customWork) flags.add(PricingRiskFlag.CUSTOM_WORK_OVERRIDE);
        if (quotes.countByOrderId(orderId) > 1) flags.add(PricingRiskFlag.REPEATED_OVERRIDE);
        return flags;
    }

    private QuoteItem line(QuoteItemType type, String description, BigDecimal unitPrice,
                           OrderChecklistItem checklistItem, int displayOrder) {
        QuoteItem line = new QuoteItem();
        line.setType(type); line.setDescription(description); line.setQuantity(BigDecimal.ONE);
        line.setUnitPrice(vnd(unitPrice)); line.setAmount(vnd(unitPrice));
        line.setOrderChecklistItem(checklistItem); line.setDisplayOrder(displayOrder);
        return line;
    }

    private void ensureWaitingDepositMission(Order order) {
        // Quote acceptance creates the invoice, not a hidden unassigned Mission.
        // Manager planning remains the only creator/materializer in the 1:N model.
        for (String id : missions.findIdsByOrderIdOrderById(order.getId()).stream().sorted().toList()) {
            Mission mission = missions.findByIdForUpdate(id)
                    .orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));
            if (mission.getStatus() == MissionStatus.CANCELLED) continue;
            if (mission.getStatus() == MissionStatus.PLANNING) {
                mission.setStatus(MissionStatus.WAITING_DEPOSIT);
            } else if (mission.getStatus() != MissionStatus.WAITING_DEPOSIT) {
                throw new ApiException(ErrorCode.MISSION_STATUS_INVALID, "Existing mission has already entered execution");
            }
        }
    }

    private Order orderForCustomerQuote(String orderId) {
        return orders.findById(orderId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private long effectiveRevForOrder(String orderId) {
        Order order = orderForCustomerQuote(orderId);
        return order.getChecklistRevision() == 0 ? 1 : order.getChecklistRevision();
    }

    private void assertOwner(String orderId) {
        assertOwner(orders.findById(orderId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND)));
    }

    private void assertOwner(Order order) {
        User user = currentUser.getCurrentUser();
        if (order.getCustomer() == null || !order.getCustomer().getId().equals(user.getId())) throw new ApiException(ErrorCode.ACCESS_DENIED);
    }

    private void assertOwnerOrManager(String orderId) {
        Order order = orders.findById(orderId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        User user = currentUser.getCurrentUser();
        boolean manager = user.getRole() != null && (user.getRole().getCode().name().equals("MANAGER") || user.getRole().getCode().name().equals("ADMIN"));
        if (!manager) assertOwner(order);
    }

    private void validateMoney(BigDecimal value, String field, boolean signed) {
        if (value == null || (!signed && value.signum() < 0) || value.stripTrailingZeros().scale() > 0) {
            throw new ApiException(ErrorCode.QUOTE_PRICING_INVALID, field + " must be an integer VND amount" );
        }
    }

    private BigDecimal vnd(BigDecimal value) { return value.setScale(0, RoundingMode.UNNECESSARY); }
    private String trimToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
