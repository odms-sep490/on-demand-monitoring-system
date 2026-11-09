package com.ondemandmonitoring.order.service;
import com.ondemandmonitoring.checklist.domain.ChecklistDefinition;
import com.ondemandmonitoring.checklist.repository.*;
import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.order.domain.*;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.dto.request.OrderChecklistItemRequest;
import com.ondemandmonitoring.order.enums.*;
import com.ondemandmonitoring.order.mapper.OrderChecklistItemMapper;
import com.ondemandmonitoring.order.repository.OrderChecklistItemRepository;
import com.ondemandmonitoring.order.service.impl.OrderChecklistSnapshotService;
import com.ondemandmonitoring.service.domain.Service;
import com.ondemandmonitoring.service.repository.ServiceRepository;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import org.junit.jupiter.api.*;
import org.mapstruct.factory.Mappers;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class OrderChecklistSnapshotServiceTest {
    ServiceRepository services;
    ChecklistDefinitionRepository definitions;
    ServiceChecklistRepository assignments;
    OrderChecklistItemRepository snapshots;
    OrderChecklistSnapshotService useCase;
    Order order;
    ChecklistDefinition c1, c2;
    @BeforeEach void setup() {
        services = mock(ServiceRepository.class); definitions = mock(ChecklistDefinitionRepository.class);
        assignments = mock(ServiceChecklistRepository.class); snapshots = mock(OrderChecklistItemRepository.class);
        var resolver = mock(AuthenticatedUserResolver.class);
        var user = User.builder().fullName("Customer").build(); user.setId("u1");
        when(resolver.getCurrentUser()).thenReturn(user);
        var service = Service.builder().name("Service").isActive(true).build(); service.setId("s1");
        order = Order.builder().customer(user).service(service).orderStatus(OrderStatus.PENDING).build(); order.setId("o1");
        when(services.findByIdForUpdate("s1")).thenReturn(Optional.of(service));
        when(assignments.findTemplateChecklistIds("s1")).thenReturn(List.of("c2", "c1"));
        c1 = definition("c1", "Check exits"); c2 = definition("c2", "Check PPE");
        when(definitions.findByIdForUpdate("c1")).thenReturn(Optional.of(c1));
        when(definitions.findByIdForUpdate("c2")).thenReturn(Optional.of(c2));
        when(snapshots.saveAllAndFlush(any())).thenAnswer(invocation -> {
            List<OrderChecklistItem> items = invocation.getArgument(0);
            items.forEach(item -> {
                assertEquals(2, item.getEvidencePolicyVersion());
                assertTrue(item.getMinimumEvidenceCount() >= 1 && item.getMinimumEvidenceCount() <= 50);
                assertNotNull(item.getRequiredEvidenceType());
            });
            items.forEach(item -> item.setId(UUID.randomUUID().toString())); return items;
        });
        useCase = new OrderChecklistSnapshotService(services, definitions, assignments, snapshots,
                Mappers.getMapper(OrderChecklistItemMapper.class), resolver,
                mock(com.ondemandmonitoring.pricing.repository.PricingPolicyRepository.class),
                new com.ondemandmonitoring.pricing.service.ChecklistPriceCalculator(),
                new com.ondemandmonitoring.pricing.service.ServicePriceResolver(assignments,
                        mock(com.ondemandmonitoring.pricing.repository.PricingPolicyRepository.class),
                        new com.ondemandmonitoring.pricing.service.ChecklistPriceCalculator()));
    }
    ChecklistDefinition definition(String id, String content) {
        var c = new ChecklistDefinition(); c.setId(id); c.setContent(content); c.setVersion(2L); return c;
    }
    OrderChecklistItemRequest item(String id, String content) {
        var r = new OrderChecklistItemRequest(); r.setSourceChecklistId(id); r.setContentOverride(content);
        if (id != null) r.setExpectedChecklistVersion(2L); return r;
    }
    @Test void defaultsUseBackendContentInTemplateOrderButLockSortedIds() {
        var result = useCase.capture(order, null);
        assertEquals(List.of("Check PPE", "Check exits"), result.stream().map(r -> r.getContent()).toList());
        assertNotNull(order.getChecklistSnapshotAt());
        var locks = inOrder(services, definitions);
        locks.verify(services).findByIdForUpdate("s1");
        locks.verify(definitions).findByIdForUpdate("c1"); locks.verify(definitions).findByIdForUpdate("c2");
    }
    @Test void guidanceIsFrozenAtOrderTimeAndSurvivesLaterCatalogEdits() {
        c1.setDescription("Covers the roof"); c1.setExecutionSteps(List.of("Fly the grid", "Photograph defects"));
        c1.setInputRequirements(List.of("Clear weather")); c1.setExpectedOutputs(List.of("Roof photos"));
        var result = useCase.capture(order, List.of(item("c1", "Check exits"), item(null, "Own custom work")));
        var captured = result.stream().filter(r -> "Check exits".equals(r.getContent())).findFirst().orElseThrow();
        assertEquals("Covers the roof", captured.getDescription());
        assertEquals(List.of("Fly the grid", "Photograph defects"), captured.getExecutionSteps());
        assertEquals(List.of("Clear weather"), captured.getInputRequirements());
        assertEquals(List.of("Roof photos"), captured.getExpectedOutputs());
        var custom = result.stream().filter(r -> "Own custom work".equals(r.getContent())).findFirst().orElseThrow();
        assertNull(custom.getDescription()); assertNull(custom.getExecutionSteps());
        // The catalog is edited afterwards: the stored snapshot is a separate copy.
        c1.setDescription("Rewritten"); c1.setExecutionSteps(List.of("Different"));
        assertEquals("Covers the roof", captured.getDescription());
        assertEquals(List.of("Fly the grid", "Photograph defects"), captured.getExecutionSteps());
    }
    @Test void configurableEvidenceRequirementIsSnapshottedAndReturned() {
        var configured = item("c1", "Check exits");
        configured.setMinimumEvidenceCount(2);
        configured.setRequiredEvidenceType(RequiredEvidenceType.IMAGE);
        doAnswer(invocation -> {
            List<OrderChecklistItem> saved = invocation.getArgument(0);
            saved.forEach(item -> item.setId(UUID.randomUUID().toString()));
            return saved;
        }).when(snapshots).saveAllAndFlush(any());
        var result = useCase.capture(order, List.of(configured));
        assertEquals(2, result.getFirst().getEvidencePolicyVersion());
        assertEquals(2, result.getFirst().getMinimumEvidenceCount());
        assertEquals(RequiredEvidenceType.IMAGE, result.getFirst().getRequiredEvidenceType());
    }

    @Test void invalidEvidenceRequirementIsRejected() {
        var invalid = item("c1", "Check exits");
        invalid.setMinimumEvidenceCount(51);
        assertThrows(ApiException.class, () -> useCase.capture(order, List.of(invalid)));
        invalid.setMinimumEvidenceCount(0);
        assertThrows(ApiException.class, () -> useCase.capture(order, List.of(invalid)));
    }

    @Test void supportsSubsetEditedAndCustomWithoutChangingCatalog() {
        var result = useCase.capture(order, List.of(item("c1", " Edited  exits "), item(null, "Customer custom")));
        assertEquals("Edited exits", result.getFirst().getContent());
        assertEquals(OrderChecklistSourceType.SERVICE_TEMPLATE, result.getFirst().getSourceType());
        assertNull(result.get(1).getSourceChecklistId());
        assertEquals(OrderChecklistSourceType.CUSTOMER_CUSTOM, result.get(1).getSourceType());
        assertEquals("Check exits", c1.getContent());
        verify(definitions, never()).save(any());
    }
    @Test void emptySelectionIsRejectedWithoutPersistingSnapshot() {
        assertThrows(ApiException.class, () -> useCase.capture(order, List.of()));
        assertNull(order.getChecklistSnapshotAt());
        verify(snapshots, never()).saveAllAndFlush(any());
    }
    @Test void emptyDefaultTemplateIsRejected() {
        when(assignments.findTemplateChecklistIds("s1")).thenReturn(List.of());
        assertThrows(ApiException.class, () -> useCase.capture(order, null));
        verify(snapshots, never()).saveAllAndFlush(any());
    }
    @Test void inactiveOnlyDefaultTemplateIsRejected() {
        c1.setIsActive(false);
        c2.setIsActive(false);
        assertThrows(ApiException.class, () -> useCase.capture(order, null));
        verify(snapshots, never()).saveAllAndFlush(any());
    }
    @Test void customOnlySelectionIsAllowed() {
        var result = useCase.capture(order, List.of(item(null, "Inspect customer area")));
        assertEquals(1, result.size());
        assertEquals(OrderChecklistSourceType.CUSTOMER_CUSTOM, result.getFirst().getSourceType());
    }
    @Test void rejectsForeignInactiveStaleAndDuplicateSource() {
        assertThrows(ApiException.class, () -> useCase.capture(order, List.of(item("foreign", null))));
        c1.setIsActive(false);
        assertThrows(ApiException.class, () -> useCase.capture(order, List.of(item("c1", null))));
        c1.setIsActive(true); var stale = item("c1", null); stale.setExpectedChecklistVersion(1L);
        assertEquals(ErrorCode.ORDER_CHECKLIST_TEMPLATE_CHANGED,
                assertThrows(ApiException.class, () -> useCase.capture(order, List.of(stale))).getErrorCode());
        assertThrows(ApiException.class, () -> useCase.capture(order, List.of(item("c1", null), item("c1", null))));
        verify(snapshots, never()).saveAllAndFlush(any());
    }
    @Test void rejectsBlankInvalidCustomDuplicateContentAndMissingVersion() {
        assertThrows(ApiException.class, () -> useCase.capture(order, List.of(item(null, " "))));
        assertThrows(ApiException.class, () -> useCase.capture(order, List.of(item(null, null))));
        assertThrows(ApiException.class, () -> useCase.capture(order, List.of(item(null, "x".repeat(501)))));
        assertThrows(ApiException.class, () -> useCase.capture(order, List.of(item(null, "Same"), item(null, " same "))));
        var missingVersion = item("c1", null); missingVersion.setExpectedChecklistVersion(null);
        assertThrows(ApiException.class, () -> useCase.capture(order, List.of(missingVersion)));
        verify(snapshots, never()).saveAllAndFlush(any());
    }
    @Test void defaultsSkipInactiveButExplicitSelectionRejectsIt() {
        c1.setIsActive(false);
        assertEquals(1, useCase.capture(order, null).size());
    }
    @Test void cannotRecaptureOrCaptureOtherCustomersOrder() {
        var other = User.builder().build(); other.setId("other"); order.setCustomer(other);
        assertEquals(ErrorCode.ACCESS_DENIED, assertThrows(ApiException.class, () -> useCase.capture(order, null)).getErrorCode());
        order.setChecklistSnapshotAt(java.time.Instant.now());
        assertEquals(ErrorCode.ORDER_CHECKLIST_LOCKED, assertThrows(ApiException.class, () -> useCase.capture(order, null)).getErrorCode());
    }
    @Test void refusesNonPendingAndInactiveService() {
        order.setOrderStatus(OrderStatus.APPROVED);
        assertThrows(ApiException.class, () -> useCase.capture(order, null));
        order.setOrderStatus(OrderStatus.PENDING); order.getService().setIsActive(false);
        assertEquals(ErrorCode.SERVICE_INACTIVE, assertThrows(ApiException.class, () -> useCase.capture(order, null)).getErrorCode());
    }
    @Test void batchReadMapsOnlySnapshotContent() {
        var row = new OrderChecklistItem(); row.setId("stable"); row.setOrder(order);
        row.setSourceChecklist(c1); row.setContent("Historical"); row.setSourceType(OrderChecklistSourceType.SERVICE_TEMPLATE);
        when(snapshots.findAllByOrderIdInOrderByDisplayOrderAscIdAsc(List.of("o1"))).thenReturn(List.of(row));
        c1.setContent("Catalog changed");
        assertEquals("Historical", useCase.getByOrders(List.of("o1")).get("o1").getFirst().getContent());
        verifyNoInteractions(definitions, assignments);
        assertTrue(useCase.getByOrders(List.of()).isEmpty());
    }
}
