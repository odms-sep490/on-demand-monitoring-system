package com.ondemandmonitoring.order.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ondemandmonitoring.checklist.domain.ChecklistDefinition;
import com.ondemandmonitoring.checklist.repository.ChecklistDefinitionRepository;
import com.ondemandmonitoring.checklist.repository.ServiceChecklistRepository;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import com.ondemandmonitoring.order.dto.request.OrderChecklistItemRequest;
import com.ondemandmonitoring.order.enums.OrderChecklistSourceType;
import com.ondemandmonitoring.order.enums.ChecklistReviewStatus;
import com.ondemandmonitoring.order.enums.OrderStatus;
import com.ondemandmonitoring.order.enums.RequiredEvidenceType;
import com.ondemandmonitoring.order.mapper.OrderChecklistItemMapper;
import com.ondemandmonitoring.order.repository.OrderChecklistItemRepository;
import com.ondemandmonitoring.order.service.impl.OrderChecklistSnapshotService;
import com.ondemandmonitoring.pricing.domain.PricingPolicy;
import com.ondemandmonitoring.pricing.domain.PricingRule;
import com.ondemandmonitoring.pricing.enums.*;
import com.ondemandmonitoring.pricing.repository.PricingPolicyRepository;
import com.ondemandmonitoring.pricing.service.ChecklistPriceCalculator;
import com.ondemandmonitoring.pricing.service.ServicePriceResolver;
import com.ondemandmonitoring.pricing.service.PricingEngine;
import com.ondemandmonitoring.pricing.dto.PricingBreakdown;
import com.ondemandmonitoring.pricing.dto.PricingBreakdownItem;
import com.ondemandmonitoring.service.domain.Service;
import com.ondemandmonitoring.service.repository.ServiceRepository;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

/** Order snapshot capture: what is frozen, what the customer may and may not choose, and the evidence thresholds derived. */
class OrderChecklistPricingSnapshotTest {
    ServiceRepository services = mock(ServiceRepository.class);
    ChecklistDefinitionRepository definitions = mock(ChecklistDefinitionRepository.class);
    ServiceChecklistRepository assignments = mock(ServiceChecklistRepository.class);
    OrderChecklistItemRepository snapshots = mock(OrderChecklistItemRepository.class);
    PricingPolicyRepository policies = mock(PricingPolicyRepository.class);
    PricingEngine engine = mock(PricingEngine.class);
    OrderChecklistSnapshotService useCase;
    Order order;
    ChecklistDefinition roof;
    List<OrderChecklistItem> saved = new ArrayList<>();
    List<ServiceChecklistRepository.TemplateOverride> overrides = new ArrayList<>();

    @BeforeEach void setup() {
        var resolver = mock(AuthenticatedUserResolver.class);
        var user = User.builder().fullName("Customer").build(); user.setId("u1");
        when(resolver.getCurrentUser()).thenReturn(user);
        var service = Service.builder().name("Service").isActive(true).build(); service.setId("s1");
        order = Order.builder().customer(user).service(service).orderStatus(OrderStatus.PENDING).build(); order.setId("o1");
        when(services.findByIdForUpdate("s1")).thenReturn(Optional.of(service));
        roof = definition("c1", "Check roof", "200000", 10, 1);
        var wall = definition("c2", "Check wall", "100000", 0, 0);
        when(assignments.findTemplateChecklistIds("s1")).thenReturn(List.of("c1", "c2"));
        when(assignments.findTemplateOverrides("s1")).thenReturn(overrides);
        when(definitions.findByIdForUpdate("c1")).thenReturn(Optional.of(roof));
        when(definitions.findByIdForUpdate("c2")).thenReturn(Optional.of(wall));
        when(snapshots.saveAllAndFlush(any())).thenAnswer(invocation -> {
            List<OrderChecklistItem> items = invocation.getArgument(0);
            items.forEach(item -> item.setId(UUID.randomUUID().toString()));
            saved.addAll(items);
            return items;
        });
        policy(rule(MediaPricingComponent.INCLUDED_IMAGE, "STANDARD", "50000", null, null),
                tier(MediaPricingComponent.EXTRA_IMAGE, "0", "10", "50000"), tier(MediaPricingComponent.EXTRA_IMAGE, "10", null, "40000"),
                tier(MediaPricingComponent.EXTRA_VIDEO, "0", null, "90000"),
                rule(MediaPricingComponent.CHECKLIST_BASE, "CUSTOM", "300000", null, null), customTier());
        useCase = new OrderChecklistSnapshotService(services, definitions, assignments, snapshots,
                Mappers.getMapper(OrderChecklistItemMapper.class), resolver, policies, new ChecklistPriceCalculator(),
                new ServicePriceResolver(assignments, policies, new ChecklistPriceCalculator()));
        when(engine.calculate(any(), any())).thenReturn(new PricingBreakdown("p1", 3, "VND", BigDecimal.ZERO,
                false, true, List.of(), List.of()));
        useCase.setPricingEngine(engine);
    }

