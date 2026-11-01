package com.ondemandmonitoring.finance.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.delivery.repository.OrderDeliveryRepository;
import com.ondemandmonitoring.finance.config.PaymentProperties;
import com.ondemandmonitoring.finance.domain.*;
import com.ondemandmonitoring.finance.dto.QuoteDraftRequest;
import com.ondemandmonitoring.finance.enums.*;
import com.ondemandmonitoring.finance.repository.*;
import com.ondemandmonitoring.finance.service.impl.QuoteService;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.mission.service.IMissionChecklistExecutionService;
import com.ondemandmonitoring.order.domain.*;
import com.ondemandmonitoring.order.enums.*;
import com.ondemandmonitoring.order.mapper.OrderChecklistItemMapper;
import com.ondemandmonitoring.order.repository.*;
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
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuoteServiceTest {
    OrderRepository orders = mock(OrderRepository.class);
    OrderChecklistItemRepository items = mock(OrderChecklistItemRepository.class);
    QuoteRepository quotes = mock(QuoteRepository.class);
    InvoiceRepository invoices = mock(InvoiceRepository.class);
    MissionRepository missions = mock(MissionRepository.class);
    AuthenticatedUserResolver users = mock(AuthenticatedUserResolver.class);
    IMissionChecklistExecutionService executions = mock(IMissionChecklistExecutionService.class);
    OrderDeliveryRepository deliveries = mock(OrderDeliveryRepository.class);
    PricingEngine pricingEngine = mock(PricingEngine.class);
    PricingPolicyRepository pricingPolicies = mock(PricingPolicyRepository.class);
    ChecklistPricingAuditRepository pricingAudits = mock(ChecklistPricingAuditRepository.class);
    IOrderReadinessService readiness = mock(IOrderReadinessService.class);
    QuoteService service;
    Order order;

    @BeforeEach void setUp() {
        service = new QuoteService(orders, items, mock(OrderChecklistItemMapper.class), quotes, invoices, missions,
                executions, users, new PaymentProperties(new BigDecimal("0.30")), deliveries,
                readiness, pricingEngine, pricingPolicies, pricingAudits);
        order = new Order(); order.setId("o1"); order.setLegalReviewStatus(com.ondemandmonitoring.order.enums.LegalReviewStatus.NOT_REQUIRED); order.setOrderStatus(OrderStatus.PENDING);
        var catalog = new com.ondemandmonitoring.service.domain.Service(); catalog.setName("Building inspection");
        order.setService(catalog);
        when(orders.findByIdForUpdate("o1")).thenReturn(Optional.of(order));
        when(invoices.findByOrderId("o1")).thenReturn(Optional.empty());
        when(quotes.findFirstByOrderIdAndStatusInOrderByQuoteVersionDesc(eq("o1"), any())).thenReturn(Optional.empty());
        when(quotes.countByOrderId("o1")).thenReturn(0);
        when(quotes.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        var policy = new PricingPolicy(); policy.setId("p1"); policy.setMinimumPriceRatio(new BigDecimal("0.75"));
        policy.setMinimumPrice(new BigDecimal("100000")); policy.setHighRiskDiscountPercent(new BigDecimal("10"));
        policy.setManagerMaxDiscountPercent(new BigDecimal("25")); policy.setManagerMaxMarkupPercent(new BigDecimal("25"));
        when(pricingPolicies.findById("p1")).thenReturn(Optional.of(policy));
        when(pricingEngine.calculate(eq(order), anyList())).thenReturn(breakdown("5000000", List.of(
                new PricingBreakdownItem(PricingCategory.PACKAGE, "Building inspection", BigDecimal.ONE,
                        new BigDecimal("5000000"), new BigDecimal("5000000")))));
    }

    @Test void includedDuplicateAndRejectedItemsContributeZero() {
        when(items.findAllByOrderIdOrderByDisplayOrderAscIdAsc("o1")).thenReturn(List.of(
                item("included", ChecklistReviewStatus.INCLUDED), item("duplicate", ChecklistReviewStatus.DUPLICATE),
                item("rejected", ChecklistReviewStatus.REJECTED)));
        var result = service.saveDraft("o1", request("5000000", "200000", "0", Map.of()));
        assertThat(result.additionalAmount()).isEqualByComparingTo("0");
        assertThat(result.totalAmount()).isEqualByComparingTo("5000000");
        assertThat(result.items()).allMatch(line -> line.type() == QuoteItemType.PACKAGE);
    }

    @Test void draftStoresAnImmutableGuidanceSnapshotAndAllowsIncompleteGuidance() {
        var custom = item("custom", ChecklistReviewStatus.INCLUDED);
        custom.setSourceType(OrderChecklistSourceType.CUSTOMER_CUSTOM);
        custom.setCustomGuidance(ChecklistGuidanceContent.normalize("Draft", List.of("Inspect"), List.of(), List.of()));
        custom.setGuidanceVersion(3);
        when(items.findAllByOrderIdOrderByDisplayOrderAscIdAsc("o1")).thenReturn(List.of(custom));
        var response = service.saveDraft("o1", request("5000000", "0", "0", Map.of()));
        var snapshot = response.checklistGuidanceSnapshot();
        assertThat(snapshot.schemaVersion()).isEqualTo(1);
        assertThat(snapshot.items()).singleElement().satisfies(entry -> {
            assertThat(entry.itemId()).isEqualTo("custom");
            assertThat(entry.guidanceVersion()).isEqualTo(3);
            assertThat(entry.executionSteps()).containsExactly("Inspect");
            assertThat(entry.inputRequirements()).isEmpty();
        });
        custom.setCustomGuidance(ChecklistGuidanceContent.normalize("Changed", List.of("Other"), List.of(), List.of()));
        assertThat(snapshot.items().getFirst().description()).isEqualTo("Draft");
    }

    @Test void pricingReviewShowsNoPackagePriceWhenTheEngineProducedNoPackageLine() {
        User customer = new User(); customer.setFullName("Customer"); order.setCustomer(customer);
        order.getService().setBasePrice(new BigDecimal("9900000"));
        when(orders.findById("o1")).thenReturn(Optional.of(order));
        when(items.findAllByOrderIdOrderByDisplayOrderAscIdAsc("o1")).thenReturn(List.of());
        when(quotes.findHistory("o1")).thenReturn(List.of());
        when(pricingEngine.calculate(eq(order), anyList())).thenReturn(breakdown("0", List.of()));
        assertThat(service.getPricingReview("o1").basePackagePrice()).isZero();
    }

    @Test void pricingReviewUsesOrderPriceSnapshotInsteadOfCurrentCatalogPrice() {
        User customer = new User(); customer.setFullName("Customer"); order.setCustomer(customer);
        order.setServiceBasePriceSnapshot(new BigDecimal("2800000"));
        order.getService().setBasePrice(new BigDecimal("9900000"));
        when(orders.findById("o1")).thenReturn(Optional.of(order));
        when(items.findAllByOrderIdOrderByDisplayOrderAscIdAsc("o1")).thenReturn(List.of());
        when(quotes.findHistory("o1")).thenReturn(List.of());
        when(pricingEngine.calculate(eq(order), anyList())).thenReturn(breakdown("2800000", List.of(
                new PricingBreakdownItem(PricingCategory.PACKAGE, "Package", BigDecimal.ONE,
                        new BigDecimal("2800000"), new BigDecimal("2800000")))));

        assertThat(service.getPricingReview("o1").basePackagePrice())
                .isEqualByComparingTo("2800000");
    }

    @Test void additionalItemContributesAndBackendCalculatesTotal() {
        when(items.findAllByOrderIdOrderByDisplayOrderAscIdAsc("o1"))
                .thenReturn(List.of(item("thermal", ChecklistReviewStatus.ADDITIONAL)));
        when(pricingEngine.calculate(eq(order), anyList())).thenReturn(breakdown("6500000", List.of(
                new PricingBreakdownItem(PricingCategory.PACKAGE, "Package", BigDecimal.ONE, new BigDecimal("5000000"), new BigDecimal("5000000")),
                new PricingBreakdownItem(PricingCategory.OUTPUT, "Thermal", BigDecimal.ONE, new BigDecimal("1500000"),
                        new BigDecimal("1500000"), "thermal"))));
        var result = service.saveDraft("o1", request("1", "200000", "0", Map.of("thermal", new BigDecimal("1500000"))));
        assertThat(result.additionalAmount()).isEqualByComparingTo("1500000");
        assertThat(result.totalAmount()).isEqualByComparingTo("6500000");
    }

    @Test void anItemCannotBeMarkedDownPastTheLimitEvenWhenTheWholeOrderStaysWithinIt() {
        var custom = item("custom", ChecklistReviewStatus.ADDITIONAL); custom.setManagerQuoteRequired(true);
        when(items.findAllByOrderIdOrderByDisplayOrderAscIdAsc("o1")).thenReturn(List.of(custom));
        when(pricingEngine.calculate(eq(order), anyList())).thenReturn(new PricingBreakdown("p1", 1, "VND", new BigDecimal("1000000"), false, true,
                List.of(new PricingBreakdownItem(PricingCategory.PACKAGE, "Package", BigDecimal.ONE,
                                new BigDecimal("700000"), new BigDecimal("700000")),
                        new PricingBreakdownItem(PricingCategory.RESOURCE, "Custom", BigDecimal.ONE,
                                new BigDecimal("300000"), new BigDecimal("300000"), "custom"))));
        // 300000 -> 150000 is -50% on the item but only -15% on the order, which the order-level guard alone would let through.
        var request = new QuoteDraftRequest(BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, null, Map.of("custom", new BigDecimal("150000")),
                "Manager lowered this price a lot", PricingAdjustmentType.CORRECTION, null, null);
        assertThatThrownBy(() -> service.saveDraft("o1", request))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PRICING_OVERRIDE_EXCEEDS_LIMIT));
        assertThat(custom.getManagerQuotedPrice()).isNull();
    }

    @Test void managerPriceIsAppliedOnlyToChecklistsThePolicyCouldNotPrice() {
        var custom = item("custom", ChecklistReviewStatus.ADDITIONAL); custom.setManagerQuoteRequired(true);
        var catalog = item("catalog", ChecklistReviewStatus.INCLUDED);
        when(items.findAllByOrderIdOrderByDisplayOrderAscIdAsc("o1")).thenReturn(List.of(custom, catalog));
        when(pricingEngine.calculate(eq(order), anyList())).thenReturn(breakdown("5030000", List.of(
                new PricingBreakdownItem(PricingCategory.PACKAGE, "Package", BigDecimal.ONE,
                        new BigDecimal("5000000"), new BigDecimal("5000000")),
                new PricingBreakdownItem(PricingCategory.RESOURCE, "Custom", BigDecimal.ONE,
                        new BigDecimal("30000"), new BigDecimal("30000"), "custom"))));
        service.saveDraft("o1", new QuoteDraftRequest(BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, null,
                Map.of("custom", new BigDecimal("36000"), "catalog", new BigDecimal("999")),
                "Manager adjusted this checklist price", PricingAdjustmentType.CORRECTION, null));
        assertThat(custom.getManagerQuotedPrice()).isEqualByComparingTo("36000");
        assertThat(custom.getManagerApprovedAmount()).isEqualByComparingTo("36000");
        assertThat(custom.getFinalAmount()).isEqualByComparingTo("36000");
        assertThat(custom.getPriceDifferenceAmount()).isEqualByComparingTo("6000");
        assertThat(custom.getPriceDifferencePercent()).isEqualByComparingTo("20");
        assertThat(catalog.getManagerQuotedPrice()).as("a policy-priced checklist can never be overwritten by a client").isNull();
    }

    @Test void approvalWritesAnAuditWithServerCalculatedDifferenceAndAction() {
        User manager = user("m1", "Manager A"); when(users.getCurrentUser()).thenReturn(manager);
        var custom = item("custom", ChecklistReviewStatus.ADDITIONAL);
        custom.setSourceType(OrderChecklistSourceType.CUSTOMER_CUSTOM);
        custom.setSystemCalculatedPrice(new BigDecimal("30000"));
        custom.setManagerQuotedPrice(new BigDecimal("45000"));
        custom.setManagerPriceAdjustmentReason("Manager adjusted after reviewing scope");
        when(items.findAllByOrderIdOrderByDisplayOrderAscIdAsc("o1")).thenReturn(List.of(custom));
        Quote quote = approvableQuote();
        prepareGuidance(custom, quote);
        when(quotes.findByIdForUpdate("q1")).thenReturn(Optional.of(quote));

        service.approve("o1", "q1");

        assertThat(custom.getPricingStatus()).isEqualTo(com.ondemandmonitoring.order.enums.ChecklistPricingStatus.ADJUSTED_AND_APPROVED);

        @SuppressWarnings("unchecked")
        var captured = (List<ChecklistPricingAudit>) mockingDetails(pricingAudits).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("saveAll"))
                .findFirst().orElseThrow().getArgument(0);
        assertThat(captured).singleElement().satisfies(audit -> {
            assertThat(audit.getOrderId()).isEqualTo("o1");
            assertThat(audit.getSystemAmount()).isEqualByComparingTo("30000");
            assertThat(audit.getFinalAmount()).isEqualByComparingTo("45000");
            assertThat(audit.getDifferenceAmount()).isEqualByComparingTo("15000");
            assertThat(audit.getDifferencePercent()).isEqualByComparingTo("50");
            assertThat(audit.getAction()).isEqualTo(ChecklistPricingAuditAction.PRICE_ADJUSTED_AND_APPROVED);
        });
    }

    @Test void approvalAuditsAnUnchangedSystemPriceWithoutAReason() {
        when(users.getCurrentUser()).thenReturn(user("m1", "Manager A"));
        var custom = item("custom", ChecklistReviewStatus.ADDITIONAL);
        custom.setSourceType(OrderChecklistSourceType.CUSTOMER_CUSTOM);
        custom.setSystemCalculatedPrice(new BigDecimal("30000"));
        custom.setManagerQuotedPrice(new BigDecimal("30000"));
        when(items.findAllByOrderIdOrderByDisplayOrderAscIdAsc("o1")).thenReturn(List.of(custom));
        Quote quote = approvableQuote();
        prepareGuidance(custom, quote);
        when(quotes.findByIdForUpdate("q1")).thenReturn(Optional.of(quote));

        service.approve("o1", "q1");

        assertThat(custom.getPricingStatus()).isEqualTo(com.ondemandmonitoring.order.enums.ChecklistPricingStatus.APPROVED);

        @SuppressWarnings("unchecked")
        var captured = (List<ChecklistPricingAudit>) mockingDetails(pricingAudits).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("saveAll"))
                .findFirst().orElseThrow().getArgument(0);
        assertThat(captured).singleElement().satisfies(audit -> {
            assertThat(audit.getDifferenceAmount()).isEqualByComparingTo("0");
            assertThat(audit.getAdjustmentReason()).isNull();
            assertThat(audit.getAction()).isEqualTo(ChecklistPricingAuditAction.SYSTEM_PRICE_APPROVED);
        });
    }

    @Test void quoteIsNotSavedWhenTheManagerPricedAgainstAnOlderPolicyVersion() {
        var custom = item("custom", ChecklistReviewStatus.ADDITIONAL); custom.setManagerQuoteRequired(true);
        when(items.findAllByOrderIdOrderByDisplayOrderAscIdAsc("o1")).thenReturn(List.of(custom));
        when(pricingEngine.calculate(eq(order), anyList())).thenReturn(breakdown("5000000", List.of()));
        var stale = new QuoteDraftRequest(BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, null, Map.of("custom", new BigDecimal("45000")),
                null, PricingAdjustmentType.CORRECTION, null, 0);
        assertThatThrownBy(() -> service.saveDraft("o1", stale))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STALE_PRICING_POLICY));
        assertThat(custom.getManagerQuotedPrice()).isNull();
    }

    @Test void quoteCannotBeSavedWhileACustomChecklistAwaitsTheManagersPrice() {
        var custom = item("Check antenna", ChecklistReviewStatus.ADDITIONAL); custom.setManagerQuoteRequired(true);
        when(items.findAllByOrderIdOrderByDisplayOrderAscIdAsc("o1")).thenReturn(List.of(custom));
        when(pricingEngine.calculate(eq(order), anyList())).thenReturn(new PricingBreakdown("p1", 1, "VND", new BigDecimal("5000000"),
                false, true, List.of(), List.of("Check antenna")));
        assertThatThrownBy(() -> service.saveDraft("o1", request("1", "0", "0", Map.of())))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CUSTOM_WORK_PRICING_INCOMPLETE);
                    assertThat(e.getMessage()).contains("Check antenna");
                });
        verify(quotes, never()).saveAndFlush(any());
    }

    @Test void invalidManagerPriceIsRejected() {
        var custom = item("custom", ChecklistReviewStatus.ADDITIONAL); custom.setManagerQuoteRequired(true);
        when(items.findAllByOrderIdOrderByDisplayOrderAscIdAsc("o1")).thenReturn(List.of(custom));
        for (String bad : new String[] {"-1", "10.5"})
            assertThatThrownBy(() -> service.saveDraft("o1", request("1", "0", "0", Map.of("custom", new BigDecimal(bad)))))
                    .isInstanceOf(ApiException.class);
        assertThat(custom.getManagerQuotedPrice()).isNull();
    }

    @Test void rejectsFractionalManagerAdjustment() {
        assertThatThrownBy(() -> service.saveDraft("o1", request("1", "0", "1.5", Map.of())))
                .isInstanceOf(ApiException.class).extracting("errorCode").isEqualTo(ErrorCode.QUOTE_PRICING_INVALID);
    }

    @Test void nonZeroAdjustmentKeepsSystemAmountAndIsApprovedByTheReviewingManager() {
        User manager = user("m1", "Manager A"); when(users.getCurrentUser()).thenReturn(manager);
        var result = service.saveDraft("o1", overrideRequest("-250000", "Shared route reduces travel cost"));
        assertThat(result.systemCalculatedAmount()).isEqualByComparingTo("5000000");
        assertThat(result.managerAdjustmentAmount()).isEqualByComparingTo("-250000");
        assertThat(result.totalAmount()).isEqualByComparingTo("4750000");
        assertThat(result.pricingApprovalStatus()).isEqualTo(PricingApprovalStatus.APPROVED);
        assertThat(result.status()).isEqualTo(QuoteStatus.DRAFT);
    }

    @Test void adjustmentWithoutMeaningfulReasonIsRejected() {
        assertThatThrownBy(() -> service.saveDraft("o1", overrideRequest("-100000", "short")))
                .isInstanceOf(ApiException.class).extracting("errorCode")
                .isEqualTo(ErrorCode.PRICING_ADJUSTMENT_REASON_REQUIRED);
    }

    @Test void requesterCannotApproveOwnAdjustment() {
        User manager = user("m1", "Manager A"); when(users.getCurrentUser()).thenReturn(manager);
        Quote quote = pendingAdjustment(manager); when(quotes.findByIdForUpdate("q1")).thenReturn(Optional.of(quote));
        assertThatThrownBy(() -> service.decidePricingAdjustment("o1", "q1", new com.ondemandmonitoring.finance.dto.PricingApprovalRequest(1L, true, null)))
                .isInstanceOf(ApiException.class).extracting("errorCode")
                .isEqualTo(ErrorCode.PRICING_SELF_APPROVAL_NOT_ALLOWED);
    }

    @Test void anotherManagerCanApproveCurrentRevision() {
        User requester = user("m1", "Manager A"), approver = user("m2", "Manager B");
        when(users.getCurrentUser()).thenReturn(approver);
        Quote quote = pendingAdjustment(requester); when(quotes.findByIdForUpdate("q1")).thenReturn(Optional.of(quote));
        var result = service.decidePricingAdjustment("o1", "q1", new com.ondemandmonitoring.finance.dto.PricingApprovalRequest(1L, true, "Reviewed"));
        assertThat(result.status()).isEqualTo(QuoteStatus.APPROVED);
        assertThat(result.pricingApprovalStatus()).isEqualTo(PricingApprovalStatus.APPROVED);
        assertThat(result.pricingApprovedByName()).isEqualTo("Manager B");
    }

    @Test void stalePricingRevisionCannotBeApproved() {
        User requester = user("m1", "Manager A"); when(users.getCurrentUser()).thenReturn(user("m2", "Manager B"));
        Quote quote = pendingAdjustment(requester); quote.setPricingRevision(2);
        when(quotes.findByIdForUpdate("q1")).thenReturn(Optional.of(quote));
        assertThatThrownBy(() -> service.decidePricingAdjustment("o1", "q1", new com.ondemandmonitoring.finance.dto.PricingApprovalRequest(1L, true, null)))
                .isInstanceOf(ApiException.class).extracting("errorCode").isEqualTo(ErrorCode.STALE_PRICING_REVISION);
    }

    @Test void normalOverrideCannotReachZero() {
        when(users.getCurrentUser()).thenReturn(user("m1", "Manager A"));
        assertThatThrownBy(() -> service.saveDraft("o1", overrideRequest("-5000000", "Attempt to make ordinary work free")))
                .isInstanceOf(ApiException.class).extracting("errorCode")
                .isEqualTo(ErrorCode.ZERO_PRICE_NOT_ALLOWED);
    }

    @Test void customerCannotAcceptUnapprovedQuote() {
        User customer = new User(); customer.setId("u1"); order.setCustomer(customer); when(users.getCurrentUser()).thenReturn(customer);
        Quote quote = new Quote(); quote.setId("q1"); quote.setOrder(order); quote.setStatus(QuoteStatus.DRAFT);
        when(quotes.findByIdForUpdate("q1")).thenReturn(Optional.of(quote));
        assertThatThrownBy(() -> service.accept("o1", "q1"))
                .isInstanceOf(ApiException.class).extracting("errorCode").isEqualTo(ErrorCode.QUOTE_NOT_APPROVED);
    }

    @Test void acceptingApprovedQuoteCreatesThirtyPercentInvoiceWithoutImplicitMission() {
        order.setOrderStatus(OrderStatus.APPROVED);
        User customer = new User(); customer.setId("u1"); order.setCustomer(customer); when(users.getCurrentUser()).thenReturn(customer);
        var preferred = new com.ondemandmonitoring.warehouse.domain.PreferredTime();
        preferred.setStartTime(java.time.LocalTime.of(8, 0)); preferred.setEndTime(java.time.LocalTime.of(10, 0));
        order.setPreferredTime(preferred); order.setPreferredDateFrom(java.time.LocalDate.of(2026, 10, 10));
        Quote quote = new Quote(); quote.setId("q1"); quote.setOrder(order); quote.setQuoteVersion(1);
        quote.setStatus(QuoteStatus.APPROVED); quote.setTotalAmount(new BigDecimal("8500000"));
        when(quotes.findByIdForUpdate("q1")).thenReturn(Optional.of(quote));
        when(quotes.findFirstByOrderIdAndStatusInOrderByQuoteVersionDesc("o1", List.of(QuoteStatus.APPROVED)))
                .thenReturn(Optional.of(quote));
        when(invoices.save(any())).thenAnswer(call -> call.getArgument(0));
        when(missions.findIdsByOrderIdOrderById("o1")).thenReturn(List.of());

        var result = service.accept("o1", "q1");

        assertThat(result.depositAmount()).isEqualByComparingTo("2550000");
        assertThat(result.remainingAmount()).isEqualByComparingTo("8500000");
        assertThat(quote.getStatus()).isEqualTo(QuoteStatus.ACCEPTED_BY_CUSTOMER);
        verify(missions, never()).save(any());
        verifyNoInteractions(executions);
    }

    private void prepareGuidance(OrderChecklistItem item, Quote quote) {
        var content = ChecklistGuidanceContent.normalize(null, List.of("Inspect"), List.of("Access"), List.of("Photos"));
        item.setCustomGuidance(content); item.setGuidanceVersion(1); item.setGuidanceConfirmedVersion(1L);
        quote.setChecklistGuidanceSnapshot(new com.ondemandmonitoring.finance.dto.ChecklistGuidanceSnapshot(1,
                List.of(new com.ondemandmonitoring.finance.dto.ChecklistGuidanceSnapshot.Item(item.getId(), 1, 1,
                        null, content.executionSteps(), content.inputRequirements(), content.expectedOutputs()))));
    }

    private OrderChecklistItem item(String id, ChecklistReviewStatus status) {
        OrderChecklistItem item = new OrderChecklistItem(); item.setId(id); item.setContent(id); item.setReviewStatus(status); return item;
    }
    private QuoteDraftRequest request(String pack, String discount, String adjustment, Map<String, BigDecimal> prices) {
        return new QuoteDraftRequest(new BigDecimal(pack), new BigDecimal(discount), new BigDecimal(adjustment), null, prices);
    }
    private PricingBreakdown breakdown(String total, List<PricingBreakdownItem> lines) {
        return new PricingBreakdown("p1", 1, "VND", new BigDecimal(total), false, true, lines);
    }
    private QuoteDraftRequest overrideRequest(String amount, String reason) {
        return new QuoteDraftRequest(BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal(amount), null, Map.of(),
                reason, PricingAdjustmentType.CORRECTION, null);
    }
    private User user(String id, String name) { User user = new User(); user.setId(id); user.setFullName(name); return user; }
    private Quote pendingAdjustment(User requester) {
        Quote quote = new Quote(); quote.setId("q1"); quote.setOrder(order); quote.setQuoteVersion(1);
        quote.setStatus(QuoteStatus.PENDING_REVIEW); quote.setPricingApprovalStatus(PricingApprovalStatus.PENDING_SECOND_MANAGER);
        quote.setPricingRevision(1); quote.setAdjustmentRequestedBy(requester); quote.setSystemCalculatedAmount(new BigDecimal("5000000"));
        quote.setManagerAdjustmentAmount(new BigDecimal("-250000")); quote.setAdjustmentAmount(new BigDecimal("-250000"));
        quote.setTotalAmount(new BigDecimal("4750000")); quote.setPackagePrice(new BigDecimal("5000000"));
        quote.setAdditionalAmount(BigDecimal.ZERO); quote.setDiscountAmount(BigDecimal.ZERO); quote.setPricingRiskFlags(new ArrayList<>());
        quote.setPricingPolicyId("p1"); quote.setPricingPolicyVersion(1); return quote;
    }
    private Quote approvableQuote() {
        Quote quote = new Quote(); quote.setId("q1"); quote.setOrder(order); quote.setQuoteVersion(1);
        quote.setStatus(QuoteStatus.DRAFT); quote.setPricingApprovalStatus(PricingApprovalStatus.NOT_REQUIRED);
        quote.setPackagePrice(new BigDecimal("5000000")); quote.setAdditionalAmount(new BigDecimal("45000"));
        quote.setDiscountAmount(BigDecimal.ZERO); quote.setAdjustmentAmount(BigDecimal.ZERO);
        quote.setTotalAmount(new BigDecimal("5045000")); quote.setPricingPolicyId("p1");
        quote.setPricingPolicyVersion(3); quote.setSystemCalculatedAmount(new BigDecimal("5030000"));
        quote.setManagerAdjustmentAmount(new BigDecimal("15000"));
        return quote;
    }
}
