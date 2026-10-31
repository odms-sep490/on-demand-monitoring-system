package com.ondemandmonitoring.order.mapper;

import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import com.ondemandmonitoring.order.dto.response.OrderChecklistItemResponse;
import com.ondemandmonitoring.order.dto.response.CustomerOrderChecklistItemResponse;
import com.ondemandmonitoring.order.enums.ChecklistPricingStatus;
import com.ondemandmonitoring.order.enums.OrderChecklistSourceType;
import org.mapstruct.*;

@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true), imports = OrderChecklistSourceType.class)
public interface OrderChecklistItemMapper {
    @Mapping(source = "sourceChecklist.id", target = "sourceChecklistId")
    @Mapping(source = "sourceChecklist.version", target = "sourceChecklistVersion")
    @Mapping(target = "description", source = "effectiveDescription")
    @Mapping(target = "executionSteps", source = "effectiveExecutionSteps")
    @Mapping(target = "inputRequirements", source = "effectiveInputRequirements")
    @Mapping(target = "expectedOutputs", source = "effectiveExpectedOutputs")
    @Mapping(target = "guidanceUpdatedById", source = "guidanceUpdatedBy.id")
    @Mapping(target = "canEditGuidance", ignore = true)
    @Mapping(target = "guidanceBlockedReason", ignore = true)
    OrderChecklistItemResponse toResponse(OrderChecklistItem item);

    @Mapping(source = "sourceChecklist.id", target = "sourceChecklistId")
    @Mapping(source = "sourceChecklist.version", target = "sourceChecklistVersion")
    // Custom text is enriched from the published/accepted quote snapshot by the service.
    @Mapping(target = "description", expression = "java(item.getSourceType() == OrderChecklistSourceType.CUSTOMER_CUSTOM ? null : item.getDescriptionSnapshot())")
    @Mapping(target = "executionSteps", expression = "java(item.getSourceType() == OrderChecklistSourceType.CUSTOMER_CUSTOM ? null : item.getExecutionStepsSnapshot())")
    @Mapping(target = "inputRequirements", expression = "java(item.getSourceType() == OrderChecklistSourceType.CUSTOMER_CUSTOM ? null : item.getInputRequirementsSnapshot())")
    @Mapping(target = "expectedOutputs", expression = "java(item.getSourceType() == OrderChecklistSourceType.CUSTOMER_CUSTOM ? null : item.getExpectedOutputsSnapshot())")
    @Mapping(target = "guidanceUpdatedById", source = "guidanceUpdatedBy.id")
    @Mapping(target = "canEditGuidance", ignore = true)
    @Mapping(target = "guidanceBlockedReason", ignore = true)
    @Mapping(target = "pricingStatus", expression = "java(customerPricingStatus(item))")
    @Mapping(target = "priceAvailable", expression = "java(customerPriceAvailable(item))")
    @Mapping(target = "approvedPrice", expression = "java(customerApprovedPrice(item))")
    CustomerOrderChecklistItemResponse toCustomerResponse(OrderChecklistItem item);

    default ChecklistPricingStatus customerPricingStatus(OrderChecklistItem item) {
        if (item.getPricingStatus() != null) return item.getPricingStatus();
        if (item.getSourceType() == OrderChecklistSourceType.SERVICE_TEMPLATE) return ChecklistPricingStatus.AUTO_PRICED;
        return ChecklistPricingStatus.NEEDS_MANAGER_REVIEW;
    }

    default boolean customerPriceAvailable(OrderChecklistItem item) {
        ChecklistPricingStatus status = customerPricingStatus(item);
        return status == ChecklistPricingStatus.AUTO_PRICED
                || status == ChecklistPricingStatus.APPROVED
                || status == ChecklistPricingStatus.ADJUSTED_AND_APPROVED
                || status == ChecklistPricingStatus.DUPLICATE
                || status == ChecklistPricingStatus.REJECTED;
    }

    default java.math.BigDecimal customerApprovedPrice(OrderChecklistItem item) {
        if (!customerPriceAvailable(item)) return null;
        if (item.getSourceType() == OrderChecklistSourceType.SERVICE_TEMPLATE)
            return item.getSystemCalculatedPrice();
        if (customerPricingStatus(item) == ChecklistPricingStatus.DUPLICATE
                || customerPricingStatus(item) == ChecklistPricingStatus.REJECTED)
            return java.math.BigDecimal.ZERO;
        return item.getFinalAmount();
    }
}