    ChecklistDefinition definition(String id, String content, String base, int images, int videos) {
        var c = new ChecklistDefinition();
        c.setId(id); c.setContent(content); c.setVersion(2L); c.setIsActive(true);
        c.setBasePrice(new BigDecimal(base)); c.setIncludedImageCount(images); c.setIncludedVideoCount(videos);
        c.setPricingProfile("STANDARD"); c.setStandardVideoDurationSeconds(images > 0 ? 30 : null);
        return c;
    }
    PricingRule rule(MediaPricingComponent component, String profile, String price, Integer allowance, PricingRounding rounding) {
        var r = new PricingRule();
        r.setCategory(PricingCategory.MEDIA); r.setPricingProfile(profile); r.setMediaComponent(component);
        r.setRuleKey(profile + component); r.setDescription(component.name()); r.setCalculationType(PricingCalculationType.FIXED);
        r.setFixedAmount(new BigDecimal(price)); r.setIncludedQuantity(allowance); r.setRoundingMode(rounding); r.setEnabled(true);
        return r;
    }
    PricingRule tier(MediaPricingComponent component, String from, String to, String price) {
        var r = rule(component, "STANDARD", price, null, PricingRounding.CEILING);
        r.setRuleKey("STANDARD" + component + from); r.setMinValue(new BigDecimal(from));
        r.setMaxValue(to == null ? null : new BigDecimal(to)); r.setUnitSize(BigDecimal.TEN);
        return r;
    }
    PricingRule customTier() {
        // Customer-added work shares the DEFAULT price list for extra media.
        var r = rule(MediaPricingComponent.EXTRA_IMAGE, "DEFAULT", "70000", null, PricingRounding.CEILING);
        r.setRuleKey("DEFAULT-EXTRA"); r.setMinValue(BigDecimal.ZERO); r.setUnitSize(BigDecimal.TEN);
        return r;
    }
    void policy(PricingRule... rules) {
        var p = new PricingPolicy(); p.setId("p1"); p.setPolicyVersion(3); p.setCurrency("VND"); p.setStatus(PricingPolicyStatus.ACTIVE);
        p.replaceRules(List.of(rules));
        when(policies.findEffectiveActive(any(Instant.class))).thenReturn(List.of(p));
    }
    OrderChecklistItemRequest template(String id, Integer images, Integer videos) {
        var r = new OrderChecklistItemRequest();
        r.setSourceChecklistId(id); r.setExpectedChecklistVersion(2L); r.setRequestedImageCount(images); r.setRequestedVideoCount(videos);
        return r;
    }
    ServiceChecklistRepository.TemplateOverride override(String checklistId, String base, Integer images, Integer videos, String profile, boolean required) {
        var o = mock(ServiceChecklistRepository.TemplateOverride.class);
        when(o.getChecklistId()).thenReturn(checklistId);
        when(o.getBasePriceOverride()).thenReturn(base == null ? null : new BigDecimal(base));
        when(o.getIncludedImageCountOverride()).thenReturn(images);
        when(o.getIncludedVideoCountOverride()).thenReturn(videos);
        when(o.getPricingProfileOverride()).thenReturn(profile);
        when(o.isRequired()).thenReturn(required);
        when(o.getChecklistActive()).thenReturn(true);
        return o;
    }

