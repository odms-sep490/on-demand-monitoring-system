package com.ondemandmonitoring.order.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import com.ondemandmonitoring.order.enums.ChecklistPricingStatus;
import com.ondemandmonitoring.order.enums.ChecklistReviewStatus;
import com.ondemandmonitoring.order.enums.OrderChecklistSourceType;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class CustomerChecklistPricingProjectionTest {
    private final OrderChecklistItemMapper mapper = Mappers.getMapper(OrderChecklistItemMapper.class);

    @Test void unresolvedCustomerItemHidesEveryInternalAmount() throws Exception {
        OrderChecklistItem item = customerItem();
        item.setPricingStatus(ChecklistPricingStatus.NEEDS_MANAGER_REVIEW);

        var response = mapper.toCustomerResponse(item);
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(response);

        assertThat(response.isPriceAvailable()).isFalse();
        assertThat(response.getApprovedPrice()).isNull();
        assertThat(json).doesNotContain("systemCalculated", "managerApproved", "difference",
                "adjustmentReason", "riskFlags", "pricingAudit");
    }

    @Test void approvedCustomerItemExposesOnlyTheFinalCommercialPrice() throws Exception {
        OrderChecklistItem item = customerItem();
        item.setPricingStatus(ChecklistPricingStatus.ADJUSTED_AND_APPROVED);

        var response = mapper.toCustomerResponse(item);
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(response);

        assertThat(response.isPriceAvailable()).isTrue();
        assertThat(response.getApprovedPrice()).isEqualByComparingTo("800000");
        assertThat(json).contains("\"approvedPrice\":800000").doesNotContain("880000", "-80000", "Scope changed");
    }

    private OrderChecklistItem customerItem() {
        OrderChecklistItem item = new OrderChecklistItem();
        item.setId("item-1");
        item.setContent("Inspect rear roof");
        item.setSourceType(OrderChecklistSourceType.CUSTOMER_CUSTOM);
        item.setReviewStatus(ChecklistReviewStatus.ADDITIONAL);
        item.setSystemCalculatedPrice(new BigDecimal("880000"));
        item.setManagerApprovedAmount(new BigDecimal("800000"));
        item.setFinalAmount(new BigDecimal("800000"));
        item.setPriceDifferenceAmount(new BigDecimal("-80000"));
        item.setManagerPriceAdjustmentReason("Scope changed");
        return item;
    }
}
