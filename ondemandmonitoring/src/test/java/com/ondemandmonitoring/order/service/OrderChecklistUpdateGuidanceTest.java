package com.ondemandmonitoring.order.service;

import com.ondemandmonitoring.checklist.repository.ChecklistDefinitionRepository;
import com.ondemandmonitoring.checklist.repository.ServiceChecklistRepository;
import com.ondemandmonitoring.finance.repository.QuoteRepository;
import com.ondemandmonitoring.order.domain.ChecklistGuidanceContent;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import com.ondemandmonitoring.order.dto.request.OrderChecklistUpdateItemRequest;
import com.ondemandmonitoring.order.dto.request.OrderChecklistUpdateRequest;
import com.ondemandmonitoring.order.enums.OrderChecklistSourceType;
import com.ondemandmonitoring.order.enums.OrderStatus;
import com.ondemandmonitoring.order.enums.RequiredEvidenceType;
import com.ondemandmonitoring.order.repository.OrderChecklistItemRepository;
import com.ondemandmonitoring.order.repository.OrderChecklistRevisionRepository;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.order.service.impl.OrderChecklistEditPolicy;
import com.ondemandmonitoring.order.service.impl.OrderChecklistSnapshotService;
import com.ondemandmonitoring.order.service.impl.OrderChecklistUpdateService;
import com.ondemandmonitoring.pricing.repository.PricingPolicyRepository;
import com.ondemandmonitoring.pricing.service.ChecklistPriceCalculator;
import com.ondemandmonitoring.pricing.service.ServicePriceResolver;
import com.ondemandmonitoring.service.domain.Service;
import com.ondemandmonitoring.service.repository.ServiceRepository;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises the real public update path, including its early-return evidence branch. */
class OrderChecklistUpdateGuidanceTest {
    private OrderChecklistItemRepository snapshots;
    private OrderChecklistUpdateService useCase;
    private Order order;
    private OrderChecklistItem original;
    private List<OrderChecklistItem> saved;

    @BeforeEach void setup() {
        var orders = mock(OrderRepository.class);
        var services = mock(ServiceRepository.class);
        var assignments = mock(ServiceChecklistRepository.class);
        snapshots = mock(OrderChecklistItemRepository.class);
        var currentUser = mock(AuthenticatedUserResolver.class);
        var policy = mock(OrderChecklistEditPolicy.class);
        var customer = User.builder().fullName("Customer").build();
        customer.setId("customer");
        var service = Service.builder().name("Service").isActive(true).build();
        service.setId("service");
        order = Order.builder().customer(customer).service(service).orderStatus(OrderStatus.PENDING).build();
        order.setId("order");
        order.setChecklistRevision(1);
        original = new OrderChecklistItem();
        original.setId("original");
        original.setOrder(order);
        original.setSourceType(OrderChecklistSourceType.CUSTOMER_CUSTOM);
        original.setContent("Inspect roof");
        original.setEvidencePolicyVersion(3);
        original.setRequestedImageCount(2);
        original.setMinimumImageCount(2);
        original.setMinimumEvidenceCount(2);
        original.setRequiredEvidenceType(RequiredEvidenceType.IMAGE);
        original.setCustomGuidance(ChecklistGuidanceContent.normalize(null, List.of("Fly"),
                List.of("No special input"), List.of("Photos")));
        original.setGuidanceVersion(4);
        original.setGuidanceConfirmedVersion(4L);
        original.setGuidanceUpdatedBy(customer);
        original.setGuidanceUpdatedAt(Instant.parse("2026-10-30T00:00:00Z"));

        when(orders.findByIdForUpdate("order")).thenReturn(Optional.of(order));
        when(services.findByIdForUpdate("service")).thenReturn(Optional.of(service));
        when(currentUser.getCurrentUser()).thenReturn(customer);
        when(policy.evaluate(order, "customer")).thenReturn(OrderChecklistEditPolicy.EditCapability.allowed());
        when(snapshots.findAllByOrderIdAndChecklistRevisionOrderByDisplayOrderAscIdAsc(eq("order"), anyLong()))
                .thenAnswer(invocation -> List.of(original));
        when(snapshots.saveAllAndFlush(anyList())).thenAnswer(invocation -> {
            saved = new ArrayList<>(invocation.<List<OrderChecklistItem>>getArgument(0));
            return saved;
        });
        useCase = new OrderChecklistUpdateService(orders, services, mock(ChecklistDefinitionRepository.class),
                assignments, snapshots, mock(OrderChecklistRevisionRepository.class), mock(QuoteRepository.class),
                mock(PricingPolicyRepository.class), mock(ChecklistPriceCalculator.class),
                mock(ServicePriceResolver.class), mock(OrderChecklistSnapshotService.class), policy,
                currentUser, mock(IOrderService.class));
    }