    @Test void requestedMediaAndTheCatalogConfigurationAreFrozenOnTheSnapshot() {
        useCase.capture(order, List.of(template("c1", 30, 3)));

        OrderChecklistItem item = saved.getFirst();
        assertEquals(1, item.getPricingSnapshotVersion());
        assertEquals(0, new BigDecimal("200000").compareTo(item.getBasePriceSnapshot()));
        assertEquals(10, item.getIncludedImageCountSnapshot());
        assertEquals(1, item.getIncludedVideoCountSnapshot());
        assertEquals(30, item.getRequestedImageCount());
        assertEquals(3, item.getRequestedVideoCount());
        assertEquals("STANDARD", item.getPricingProfileSnapshot());
        assertEquals(30, item.getStandardVideoDurationSnapshot());
        assertEquals(OrderChecklistSourceType.SERVICE_TEMPLATE, item.getSourceType());
        assertEquals(ChecklistReviewStatus.INCLUDED, item.getReviewStatus());
        assertEquals("p1", order.getPricingPolicyId());
        assertEquals(3, order.getPricingPolicyVersion());
        // evidence policy V3 demands BOTH thresholds
        assertEquals(3, item.getEvidencePolicyVersion());
        assertEquals(30, item.getMinimumImageCount());
        assertEquals(3, item.getMinimumVideoCount());
        assertEquals(33, item.getMinimumEvidenceCount());
        assertEquals(RequiredEvidenceType.IMAGE_AND_VIDEO, item.getRequiredEvidenceType());
    }

    @Test void catalogChecklistOutsideThePackageRequiresManagerReview() {
        var thermal = definition("c3", "Thermal inspection", "350000", 0, 0);
        when(definitions.findByIdForUpdate("c3")).thenReturn(Optional.of(thermal));

        useCase.capture(order, List.of(template("c3", null, null)));

        assertEquals(OrderChecklistSourceType.CUSTOMER_SELECTED, saved.getFirst().getSourceType());
        assertEquals(ChecklistReviewStatus.PENDING, saved.getFirst().getReviewStatus());
        assertTrue(saved.getFirst().isManagerQuoteRequired());
    }

    @Test void catalogEditsAfterCaptureDoNotChangeTheStoredSnapshot() {
        useCase.capture(order, List.of(template("c1", null, null)));
        OrderChecklistItem item = saved.getFirst();
        roof.setBasePrice(new BigDecimal("999999")); roof.setIncludedImageCount(300);
        assertEquals(0, new BigDecimal("200000").compareTo(item.getBasePriceSnapshot()));
        assertEquals(10, item.getIncludedImageCountSnapshot());
        assertEquals(10, item.getRequestedImageCount());
    }

    @Test void omittedQuantitiesDefaultToTheIncludedAllowance() {
        useCase.capture(order, null);
        OrderChecklistItem roofItem = saved.getFirst();
        assertEquals(10, roofItem.getRequestedImageCount());
        assertEquals(1, roofItem.getRequestedVideoCount());
        assertEquals(3, roofItem.getEvidencePolicyVersion());
        OrderChecklistItem wallItem = saved.get(1);
        // no media allowance and no explicit request: the legacy V2 default evidence rule stays
        assertEquals(2, wallItem.getEvidencePolicyVersion());
        assertEquals(1, wallItem.getMinimumEvidenceCount());
    }

