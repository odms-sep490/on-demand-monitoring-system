package com.ondemandmonitoring.order.mapper;

import com.ondemandmonitoring.order.domain.ChecklistGuidanceContent;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import com.ondemandmonitoring.order.enums.OrderChecklistSourceType;
import com.ondemandmonitoring.user.domain.User;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import static org.junit.jupiter.api.Assertions.*;

class OrderChecklistItemGuidanceMapperTest {
    private final OrderChecklistItemMapper mapper = Mappers.getMapper(OrderChecklistItemMapper.class);

    @Test void managerGetsCustomGuidanceWhileCustomerGetsOnlyMetadataWithoutDraftText() {
        var item = new OrderChecklistItem();
        item.setSourceType(OrderChecklistSourceType.CUSTOMER_CUSTOM);
        item.setCustomGuidance(ChecklistGuidanceContent.normalize("Custom", List.of("Fly"),
                List.of("Site access"), List.of("Photos")));
        item.setDescriptionSnapshot("Must never be used for custom");
        item.setGuidanceVersion(3);
        item.setGuidanceConfirmedVersion(3L);
        User manager = User.builder().fullName("Manager").build();
        manager.setId("manager-id");
        item.setGuidanceUpdatedBy(manager);
        item.setGuidanceUpdatedAt(Instant.parse("2026-10-30T00:00:00Z"));

        var internal = mapper.toResponse(item);
        var customer = mapper.toCustomerResponse(item);
        assertEquals("Custom", internal.getDescription());
        assertEquals(List.of("Fly"), internal.getExecutionSteps());
        assertEquals(List.of("Site access"), internal.getInputRequirements());
        assertEquals(List.of("Photos"), internal.getExpectedOutputs());
        assertNull(customer.getDescription());
        assertNull(customer.getExecutionSteps());
        assertNull(customer.getInputRequirements());
        assertNull(customer.getExpectedOutputs());
        assertEquals(3, internal.getGuidanceVersion());
        assertEquals(3, customer.getGuidanceVersion());
        assertEquals(3L, customer.getGuidanceConfirmedVersion());
        assertEquals("manager-id", internal.getGuidanceUpdatedById());
        assertEquals("manager-id", customer.getGuidanceUpdatedById());
        assertEquals(item.getGuidanceUpdatedAt(), customer.getGuidanceUpdatedAt());
        assertTrue(internal.isGuidanceReady());
        assertTrue(customer.isGuidanceReady());
        assertFalse(internal.isCanEditGuidance());
        assertFalse(customer.isCanEditGuidance());
        assertNull(internal.getGuidanceBlockedReason());

        item.setGuidanceConfirmedVersion(2L);
        assertFalse(mapper.toResponse(item).isGuidanceReady());
        item.setGuidanceConfirmedVersion(3L);
        item.setCustomGuidance(ChecklistGuidanceContent.normalize(null));
        assertFalse(mapper.toCustomerResponse(item).isGuidanceReady());
    }

    @Test void catalogUsesImmutableSnapshotsAndNewCustomHasNoGuidance() {
        for (var source : List.of(OrderChecklistSourceType.SERVICE_TEMPLATE,
                OrderChecklistSourceType.CUSTOMER_SELECTED)) {
            var item = new OrderChecklistItem();
            item.setSourceType(source);
            item.setDescriptionSnapshot("Catalog");
            item.setExecutionStepsSnapshot(List.of("Catalog step"));
            item.setInputRequirementsSnapshot(List.of("Catalog input"));
            item.setExpectedOutputsSnapshot(List.of("Catalog output"));
            item.setCustomGuidance(ChecklistGuidanceContent.normalize("Ignored", null, null, null));
            var response = mapper.toResponse(item);
            assertEquals("Catalog", response.getDescription());
            assertEquals(List.of("Catalog step"), response.getExecutionSteps());
            assertEquals(List.of("Catalog input"), response.getInputRequirements());
            assertEquals(List.of("Catalog output"), response.getExpectedOutputs());
            assertEquals("Catalog", mapper.toCustomerResponse(item).getDescription());
            assertTrue(response.isGuidanceReady());
        }
        var custom = new OrderChecklistItem();
        custom.setSourceType(OrderChecklistSourceType.CUSTOMER_CUSTOM);
        assertNull(mapper.toResponse(custom).getExecutionSteps());
        assertFalse(mapper.toCustomerResponse(custom).isGuidanceReady());
    }
}
