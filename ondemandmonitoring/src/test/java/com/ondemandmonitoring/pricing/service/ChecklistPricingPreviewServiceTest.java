package com.ondemandmonitoring.pricing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ondemandmonitoring.checklist.domain.ChecklistDefinition;
import com.ondemandmonitoring.checklist.domain.ServiceChecklist;
import com.ondemandmonitoring.checklist.repository.ChecklistDefinitionRepository;
import com.ondemandmonitoring.checklist.repository.ServiceChecklistRepository;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import com.ondemandmonitoring.order.enums.ChecklistReviewStatus;
import com.ondemandmonitoring.order.enums.OrderChecklistSourceType;
import com.ondemandmonitoring.order.enums.OrderPermitStatus;
import com.ondemandmonitoring.order.enums.OrderResultFormat;
import com.ondemandmonitoring.pricing.repository.CustomWorkAssessmentRepository;
import com.ondemandmonitoring.pricing.domain.PricingPolicy;
import com.ondemandmonitoring.pricing.domain.PricingRule;
import com.ondemandmonitoring.pricing.dto.ChecklistPricingPreviewRequests.*;
import com.ondemandmonitoring.pricing.enums.*;
import com.ondemandmonitoring.pricing.repository.PricingPolicyRepository;
import com.ondemandmonitoring.service.domain.Service;
import com.ondemandmonitoring.service.repository.ServiceRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChecklistPricingPreviewServiceTest {
    ServiceRepository services = mock(ServiceRepository.class);
    ChecklistDefinitionRepository definitions = mock(ChecklistDefinitionRepository.class);
    ServiceChecklistRepository assignments = mock(ServiceChecklistRepository.class);
    PricingPolicyRepository policies = mock(PricingPolicyRepository.class);
    ChecklistPricingPreviewService preview = new ChecklistPricingPreviewService(services, definitions, assignments, policies, new ChecklistPriceCalculator(),
            new ServicePriceResolver(assignments, policies, new ChecklistPriceCalculator()));
    Service inspection, survey;
    ChecklistDefinition roof, wall;
    PricingPolicy activePolicy;
    List<ServiceChecklist> inspectionRows = new ArrayList<>();

    @BeforeEach void setup() {
        inspection = Service.builder().name("Inspection").isActive(true).basePrice(new BigDecimal("1000000")).build(); inspection.setId("s1");
        survey = Service.builder().name("Survey").isActive(true).basePrice(new BigDecimal("500000")).build(); survey.setId("s2");
        roof = checklist("c1", "Check roof", "200000", 10, 1);
        wall = checklist("c2", "Check wall", "100000", 0, 0);
        inspectionRows.add(row(inspection, roof, 0)); inspectionRows.add(row(inspection, wall, 1));
        when(services.findById("s1")).thenReturn(Optional.of(inspection));
        when(services.findById("s2")).thenReturn(Optional.of(survey));
        when(assignments.findAllByServiceIdOrderByDisplayOrderAscIdAsc("s1")).thenAnswer(i -> inspectionRows);
        when(assignments.findAllByServiceIdAndChecklistIsActiveTrueOrderByDisplayOrderAscIdAsc("s1")).thenAnswer(i -> inspectionRows);
        when(assignments.findAllByServiceIdOrderByDisplayOrderAscIdAsc("s2")).thenReturn(List.of(row(survey, roof, 0)));
        when(definitions.findById("c1")).thenReturn(Optional.of(roof));
        when(definitions.findById("c2")).thenReturn(Optional.of(wall));
        activePolicy = new PricingPolicy(); activePolicy.setId("p1"); activePolicy.setPolicyVersion(5); activePolicy.setCurrency("VND"); activePolicy.setStatus(PricingPolicyStatus.ACTIVE);
        activePolicy.replaceRules(List.of(included(MediaPricingComponent.INCLUDED_IMAGE, "50000"), included(MediaPricingComponent.INCLUDED_VIDEO, "120000"),
                tier(MediaPricingComponent.EXTRA_IMAGE, "0", "10", "50000"), tier(MediaPricingComponent.EXTRA_IMAGE, "10", null, "40000"),
                tier(MediaPricingComponent.EXTRA_VIDEO, "0", null, "90000"),
                fixed(PricingCategory.OUTPUT, "VIDEO", "Requested video", "300000")));
        when(policies.findEffectiveActive(any(Instant.class))).thenReturn(List.of(activePolicy));
    }

    ChecklistDefinition checklist(String id, String content, String base, int images, int videos) {
        var c = new ChecklistDefinition();
        c.setId(id); c.setContent(content); c.setIsActive(true); c.setBasePrice(new BigDecimal(base));
        c.setIncludedImageCount(images); c.setIncludedVideoCount(videos); c.setPricingProfile("STANDARD");
        return c;
    }
    ServiceChecklist row(Service s, ChecklistDefinition c, int order) {
        var r = new ServiceChecklist(); r.setId("sc-" + s.getId() + c.getId()); r.setService(s); r.setChecklist(c); r.setDisplayOrder(order); return r;
    }
    PricingRule included(MediaPricingComponent component, String price) {
        var r = new PricingRule();
        r.setCategory(PricingCategory.MEDIA); r.setPricingProfile("STANDARD"); r.setMediaComponent(component); r.setRuleKey(component.name());
        r.setDescription(component.name()); r.setCalculationType(PricingCalculationType.FIXED); r.setFixedAmount(new BigDecimal(price)); r.setEnabled(true);
        return r;
    }
    PricingRule tier(MediaPricingComponent component, String from, String to, String price) {
        var r = included(component, price);
        r.setRuleKey(component + from); r.setMinValue(new BigDecimal(from)); r.setMaxValue(to == null ? null : new BigDecimal(to));
        r.setUnitSize(component == MediaPricingComponent.EXTRA_VIDEO ? BigDecimal.ONE : BigDecimal.TEN); r.setRoundingMode(PricingRounding.CEILING);
        return r;
    }
    PricingRule fixed(PricingCategory category, String key, String description, String amount) {
        var r = new PricingRule(); r.setCategory(category); r.setRuleKey(key); r.setDescription(description);
        r.setCalculationType(PricingCalculationType.FIXED); r.setFixedAmount(new BigDecimal(amount)); r.setEnabled(true);
        return r;
    }

    @Test void customerPreviewIncludesVideoDeliverableExactlyOnceAndRemovingItRemovesTheFee() {
        var withVideo = preview.customerQuote(new Customer("s1", null, null, null, null, null,
                List.of(OrderResultFormat.VIDEO), OrderPermitStatus.NONE));
        var withoutVideo = preview.customerQuote(new Customer("s1", null));

        assertThat(withVideo.addOns()).singleElement().satisfies(fee -> {
            assertThat(fee.key()).isEqualTo("OUTPUT_VIDEO");
            assertThat(fee.description()).isEqualTo("Video bàn giao");
            assertThat(fee.amount()).isEqualByComparingTo("300000");
        });
        assertThat(withVideo.total().subtract(withoutVideo.total())).isEqualByComparingTo("300000");
        assertThat(withoutVideo.addOns()).isEmpty();
    }

    @Test void areaMonitoringQuoteExplainsTheExactTwoHundredThousandDelta() {
        inspection.setName("Giám sát khu vực");
        inspectionRows.clear();
        List<BigDecimal> defaultPrices = List.of(new BigDecimal("600000"), new BigDecimal("600000"),
                new BigDecimal("400000"), new BigDecimal("500000"), new BigDecimal("400000"));
        for (int index = 0; index < defaultPrices.size(); index++) {
            ChecklistDefinition definition = checklist("area-" + index, "Checklist " + (index + 1),
                    defaultPrices.get(index).toPlainString(), 0, 0);
            if (index == 0) {
                definition.setAreaPricingEnabled(true);
                definition.setIncludedAreaHa(BigDecimal.TEN);
            }
            inspectionRows.add(row(inspection, definition, index));
        }
        PricingRule area = fixed(PricingCategory.AREA, "UP_TO_50", "Phụ phí theo mỗi 10 ha", "100000");
        area.setCalculationType(PricingCalculationType.PER_BLOCK);
        area.setMinValue(BigDecimal.TEN);
        area.setMaxValue(new BigDecimal("50"));
        area.setUnitSize(BigDecimal.TEN);
        area.setRoundingMode(PricingRounding.CEILING);
        activePolicy.replaceRules(List.of(area,
                fixed(PricingCategory.OUTPUT, "VIDEO", "Video bàn giao", "300000")));

        var defaultQuote = preview.defaultPrice("s1");
        var customerQuote = preview.customerQuote(new Customer("s1", null, new BigDecimal("28.3"), null, null,
                null, List.of(OrderResultFormat.VIDEO), OrderPermitStatus.NONE));

        assertThat(defaultQuote.checklists()).extracting(row -> row.subtotal().intValue())
                .containsExactly(600000, 600000, 400000, 500000, 400000);
        assertThat(defaultQuote.checklistSubtotal()).isEqualByComparingTo("2500000");
        assertThat(customerQuote.checklists()).hasSize(5);
        assertThat(customerQuote.checklists().getFirst().lines()).filteredOn(line -> "AREA".equals(line.component()))
                .singleElement().satisfies(line -> {
                    assertThat(line.quantity()).isEqualByComparingTo("2");
                    assertThat(line.unitPrice()).isEqualByComparingTo("100000");
                    assertThat(line.amount()).isEqualByComparingTo("200000");
                });
        assertThat(customerQuote.checklistSubtotal()).isEqualByComparingTo("2700000");
        assertThat(customerQuote.addOns()).singleElement().satisfies(addOn -> {
            assertThat(addOn.key()).isEqualTo("OUTPUT_VIDEO");
            assertThat(addOn.amount()).isEqualByComparingTo("300000");
        });
        assertThat(customerQuote.total()).isEqualByComparingTo("3000000");
        BigDecimal displayedComponents = defaultQuote.checklistSubtotal()
                .add(new BigDecimal("200000")).add(customerQuote.addOns().getFirst().amount());
        assertThat(customerQuote.total()).isEqualByComparingTo(displayedComponents);
    }

    @Test void areaPricingRequiresAnAvailableAreaAndRecalculatesWhenItChangesOrIsCleared() {
        roof.setBasePrice(new BigDecimal("3400000"));
        roof.setAreaPricingEnabled(true);
        roof.setIncludedAreaHa(BigDecimal.TEN);
        PricingRule area = fixed(PricingCategory.AREA, "UP_TO_50", "Area surcharge", "100000");
        area.setCalculationType(PricingCalculationType.PER_BLOCK);
        area.setMinValue(BigDecimal.TEN);
        area.setMaxValue(new BigDecimal("50"));
        area.setUnitSize(BigDecimal.TEN);
        area.setRoundingMode(PricingRounding.CEILING);
        activePolicy.replaceRules(List.of(area));

        var noArea = preview.customerQuote(new Customer("s1", null));
        var thirtyHa = preview.customerQuote(new Customer("s1", null, new BigDecimal("30"), null, null));
        var fiftyHa = preview.customerQuote(new Customer("s1", null, new BigDecimal("50"), null, null));
        var cleared = preview.customerQuote(new Customer("s1", null));

        assertThat(noArea.checklists().stream().flatMap(row -> row.lines().stream()))
                .noneMatch(line -> line.category() == PricingCategory.AREA);
        assertThat(noArea.total()).isEqualByComparingTo("3500000");
        assertThat(thirtyHa.checklists().getFirst().lines()).filteredOn(line -> line.category() == PricingCategory.AREA)
                .singleElement().satisfies(line -> assertThat(line.amount()).isEqualByComparingTo("200000"));
        assertThat(thirtyHa.total()).isEqualByComparingTo("3700000");
        assertThat(fiftyHa.checklists().getFirst().lines()).filteredOn(line -> line.category() == PricingCategory.AREA)
                .singleElement().satisfies(line -> assertThat(line.amount()).isEqualByComparingTo("400000"));
        assertThat(fiftyHa.total()).isEqualByComparingTo("3900000");
        assertThat(cleared.total()).isEqualByComparingTo(noArea.total());
        assertThat(cleared.checklists().stream().flatMap(row -> row.lines().stream()))
                .noneMatch(line -> line.category() == PricingCategory.AREA);
    }

    @Test void checklistEvidenceVideoAndOrderDeliverableVideoRemainSeparateCharges() {
        var result = preview.customerQuote(new Customer("s1",
                List.of(new CustomerItem("c1", null, 10, 2), new CustomerItem("c2", null, 0, 0)),
                null, null, null, null, List.of(OrderResultFormat.VIDEO), OrderPermitStatus.NONE));

        assertThat(result.checklists().getFirst().lines()).anySatisfy(line -> {
            assertThat(line.component()).isEqualTo("EXTRA_VIDEO");
            assertThat(line.amount()).isEqualByComparingTo("90000");
        });
        assertThat(result.addOns()).singleElement().satisfies(fee -> {
            assertThat(fee.key()).isEqualTo("OUTPUT_VIDEO");
            assertThat(fee.amount()).isEqualByComparingTo("300000");
        });
    }

    @Test void previewAndPersistedOrderEngineHaveParityAndOutputIsNotMultipliedByChecklistCount() {
        var customer = preview.customerQuote(new Customer("s1", null, null, null, null, null,
                List.of(OrderResultFormat.VIDEO), OrderPermitStatus.NONE));
        var engine = new RuleBasedPricingEngine(policies, mock(CustomWorkAssessmentRepository.class),
                new ChecklistPriceCalculator());
        Order order = new Order(); order.setService(inspection); order.setResultFormats(List.of(OrderResultFormat.VIDEO));
        OrderChecklistItem roofItem = snapshot("i1", roof);
        OrderChecklistItem wallItem = snapshot("i2", wall);

        var manager = engine.calculate(order, List.of(roofItem, wallItem));

        assertThat(manager.systemCalculatedAmount()).isEqualByComparingTo(customer.total());
        assertThat(manager.items()).filteredOn(line -> line.category() == PricingCategory.OUTPUT)
                .singleElement().satisfies(line -> assertThat(line.amount()).isEqualByComparingTo("300000"));
    }

    @Test void newlyActivatedAdminOutputRateAffectsTheNextPreview() {
        var before = preview.customerQuote(new Customer("s1", null, null, null, null, null,
                List.of(OrderResultFormat.VIDEO), OrderPermitStatus.NONE));
        activePolicy.replaceRules(List.of(included(MediaPricingComponent.INCLUDED_IMAGE, "50000"),
                included(MediaPricingComponent.INCLUDED_VIDEO, "120000"),
                tier(MediaPricingComponent.EXTRA_IMAGE, "0", "10", "50000"),
                tier(MediaPricingComponent.EXTRA_IMAGE, "10", null, "40000"),
                tier(MediaPricingComponent.EXTRA_VIDEO, "0", null, "90000"),
                fixed(PricingCategory.OUTPUT, "VIDEO", "Requested video", "500000")));
        var after = preview.customerQuote(new Customer("s1", null, null, null, null, null,
                List.of(OrderResultFormat.VIDEO), OrderPermitStatus.NONE));
        assertThat(after.total().subtract(before.total())).isEqualByComparingTo("200000");
    }

    private OrderChecklistItem snapshot(String id, ChecklistDefinition definition) {
        OrderChecklistItem item = new OrderChecklistItem(); item.setId(id); item.setContent(definition.getContent());
        item.setSourceChecklist(definition); item.setSourceType(OrderChecklistSourceType.SERVICE_TEMPLATE);
        item.setReviewStatus(ChecklistReviewStatus.INCLUDED); item.setPricingSnapshotVersion(1);
        item.setBasePriceSnapshot(definition.getBasePrice()); item.setIncludedImageCountSnapshot(definition.getIncludedImageCount());
        item.setIncludedVideoCountSnapshot(definition.getIncludedVideoCount()); item.setRequestedImageCount(definition.getIncludedImageCount());
        item.setRequestedVideoCount(definition.getIncludedVideoCount()); item.setPricingProfileSnapshot(definition.getPricingProfile());
        return item;
    }

    @Test void defaultServicePriceIsOnlyTheSumOfDefaultChecklistContributions() {
        var result = preview.defaultPrice("s1");
        // roof: 200,000 + 50,000 (10 images) + 120,000 (1 video); wall: 100,000
        assertThat(result.checklists()).extracting(c -> c.subtotal().intValue()).containsExactly(370000, 100000);
        assertThat(result.checklistSubtotal()).isEqualByComparingTo("470000");
        // the legacy catalog price (1,000,000) is NOT added on top of priced checklists
        assertThat(result.priceSource()).isEqualTo("CHECKLISTS");
        assertThat(result.packageFee()).isZero();
        assertThat(result.total()).isEqualByComparingTo("470000");
        assertThat(result.policyVersion()).isEqualTo(5);
        assertThat(result.complete()).isTrue();
    }

    @Test void addingAndRemovingADefaultChecklistChangesThePriceByItsContribution() {
        BigDecimal both = preview.defaultPrice("s1").total();
        inspectionRows.remove(1);
        BigDecimal withoutWall = preview.defaultPrice("s1").total();
        assertThat(both.subtract(withoutWall)).isEqualByComparingTo("100000");
        inspectionRows.clear();
        assertThat(preview.defaultPrice("s1").total()).isZero();
    }

    @Test void adminWhatIfDoesNotNeedSavedConfigurationAndLeavesTheCatalogUntouched() {
        var result = preview.adminPreview(new Admin("s1", null, List.of(
                new AdminItem("c1", new BigDecimal("300000"), 20, 0, null, 35, null),
                new AdminItem("c2", null, null, null, null, null, null))));

        // roof: 300,000 + 50,000 included images + 15 extra images (10 @50,000 + 5 -> 1 block @40,000) = 440,000; wall 100,000
        assertThat(result.checklists().get(0).subtotal()).isEqualByComparingTo("440000");
        // there is no package fee any more: roof 440,000 + wall 100,000 are the whole default price
        assertThat(result.priceSource()).isEqualTo("CHECKLISTS");
        assertThat(result.total()).isEqualByComparingTo("540000");
        assertThat(roof.getBasePrice()).isEqualByComparingTo("200000");
        assertThat(roof.getIncludedImageCount()).isEqualTo(10);
    }

    @Test void associationOverrideAffectsOnlyThatServiceAndInheritsOtherwise() {
        inspectionRows.getFirst().setBasePriceOverride(new BigDecimal("260000"));
        inspectionRows.getFirst().setIncludedVideoCountOverride(0);
        var inspectionPrice = preview.defaultPrice("s1");
        var surveyPrice = preview.defaultPrice("s2");

        assertThat(inspectionPrice.checklists().getFirst().subtotal()).isEqualByComparingTo("310000"); // 260k + 50k, no video
        assertThat(surveyPrice.checklists().getFirst().subtotal()).isEqualByComparingTo("370000");     // inherits the definition
        assertThat(surveyPrice.packageFee()).isZero();
        assertThat(surveyPrice.total()).isEqualByComparingTo("370000");
    }

    @Test void adminPreviewReportsAChecklistThePolicyCannotPriceInsteadOfASmallerTotal() {
        PricingPolicy noTiers = new PricingPolicy(); noTiers.setId("notiers"); noTiers.setPolicyVersion(9); noTiers.setCurrency("VND"); noTiers.setStatus(PricingPolicyStatus.DRAFT);
        noTiers.replaceRules(List.of(included(MediaPricingComponent.INCLUDED_IMAGE, "50000")));
        when(policies.findById("notiers")).thenReturn(Optional.of(noTiers));
        var result = preview.adminPreview(new Admin("s1", "notiers", List.of(new AdminItem("c1", null, null, null, null, 20, null))));
        assertThat(result.complete()).isFalse();
        assertThat(result.warnings()).hasSize(1);
    }

    @Test void adminCanPreviewAgainstADraftPolicyBeforePublishing() {
        PricingPolicy draft = new PricingPolicy(); draft.setId("draft"); draft.setPolicyVersion(6); draft.setCurrency("VND"); draft.setStatus(PricingPolicyStatus.DRAFT);
        draft.replaceRules(List.of(included(MediaPricingComponent.INCLUDED_IMAGE, "70000")));
        when(policies.findById("draft")).thenReturn(Optional.of(draft));
        var result = preview.adminPreview(new Admin("s1", "draft", null));
        assertThat(result.draftPolicy()).isTrue();
        assertThat(result.checklists().getFirst().subtotal()).isEqualByComparingTo("270000");
    }

    @Test void aRepeatingOrderQuoteIsMultipliedByTheNumberOfVisits() {
        var once = preview.customerQuote(new Customer("s1", null));
        var thrice = preview.customerQuote(new Customer("s1", null, null, null, null, 3));
        assertThat(thrice.occurrences()).isEqualTo(3);
        assertThat(once.occurrences()).isEqualTo(1);
        assertThat(thrice.checklistSubtotal()).isEqualByComparingTo(once.checklistSubtotal().multiply(new BigDecimal("3")));
        assertThat(thrice.total()).isEqualByComparingTo(once.total().multiply(new BigDecimal("3")));
        // every line keeps quantity x unit price = amount
        assertThat(thrice.checklists().stream().flatMap(row -> row.lines().stream()))
                .allSatisfy(line -> assertThat(line.unitPrice().multiply(line.quantity())).isEqualByComparingTo(line.amount()));
    }

    @Test void customerQuoteUsesBackendPricesAndTheRequestedQuantities() {
        var result = preview.customerQuote(new Customer("s1", List.of(new CustomerItem("c1", null, 30, 3), new CustomerItem("c2", null, null, null))));
        // roof: 200k + 50k + 120k + (50k+40k) images + 2 extra videos x 90k
        assertThat(result.checklists().getFirst().subtotal()).isEqualByComparingTo("640000");
        assertThat(result.checklists().getFirst().extraImageCount()).isEqualTo(20);
        assertThat(result.checklists().getFirst().extraVideoCount()).isEqualTo(2);
        assertThat(result.packageFee()).isZero();
        assertThat(result.total()).isEqualByComparingTo("740000");
    }

    @Test void withoutAnyPricedChecklistTheServiceIsUnpricedAndTheCatalogPriceIsNotAFallback() {
        roof.setBasePrice(BigDecimal.ZERO); roof.setIncludedImageCount(0); roof.setIncludedVideoCount(0);
        wall.setBasePrice(BigDecimal.ZERO);
        var stored = preview.defaultPrice("s1");
        assertThat(stored.priceSource()).isEqualTo("UNPRICED");
        assertThat(stored.packageFee()).isZero();
        assertThat(stored.checklistSubtotal()).isZero();
        assertThat(stored.total()).isZero();
        var whatIf = preview.adminPreview(new Admin("s1", null, null));
        assertThat(whatIf.total()).isZero();
        var customer = preview.customerQuote(new Customer("s1", null));
        assertThat(customer.priceSource()).isEqualTo("UNPRICED");
        assertThat(customer.total()).isZero();
    }

    @Test void adminPreviewCustomerQuoteAndServiceEstimateReportTheSameDefaultTotal() {
        var resolver = new ServicePriceResolver(assignments, policies, new ChecklistPriceCalculator());
        var admin = preview.defaultPrice("s1");
        var customer = preview.customerQuote(new Customer("s1", null));
        var estimate = resolver.resolveOrLegacy(inspection);
        assertThat(customer.total()).isEqualByComparingTo(admin.total());
        assertThat(estimate.total()).isEqualByComparingTo(admin.total());
        assertThat(estimate.source()).isEqualTo(admin.priceSource());
    }

    @Test void estimateIsUnpricedWithoutAnActivePolicyInsteadOfFailing() {
        when(policies.findEffectiveActive(any(Instant.class))).thenReturn(List.of());
        var resolver = new ServicePriceResolver(assignments, policies, new ChecklistPriceCalculator());
        var estimate = resolver.resolveOrLegacy(inspection);
        assertThat(estimate.source()).isEqualTo("UNPRICED");
        assertThat(estimate.total()).isZero();
    }

    @Test void customerCanAddAKnownCatalogChecklistButNotAnUnknownOrDuplicateId() {
        assertThatThrownBy(() -> preview.customerQuote(new Customer("s1", List.of(new CustomerItem("not-in-template", null, null, null)))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CHECKLIST_NOT_FOUND));
        var thermal = checklist("c3", "Thermal inspection", "350000", 0, 0);
        when(definitions.findById("c3")).thenReturn(Optional.of(thermal));
        var selected = preview.customerQuote(new Customer("s1", List.of(new CustomerItem("c1", null, null, null),
                new CustomerItem("c3", null, null, null))));
        assertThat(selected.checklists()).extracting(c -> c.checklistId()).containsExactly("c1", "c3");
        assertThat(selected.checklists().get(1).quotePending()).isTrue();
        assertThat(selected.checklists().get(1).subtotal()).isZero();
        assertThat(selected.checklists().get(1).lines()).isEmpty();
        assertThat(selected.checklistSubtotal()).isEqualByComparingTo(selected.checklists().getFirst().subtotal());
        assertThatThrownBy(() -> preview.customerQuote(new Customer("s1", List.of(new CustomerItem("c1", null, null, null), new CustomerItem("c1", null, null, null)))))
                .isInstanceOf(ApiException.class);
    }

    @Test void customChecklistWithoutAPolicyPriceIsMarkedForManagerQuoteInsteadOfBeingRejectedOrFree() {
        var result = preview.customerQuote(new Customer("s1", List.of(new CustomerItem("c1", null, null, null),
                new CustomerItem(null, "Check the antenna mast", 2, null))));
        assertThat(result.quotePending()).isTrue();
        var custom = result.checklists().get(1);
        assertThat(custom.quotePending()).isTrue();
        assertThat(custom.custom()).isTrue();
        assertThat(custom.lines()).isEmpty();
        // the catalog checklist keeps its price; the pending one adds nothing until the Manager quotes it
        assertThat(result.checklistSubtotal()).isEqualByComparingTo(result.checklists().getFirst().subtotal());
    }

    @Test void requiredChecklistsStayInTheCustomerSelection() {
        inspectionRows.get(1).setRequired(true);
        assertThatThrownBy(() -> preview.customerQuote(new Customer("s1", List.of(new CustomerItem("c1", null, null, null)))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORDER_CHECKLIST_REQUIRED_MISSING));
    }
}
