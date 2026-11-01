package com.ondemandmonitoring.finance.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.delivery.repository.OrderDeliveryRepository;
import com.ondemandmonitoring.finance.config.PaymentProperties;
import com.ondemandmonitoring.finance.domain.*;
import com.ondemandmonitoring.finance.dto.*;
import com.ondemandmonitoring.finance.enums.*;
import com.ondemandmonitoring.finance.repository.*;
import com.ondemandmonitoring.finance.service.impl.QuoteService;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.mission.service.IMissionChecklistExecutionService;
import com.ondemandmonitoring.order.domain.*;
import com.ondemandmonitoring.order.dto.response.OrderChecklistItemResponse;
import com.ondemandmonitoring.order.enums.*;
import com.ondemandmonitoring.order.mapper.OrderChecklistItemMapper;
import com.ondemandmonitoring.order.repository.*;
import com.ondemandmonitoring.pricing.enums.PricingApprovalStatus;
import com.ondemandmonitoring.pricing.repository.*;
import com.ondemandmonitoring.pricing.service.PricingEngine;
import com.ondemandmonitoring.review.service.IOrderReadinessService;
import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuoteGuidanceServiceTest {
    OrderRepository orders = mock(OrderRepository.class);
    OrderChecklistItemRepository items = mock(OrderChecklistItemRepository.class);
    OrderChecklistItemMapper mapper = mock(OrderChecklistItemMapper.class);
    QuoteRepository quotes = mock(QuoteRepository.class);
    InvoiceRepository invoices = mock(InvoiceRepository.class);
    MissionRepository missions = mock(MissionRepository.class);
    AuthenticatedUserResolver users = mock(AuthenticatedUserResolver.class);
    IOrderReadinessService readiness = mock(IOrderReadinessService.class);
    PricingEngine pricingEngine = mock(PricingEngine.class);
    QuoteService service;
    Order order;
    OrderChecklistItem item;
    User manager;

    @BeforeEach void setup() {
        service = new QuoteService(orders, items, mapper, quotes, invoices, missions,
                mock(IMissionChecklistExecutionService.class), users, new PaymentProperties(new BigDecimal("0.30")),
                mock(OrderDeliveryRepository.class), readiness, pricingEngine,
                mock(PricingPolicyRepository.class), mock(ChecklistPricingAuditRepository.class));
        order = new Order(); order.setId("o1"); order.setChecklistRevision(2); order.setOrderStatus(OrderStatus.APPROVED);
        order.setLegalReviewStatus(LegalReviewStatus.NOT_REQUIRED);
        item = new OrderChecklistItem(); item.setId("i1"); item.setOrder(order); item.setChecklistRevision(2);
        item.setSourceType(OrderChecklistSourceType.CUSTOMER_CUSTOM); item.setReviewStatus(ChecklistReviewStatus.ADDITIONAL);
        manager = new User(); manager.setId("manager"); Role role = new Role(); role.setCode(RoleCode.MANAGER); manager.setRole(role);
        when(users.getCurrentUser()).thenReturn(manager);
        when(orders.findByIdForUpdate("o1")).thenReturn(Optional.of(order));
        when(orders.findById("o1")).thenReturn(Optional.of(order));
        when(items.findByIdAndOrderId("i1", "o1")).thenReturn(Optional.of(item));
        when(items.findAllByOrderIdOrderByDisplayOrderAscIdAsc("o1")).thenReturn(List.of(item));
        when(mapper.toResponse(any())).thenAnswer(call -> new OrderChecklistItemResponse());
    }

    @Test void incompleteDraftIsNormalizedAndAudited() {
        service.saveChecklistGuidance("o1", "i1", request(0, "  notes  ", Arrays.asList(" Inspect ", "", "Inspect", null), false));
        assertThat(item.getGuidanceVersion()).isEqualTo(1);
        assertThat(item.getCustomGuidance().description()).isEqualTo("notes");
        assertThat(item.getCustomGuidance().executionSteps()).containsExactly("Inspect");
        assertThat(item.getCustomGuidance().inputRequirements()).isEmpty();
        assertThat(item.getGuidanceConfirmedVersion()).isNull();
        assertThat(item.getGuidanceUpdatedBy()).isSameAs(manager);
        assertThat(item.getGuidanceUpdatedAt()).isNotNull();
    }

    @Test void confirmMissingSectionsIsRejectedBeforeMutation() {
        expect(ErrorCode.CHECKLIST_GUIDANCE_INCOMPLETE,
                () -> service.saveChecklistGuidance("o1", "i1", request(0, null, List.of("Inspect"), true)));
        assertThat(item.getGuidanceVersion()).isZero();
        assertThat(item.getCustomGuidance()).isNull();
    }

    @Test void completeConfirmationSetsCurrentVersionAndEditableResponse() {
        var response = service.saveChecklistGuidance("o1", "i1", complete(0, true));
        assertThat(item.isGuidanceReady()).isTrue();
        assertThat(item.getGuidanceConfirmedVersion()).isEqualTo(1);
        assertThat(response.isCanEditGuidance()).isTrue();
        assertThat(response.getGuidanceBlockedReason()).isNull();
    }

    @Test void normalizedNoOpPreservesVersionAuditAndQuotes() {
        confirmed(); Instant previous = Instant.parse("2026-01-01T00:00:00Z"); item.setGuidanceUpdatedAt(previous);
        Quote quote = quote(QuoteStatus.APPROVED); active(quote);
        service.saveChecklistGuidance("o1", "i1", complete(1, false));
        assertThat(item.getGuidanceVersion()).isEqualTo(1);
        assertThat(item.getGuidanceConfirmedVersion()).isEqualTo(1);
        assertThat(item.getGuidanceUpdatedAt()).isEqualTo(previous);
        assertThat(quote.getStatus()).isEqualTo(QuoteStatus.APPROVED);
    }

    @Test void noOpCanConfirmWithoutIncrementingVersion() {
        confirmed(); item.setGuidanceConfirmedVersion(null);
        service.saveChecklistGuidance("o1", "i1", complete(1, true));
        assertThat(item.getGuidanceVersion()).isEqualTo(1);
        assertThat(item.isGuidanceReady()).isTrue();
    }

    @Test void editsSupersedeEveryActiveQuoteAndPreserveHistoricalSnapshot() {
        confirmed(); Quote first = quote(QuoteStatus.APPROVED); first.setId("q1");
        Quote second = quote(QuoteStatus.DRAFT); second.setId("q2");
        var snapshot = first.getChecklistGuidanceSnapshot();
        first.setApprovedBy(manager); first.setApprovedAt(Instant.now()); first.setPricingApprovedBy(manager);
        first.setPricingApprovedAt(Instant.now()); first.setPricingApprovalNote("approved");
        when(quotes.findHistory("o1")).thenReturn(List.of(second, first));
        when(quotes.findByIdForUpdate("q1")).thenReturn(Optional.of(first));
        when(quotes.findByIdForUpdate("q2")).thenReturn(Optional.of(second));
        service.saveChecklistGuidance("o1", "i1", request(1, "Changed", List.of("Inspect"), false));
        assertThat(first.getStatus()).isEqualTo(QuoteStatus.SUPERSEDED);
        assertThat(second.getStatus()).isEqualTo(QuoteStatus.SUPERSEDED);
        assertThat(first.getApprovedBy()).isNull(); assertThat(first.getPricingApprovedBy()).isNull();
        assertThat(first.getApprovedAt()).isNull(); assertThat(first.getPricingApprovedAt()).isNull();
        assertThat(first.getPricingApprovalNote()).isNull();
        assertThat(first.getChecklistGuidanceSnapshot()).isEqualTo(snapshot);
        assertThat(item.getGuidanceConfirmedVersion()).isNull();
        var lockOrder = inOrder(orders, quotes, items);
        lockOrder.verify(orders).findByIdForUpdate("o1");
        lockOrder.verify(quotes).findByIdForUpdate("q1"); lockOrder.verify(quotes).findByIdForUpdate("q2");
        lockOrder.verify(items).findByIdAndOrderId("i1", "o1");
    }

    @Test void staleVersionAndRevisionNeverOverwrite() {
        confirmed();
        expect(ErrorCode.STALE_GUIDANCE_VERSION, () -> service.saveChecklistGuidance("o1", "i1", complete(0, true)));
        expect(ErrorCode.STALE_PRICING_REVISION, () -> service.saveChecklistGuidance("o1", "i1",
                new ChecklistGuidanceRequest(1L, 1L, null, List.of(), List.of(), List.of(), false)));
        assertThat(item.getGuidanceVersion()).isEqualTo(1);
    }

    @Test void archivedItemAndForeignItemAreRejected() {
        item.setChecklistRevision(1);
        expect(ErrorCode.STALE_PRICING_REVISION, () -> service.saveChecklistGuidance("o1", "i1", complete(0, false)));
        when(items.findByIdAndOrderId("foreign", "o1")).thenReturn(Optional.empty());
        expect(ErrorCode.CHECKLIST_NOT_FOUND, () -> service.saveChecklistGuidance("o1", "foreign", complete(0, false)));
    }

    @Test void catalogAndUnauthorizedActorCannotEdit() {
        item.setSourceType(OrderChecklistSourceType.CUSTOMER_SELECTED);
        expect(ErrorCode.CHECKLIST_GUIDANCE_INVALID, () -> service.saveChecklistGuidance("o1", "i1", complete(0, false)));
        manager.getRole().setCode(RoleCode.CUSTOMER);
        expect(ErrorCode.ACCESS_DENIED, () -> service.saveChecklistGuidance("o1", "i1", complete(0, false)));
    }

    @Test void adminCanEdit() {
        manager.getRole().setCode(RoleCode.ADMIN);
        service.saveChecklistGuidance("o1", "i1", complete(0, true));
        assertThat(item.isGuidanceReady()).isTrue();
    }

    @Test void invoicedAcceptedTerminalAndExecutingOrdersCannotEdit() {
        for (OrderStatus status : List.of(OrderStatus.REJECTED, OrderStatus.CANCELLED, OrderStatus.COMPLETED, OrderStatus.IN_PROGRESS)) {
            order.setOrderStatus(status);
            expect(ErrorCode.CHECKLIST_GUIDANCE_EDIT_BLOCKED, () -> service.saveChecklistGuidance("o1", "i1", complete(0, false)));
        }
        order.setOrderStatus(OrderStatus.APPROVED);
        when(invoices.findByOrderId("o1")).thenReturn(Optional.of(new Invoice()));
        expect(ErrorCode.CHECKLIST_GUIDANCE_EDIT_BLOCKED, () -> service.saveChecklistGuidance("o1", "i1", complete(0, false)));
        when(invoices.findByOrderId("o1")).thenReturn(Optional.empty());
        when(quotes.findFirstByOrderIdAndStatusInOrderByQuoteVersionDesc("o1", List.of(QuoteStatus.ACCEPTED_BY_CUSTOMER)))
                .thenReturn(Optional.of(quote(QuoteStatus.ACCEPTED_BY_CUSTOMER)));
        expect(ErrorCode.CHECKLIST_GUIDANCE_EDIT_BLOCKED, () -> service.saveChecklistGuidance("o1", "i1", complete(0, false)));
        when(quotes.findFirstByOrderIdAndStatusInOrderByQuoteVersionDesc("o1", List.of(QuoteStatus.ACCEPTED_BY_CUSTOMER)))
                .thenReturn(Optional.empty());
        Mission mission = new Mission(); mission.setStatus(MissionStatus.IN_FLIGHT);
        when(missions.findAllByOrderIdOrderByScheduledStartAtAscIdAsc("o1")).thenReturn(List.of(mission));
        expect(ErrorCode.CHECKLIST_GUIDANCE_EDIT_BLOCKED, () -> service.saveChecklistGuidance("o1", "i1", complete(0, false)));
    }

    @Test void missingAndChangedSnapshotsCannotBeApproved() {
        confirmed(); Quote quote = quote(QuoteStatus.DRAFT); quote.setChecklistGuidanceSnapshot(null); active(quote);
        expect(ErrorCode.QUOTE_GUIDANCE_SNAPSHOT_STALE, () -> service.approve("o1", "q1"));
        quote.setChecklistGuidanceSnapshot(snapshot()); item.setGuidanceVersion(2); item.setGuidanceConfirmedVersion(2L);
        expect(ErrorCode.QUOTE_GUIDANCE_SNAPSHOT_STALE, () -> service.approve("o1", "q1"));
        assertThat(quote.getStatus()).isEqualTo(QuoteStatus.DRAFT);
    }

    @Test void completeButUnconfirmedGuidanceCannotBeApproved() {
        confirmed(); Quote quote = quote(QuoteStatus.DRAFT); active(quote); item.setGuidanceConfirmedVersion(null);
        expect(ErrorCode.CHECKLIST_GUIDANCE_INCOMPLETE, () -> service.approve("o1", "q1"));
        verifyNoInteractions(readiness);
    }

    @Test void rejectedAndDuplicateCustomItemsAreExcluded() {
        for (ChecklistReviewStatus status : List.of(ChecklistReviewStatus.REJECTED, ChecklistReviewStatus.DUPLICATE)) {
            item.setReviewStatus(status); Quote quote = quote(QuoteStatus.DRAFT);
            quote.setChecklistGuidanceSnapshot(new ChecklistGuidanceSnapshot(1, List.of())); active(quote);
            service.approve("o1", "q1");
            assertThat(quote.getStatus()).isEqualTo(QuoteStatus.APPROVED);
        }
    }

    @Test void acceptanceRequiresExactGuidanceContentAndCreatesNoInvoiceOnMismatch() {
        confirmed(); order.setCustomer(manager); Quote quote = quote(QuoteStatus.APPROVED); active(quote);
        when(quotes.findFirstByOrderIdAndStatusInOrderByQuoteVersionDesc("o1", List.of(QuoteStatus.APPROVED)))
                .thenReturn(Optional.of(quote));
        item.setCustomGuidance(ChecklistGuidanceContent.normalize("different", List.of("Inspect"), List.of("Access"), List.of("Photos")));
        expect(ErrorCode.QUOTE_GUIDANCE_SNAPSHOT_STALE, () -> service.accept("o1", "q1"));
        verify(invoices, never()).save(any());
        assertThat(quote.getStatus()).isEqualTo(QuoteStatus.APPROVED);
    }

    @Test void legacyAcceptedQuoteCanStillBeReadWithoutSnapshot() {
        order.setCustomer(manager); Quote quote = quote(QuoteStatus.ACCEPTED_BY_CUSTOMER); quote.setChecklistGuidanceSnapshot(null);
        when(quotes.findFirstByOrderIdAndStatusInOrderByQuoteVersionDesc("o1", List.of(QuoteStatus.APPROVED, QuoteStatus.ACCEPTED_BY_CUSTOMER)))
                .thenReturn(Optional.of(quote));
        assertThat(service.getCurrentForCustomer("o1").checklistGuidanceSnapshot()).isNull();
    }

    @Test void legacyUnacceptedCustomQuoteRequiresRebuild() {
        order.setCustomer(manager); Quote quote = quote(QuoteStatus.APPROVED); quote.setChecklistGuidanceSnapshot(null);
        when(quotes.findFirstByOrderIdAndStatusInOrderByQuoteVersionDesc("o1", List.of(QuoteStatus.APPROVED, QuoteStatus.ACCEPTED_BY_CUSTOMER)))
                .thenReturn(Optional.of(quote));
        expect(ErrorCode.QUOTE_GUIDANCE_SNAPSHOT_STALE, () -> service.getCurrentForCustomer("o1"));
    }

    @Test void reviewCannotAlterAcceptedOrInvoicedScope() {
        var request = new ChecklistReviewRequest(ChecklistReviewStatus.REJECTED, "Not included", 2L);
        when(invoices.findByOrderId("o1")).thenReturn(Optional.of(new Invoice()));
        expect(ErrorCode.CHECKLIST_GUIDANCE_EDIT_BLOCKED, () -> service.reviewChecklist("o1", "i1", request));
        when(invoices.findByOrderId("o1")).thenReturn(Optional.empty());
        when(quotes.findFirstByOrderIdAndStatusInOrderByQuoteVersionDesc("o1", List.of(QuoteStatus.ACCEPTED_BY_CUSTOMER)))
                .thenReturn(Optional.of(quote(QuoteStatus.ACCEPTED_BY_CUSTOMER)));
        expect(ErrorCode.CHECKLIST_GUIDANCE_EDIT_BLOCKED, () -> service.reviewChecklist("o1", "i1", request));
        assertThat(item.getReviewStatus()).isEqualTo(ChecklistReviewStatus.ADDITIONAL);
    }

    @Test void reviewChangeSupersedesQuoteButNormalizedNoOpPreservesIt() {
        Quote quote = quote(QuoteStatus.APPROVED); active(quote);
        service.reviewChecklist("o1", "i1", new ChecklistReviewRequest(ChecklistReviewStatus.ADDITIONAL, "  ", 2L));
        assertThat(quote.getStatus()).isEqualTo(QuoteStatus.APPROVED);
        service.reviewChecklist("o1", "i1", new ChecklistReviewRequest(ChecklistReviewStatus.REJECTED, "Excluded", 2L));
        assertThat(quote.getStatus()).isEqualTo(QuoteStatus.SUPERSEDED);
        assertThat(item.getReviewStatus()).isEqualTo(ChecklistReviewStatus.REJECTED);
    }

    @Test void reviewMetadataChangeAlsoSupersedesQuote() {
        Quote quote = quote(QuoteStatus.APPROVED); active(quote);
        service.reviewChecklist("o1", "i1", new ChecklistReviewRequest(ChecklistReviewStatus.ADDITIONAL, "New review note", 2L));
        assertThat(quote.getStatus()).isEqualTo(QuoteStatus.SUPERSEDED);
    }

    @Test void pricingResponseUsesActorAndOrderStateForGuidancePermissions() {
        order.setCustomer(manager); order.setService(new com.ondemandmonitoring.service.domain.Service());
        when(pricingEngine.calculate(eq(order), anyList())).thenReturn(new com.ondemandmonitoring.pricing.dto.PricingBreakdown(
                "policy", 1, "VND", BigDecimal.TEN, false, true, List.of()));
        assertThat(service.getPricingReview("o1").checklistItems()).singleElement()
                .satisfies(response -> assertThat(response.isCanEditGuidance()).isTrue());
        when(invoices.findByOrderId("o1")).thenReturn(Optional.of(new Invoice()));
        assertThat(service.getPricingReview("o1").checklistItems()).singleElement().satisfies(response -> {
            assertThat(response.isCanEditGuidance()).isFalse();
            assertThat(response.getGuidanceBlockedReason()).isEqualTo("ORDER_INVOICED");
        });
        manager.getRole().setCode(RoleCode.CUSTOMER);
        assertThat(service.getPricingReview("o1").checklistItems()).singleElement()
                .satisfies(response -> assertThat(response.getGuidanceBlockedReason()).isEqualTo("ACCESS_DENIED"));
    }

    private void confirmed() {
        item.setCustomGuidance(ChecklistGuidanceContent.normalize(null, List.of("Inspect"), List.of("Access"), List.of("Photos")));
        item.setGuidanceVersion(1); item.setGuidanceConfirmedVersion(1L);
    }

    private ChecklistGuidanceRequest request(long version, String description, List<String> steps, boolean confirm) {
        return new ChecklistGuidanceRequest(2L, version, description, steps, List.of(), List.of(), confirm);
    }

    private ChecklistGuidanceRequest complete(long version, boolean confirm) {
        return new ChecklistGuidanceRequest(2L, version, null, List.of(" Inspect ", "Inspect"), List.of("Access"), List.of("Photos"), confirm);
    }

    private ChecklistGuidanceSnapshot snapshot() {
        var content = ChecklistGuidanceContent.normalize(item.getCustomGuidance());
        return new ChecklistGuidanceSnapshot(1, List.of(new ChecklistGuidanceSnapshot.Item("i1", 2, item.getGuidanceVersion(),
                content.description(), content.executionSteps(), content.inputRequirements(), content.expectedOutputs())));
    }

    private Quote quote(QuoteStatus status) {
        Quote quote = new Quote(); quote.setId("q1"); quote.setOrder(order); quote.setChecklistRevision(2);
        quote.setStatus(status); quote.setPricingApprovalStatus(PricingApprovalStatus.NOT_REQUIRED);
        quote.setTotalAmount(BigDecimal.TEN); quote.setChecklistGuidanceSnapshot(snapshot()); return quote;
    }

    private void active(Quote quote) {
        when(quotes.findHistory("o1")).thenReturn(List.of(quote));
        when(quotes.findByIdForUpdate(quote.getId())).thenReturn(Optional.of(quote));
    }

    private void expect(ErrorCode code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}
