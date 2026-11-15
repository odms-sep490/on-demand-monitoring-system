package com.ondemandmonitoring.pricing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ondemandmonitoring.checklist.domain.ChecklistDefinition;
import com.ondemandmonitoring.checklist.domain.ServiceChecklist;
import com.ondemandmonitoring.checklist.repository.ChecklistDefinitionRepository;
import com.ondemandmonitoring.checklist.repository.ServiceChecklistRepository;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.order.enums.OrderPermitStatus;
import com.ondemandmonitoring.order.enums.OrderResultFormat;
import com.ondemandmonitoring.pricing.dto.ChecklistPricingPreviewRequests.Customer;
import com.ondemandmonitoring.pricing.dto.ChecklistPricingPreviewRequests.CustomerItem;
import com.ondemandmonitoring.pricing.repository.PricingPolicyRepository;
import com.ondemandmonitoring.service.domain.Service;
import com.ondemandmonitoring.service.repository.ServiceRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChecklistPricingPreviewServiceTest {
    ServiceRepository services = mock(ServiceRepository.class);
    ChecklistDefinitionRepository definitions = mock(ChecklistDefinitionRepository.class);
    ServiceChecklistRepository assignments = mock(ServiceChecklistRepository.class);
    PricingPolicyRepository policies = mock(PricingPolicyRepository.class);
    ChecklistPricingPreviewService preview = new ChecklistPricingPreviewService(services, definitions, assignments,
            policies, new ChecklistPriceCalculator());
    Service service;
    ChecklistDefinition roof;
    ChecklistDefinition wall;
    List<ServiceChecklist> defaults;

    @BeforeEach
    void setup() {
        service = Service.builder().name("Inspection").isActive(true).basePrice(new BigDecimal("1000000")).build();
        service.setId("s1");
        roof = checklist("c1", "Roof", "2000000");
        wall = checklist("c2", "Wall", "3000000");
        defaults = List.of(row(roof, 0), row(wall, 1));

        when(services.findById("s1")).thenReturn(Optional.of(service));
        when(assignments.findAllByServiceIdOrderByDisplayOrderAscIdAsc("s1")).thenReturn(defaults);
        when(assignments.findAllByServiceIdAndChecklistIsActiveTrueOrderByDisplayOrderAscIdAsc("s1"))
                .thenReturn(defaults);
        when(definitions.findById("c1")).thenReturn(Optional.of(roof));
        when(definitions.findById("c2")).thenReturn(Optional.of(wall));
        when(policies.findEffectiveActive(org.mockito.ArgumentMatchers.any(Instant.class))).thenReturn(List.of());
    }

    @Test
    void customerGetsBackendAreaFormulaWithoutAnAdvancedPricingPolicy() {
        var result = preview.customerQuote(new Customer("s1", List.of(
                new CustomerItem("c1", null, 25, 3), new CustomerItem("c2", null, null, null)),
                new BigDecimal("0.015"), null, null));

        assertThat(result.policyId()).isNull();
        assertThat(result.policyVersion()).isZero();
        assertThat(result.currency()).isEqualTo("VND");
        assertThat(result.checklists()).extracting(row -> row.subtotal())
                .containsExactly(new BigDecimal("3000000"), new BigDecimal("4500000"));
        assertThat(result.total()).isEqualByComparingTo("7500000");
        assertThat(result.checklists()).allSatisfy(row -> {
            assertThat(row.basePrice()).isPositive();
            assertThat(row.standardAreaM2()).isEqualByComparingTo("100");
            assertThat(row.actualAreaM2()).isEqualByComparingTo("150.000");
            assertThat(row.calculationDisplay()).contains("150");
        });
        assertThat(result.addOns()).isEmpty();
    }

    @Test
    void serviceDefaultAndCustomerBaselineUseTheSameChecklistTotalWithoutPolicy() {
        var defaultPrice = preview.defaultPrice("s1");
        var customer = preview.customerQuote(new Customer("s1", null, null, null, null, null,
                List.of(OrderResultFormat.VIDEO), OrderPermitStatus.NEED_SUPPORT));

        assertThat(defaultPrice.total()).isEqualByComparingTo("5000000");
        assertThat(defaultPrice.policyId()).isNull();
        assertThat(customer.total()).isEqualByComparingTo(defaultPrice.total());
        assertThat(customer.addOns()).isEmpty();
        assertThat(defaultPrice.checklists()).extracting(row -> row.calculationDisplay())
                .allSatisfy(formula -> assertThat(formula).contains("không phát sinh phụ phí diện tích"));
    }

    @Test
    void zeroActualAreaIsRejectedByTheBackendFormula() {
        assertThatThrownBy(() -> preview.customerQuote(new Customer("s1", null, BigDecimal.ZERO, null, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PRICING_CALCULATION_FAILED));
    }

    @Test
    void configuredCatalogPricesDoNotChangeWhenEvidenceCountsOrPoliciesChange() {
        BigDecimal before = preview.customerQuote(new Customer("s1", List.of(
                new CustomerItem("c1", null, 0, 0)))).total();
        roof.setIncludedImageCount(50);
        roof.setIncludedVideoCount(40);
        when(policies.findEffectiveActive(org.mockito.ArgumentMatchers.any(Instant.class)))
                .thenReturn(List.of());

        BigDecimal after = preview.customerQuote(new Customer("s1", List.of(
                new CustomerItem("c1", null, 500, 500)))).total();

        assertThat(before).isEqualByComparingTo("2000000");
        assertThat(after).isEqualByComparingTo(before);
    }

    @Test
    void requiredBaseChecklistCannotBeRemovedFromTheCustomerSelection() {
        defaults.get(1).setRequired(true);
        assertThatThrownBy(() -> preview.customerQuote(new Customer("s1", List.of(
                new CustomerItem("c1", null, null, null)))))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.ORDER_CHECKLIST_REQUIRED_MISSING));
    }

    private ChecklistDefinition checklist(String id, String name, String basePrice) {
        ChecklistDefinition definition = new ChecklistDefinition();
        definition.setId(id);
        definition.setContent(name);
        definition.setIsActive(true);
        definition.setBasePrice(new BigDecimal(basePrice));
        definition.setStandardAreaM2(new BigDecimal("100"));
        definition.setIncludedImageCount(0);
        definition.setIncludedVideoCount(0);
        return definition;
    }

    private ServiceChecklist row(ChecklistDefinition definition, int displayOrder) {
        ServiceChecklist row = new ServiceChecklist();
        row.setId("sc-" + definition.getId());
        row.setService(service);
        row.setChecklist(definition);
        row.setDisplayOrder(displayOrder);
        return row;
    }
}