    @Test void unchangedInheritedCustomKeepsConfirmationWhenAnotherItemIsAdded() {
        var newItem = new OrderChecklistUpdateItemRequest();
        newItem.setContentOverride("Inspect wall");
        update(List.of(inherit(), newItem));
        assertInherited(saved.getFirst(), 4L);
        var added = saved.get(1);
        assertNull(added.getCustomGuidance());
        assertEquals(0, added.getGuidanceVersion());
        assertNull(added.getGuidanceConfirmedVersion());
        assertNull(added.getGuidanceUpdatedBy());
        assertNull(added.getGuidanceUpdatedAt());
        assertFalse(added.isGuidanceReady());
    }

    @Test void contentEditRetainsGuidanceButClearsConfirmationWithoutTouchingHistory() {
        var item = inherit();
        item.setContentOverride("Inspect south roof");
        update(List.of(item));
        assertInherited(saved.getFirst(), null);
        assertEquals("Inspect roof", original.getContent());
        assertEquals(4L, original.getGuidanceConfirmedVersion());
    }

    @Test void explicitMediaEditClearsConfirmation() {
        var item = inherit();
        item.setRequestedImageCount(3);
        update(List.of(item));
        assertInherited(saved.getFirst(), null);
        assertEquals(3, saved.getFirst().getMinimumImageCount());
        assertEquals(2, original.getMinimumImageCount());
    }

    @Test void legacyEvidenceEditClearsConfirmation() {
        var item = inherit();
        item.setMinimumEvidenceCount(1);
        item.setRequiredEvidenceType(RequiredEvidenceType.VIDEO);
        update(List.of(item));
        assertInherited(saved.getFirst(), null);
        assertEquals(RequiredEvidenceType.VIDEO, saved.getFirst().getRequiredEvidenceType());
    }

    @Test void editingThenRevertingDoesNotRestoreConfirmation() {
        var item = inherit();
        item.setContentOverride("Inspect south roof");
        update(List.of(item));
        original = saved.getFirst();
        original.setId("revision-two");
        var revert = inherit();
        revert.setContentOverride("Inspect roof");
        update(List.of(revert));
        assertInherited(saved.getFirst(), null);
        assertEquals("Inspect roof", saved.getFirst().getContent());
    }

    @Test void normalizedNoOpDoesNotCreateRevisionOrClearConfirmation() {
        var item = inherit();
        item.setContentOverride("  Inspect   roof  ");
        update(List.of(item));
        verify(snapshots, never()).saveAllAndFlush(anyList());
        assertEquals(4L, original.getGuidanceConfirmedVersion());
        assertEquals(1, order.getChecklistRevision());
    }

    private OrderChecklistUpdateItemRequest inherit() {
        var item = new OrderChecklistUpdateItemRequest();
        item.setExistingItemId(original.getId());
        return item;
    }

    private void update(List<OrderChecklistUpdateItemRequest> items) {
        var request = new OrderChecklistUpdateRequest();
        request.setExpectedRevision(order.getChecklistRevision());
        request.setItems(items);
        useCase.updateChecklist("order", request);
    }

    private void assertInherited(OrderChecklistItem candidate, Long confirmedVersion) {
        assertEquals(original.getCustomGuidance(), candidate.getCustomGuidance());
        assertEquals(4, candidate.getGuidanceVersion());
        assertEquals(confirmedVersion, candidate.getGuidanceConfirmedVersion());
        assertSame(original.getGuidanceUpdatedBy(), candidate.getGuidanceUpdatedBy());
        assertEquals(original.getGuidanceUpdatedAt(), candidate.getGuidanceUpdatedAt());
    }
}