    @Test void onlyImagesRequestedMeansOnlyImageEvidenceIsAccepted() {
        useCase.capture(order, List.of(template("c1", 12, 0)));
        OrderChecklistItem item = saved.getFirst();
        assertEquals(RequiredEvidenceType.IMAGE, item.getRequiredEvidenceType());
        assertEquals(0, item.getMinimumVideoCount());
    }

    @Test void serviceAssociationOverrideWinsOverTheDefinitionWithoutTouchingIt() {
        overrides.add(override("c1", "250000", 20, null, "premium", false));
        useCase.capture(order, List.of(template("c1", null, null)));

        OrderChecklistItem item = saved.getFirst();
        assertEquals(0, new BigDecimal("250000").compareTo(item.getBasePriceSnapshot()));
        assertEquals(20, item.getIncludedImageCountSnapshot());
        assertEquals(1, item.getIncludedVideoCountSnapshot());
        assertEquals("PREMIUM", item.getPricingProfileSnapshot());
        assertEquals(0, new BigDecimal("200000").compareTo(roof.getBasePrice()));
        assertEquals(10, roof.getIncludedImageCount());
    }

    @Test void requiredChecklistCannotBeRemovedButOptionalOnesCan() {
        overrides.add(override("c2", null, null, null, null, true));
        var ex = assertThrows(ApiException.class, () -> useCase.capture(order, List.of(template("c1", null, null))));
        assertEquals(ErrorCode.ORDER_CHECKLIST_REQUIRED_MISSING, ex.getErrorCode());
        overrides.clear();
        overrides.add(override("c2", null, null, null, null, false));
        assertDoesNotThrow(() -> useCase.capture(order, List.of(template("c1", null, null))));
    }

    @Test void explicitEvidenceFieldsAndRequestedMediaCannotBeMixed() {
        var item = template("c1", 10, null);
        item.setMinimumEvidenceCount(2);
        assertEquals(ErrorCode.INVALID_REQUEST, assertThrows(ApiException.class, () -> useCase.capture(order, List.of(item))).getErrorCode());
    }

    @Test void imageAndVideoEvidenceCannotBeDeclaredWithASingleMinimum() {
        var item = template("c1", null, null);
        item.setMinimumEvidenceCount(2);
        item.setRequiredEvidenceType(RequiredEvidenceType.IMAGE_AND_VIDEO);
        assertEquals(ErrorCode.INVALID_REQUEST, assertThrows(ApiException.class, () -> useCase.capture(order, List.of(item))).getErrorCode());
    }

    @Test void requestingOnlyImagesStoresAnImageOnlyRequirement() {
        useCase.capture(order, List.of(template("c1", 5, 0)));
        var item = saved.getFirst();
        assertEquals(RequiredEvidenceType.IMAGE, item.getRequiredEvidenceType());
        assertEquals(5, item.getMinimumImageCount());
        assertEquals(0, item.getMinimumVideoCount());
    }

    @Test void aChecklistRequestingNoMediaAtAllIsRefused() {
        assertEquals(ErrorCode.INVALID_REQUEST,
                assertThrows(ApiException.class, () -> useCase.capture(order, List.of(template("c1", 0, 0)))).getErrorCode());
    }

    @Test void extraMediaThePolicyCannotPriceIsRejectedBeforeASnapshotExists() {
        policy(rule(MediaPricingComponent.INCLUDED_IMAGE, "STANDARD", "50000", null, null));
        var ex = assertThrows(ApiException.class, () -> useCase.capture(order, List.of(template("c1", 11, null))));
        assertEquals(ErrorCode.MEDIA_PRICING_NOT_CONFIGURED, ex.getErrorCode());
        assertTrue(saved.isEmpty());
    }

    @Test void customChecklistUsesGenericPricingAndRequiresManagerReview() {
        var custom = new OrderChecklistItemRequest();
        custom.setContentOverride("Check the antenna mast"); custom.setRequestedImageCount(4);
        when(engine.calculate(any(), any())).thenAnswer(call -> {
            List<OrderChecklistItem> rows = call.getArgument(1);
            var line = new PricingBreakdownItem(PricingCategory.MEDIA, "Internal custom price", BigDecimal.ONE,
                    new BigDecimal("880000"), new BigDecimal("880000"), rows.getFirst().getId());
            return new PricingBreakdown("p1", 3, "VND", new BigDecimal("880000"), true, true, List.of(line), List.of());
        });
        useCase.capture(order, List.of(custom));
        OrderChecklistItem item = saved.getFirst();
        assertEquals(OrderChecklistSourceType.CUSTOMER_CUSTOM, item.getSourceType());
        assertEquals("GENERIC_CUSTOMER_ADDED", item.getPricingProfileSnapshot());
        assertEquals(1, item.getPricingSnapshotVersion());
        assertNull(item.getSourceChecklist());
        assertTrue(item.isManagerQuoteRequired(), "customer-added work always requires Manager price review");
        assertEquals(new BigDecimal("880000"), item.getSystemCalculatedPrice());

        saved.clear();
        order.setChecklistSnapshotAt(null);
        policy(rule(MediaPricingComponent.INCLUDED_IMAGE, "STANDARD", "50000", null, null));
        assertThrows(ApiException.class, () -> useCase.capture(order, List.of(custom)),
                "a customer-added checklist must have a configured system baseline");
    }

    @Test void selectionThatPricesToNothingOnAnUnpricedServiceIsRefused() {
        roof.setBasePrice(BigDecimal.ZERO); roof.setIncludedImageCount(0); roof.setIncludedVideoCount(0);
        // nothing is stubbed for the default checklist rows -> the service has no priced default checklist
        var e = assertThrows(ApiException.class, () -> useCase.capture(order, List.of(template("c1", null, null))));
        assertEquals(ErrorCode.SERVICE_NOT_PRICED, e.getErrorCode());
    }

    @Test void theRequestHasNoFieldThatCouldCarryAClientSidePrice() {
        var names = Arrays.stream(OrderChecklistItemRequest.class.getDeclaredFields()).map(f -> f.getName().toLowerCase(Locale.ROOT)).toList();
        assertTrue(names.stream().noneMatch(n -> n.contains("price") || n.contains("amount") || n.contains("total") || n.contains("cost")), names.toString());
    }

    @Test void recalculationReplacesEveryChecklistSystemPriceFromTheLatestBreakdown() {
        var first = new OrderChecklistItem(); first.setId("i1"); first.setOrder(order);
        first.setSystemCalculatedPrice(new BigDecimal("100000"));
        var second = new OrderChecklistItem(); second.setId("i2"); second.setOrder(order);
        second.setSystemCalculatedPrice(new BigDecimal("200000"));
        when(snapshots.findAllByOrderIdOrderByDisplayOrderAscIdAsc("o1")).thenReturn(List.of(first, second));
        when(engine.calculate(order, List.of(first, second))).thenReturn(new PricingBreakdown("p2", 2, "VND",
                new BigDecimal("650000"), false, true, List.of(
                new PricingBreakdownItem(PricingCategory.CHECKLIST, "first base", BigDecimal.ONE,
                        new BigDecimal("150000"), new BigDecimal("150000"), "i1"),
                new PricingBreakdownItem(PricingCategory.AREA, "first area", BigDecimal.ONE,
                        new BigDecimal("300000"), new BigDecimal("300000"), "i1"),
                new PricingBreakdownItem(PricingCategory.CHECKLIST, "second base", BigDecimal.ONE,
                        new BigDecimal("200000"), new BigDecimal("200000"), "i2")), List.of()));

        useCase.recalculate(order);

        assertEquals(new BigDecimal("450000"), first.getSystemCalculatedPrice());
        assertEquals(new BigDecimal("200000"), second.getSystemCalculatedPrice());
    }
}
