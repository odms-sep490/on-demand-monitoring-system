package com.ondemandmonitoring.order.mapper;

import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import com.ondemandmonitoring.order.dto.response.OrderChecklistItemResponse;
import com.ondemandmonitoring.order.dto.response.CustomerOrderChecklistItemResponse;
import com.ondemandmonitoring.order.enums.ChecklistPricingStatus;
import com.ondemandmonitoring.order.enums.OrderChecklistSourceType;
import org.mapstruct.*;

@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public interface OrderChecklistItemMapper {
    @Mapping(source = "sourceChecklist.id", target = "sourceChecklistId")
    @Mapping(source = "sourceChecklist.version", target = "sourceChecklistVersion")
    @Mapping(target = "description", source = "descriptionSnapshot")
    @Mapping(target = "executionSteps", source = "executionStepsSnapshot")
    @Mapping(target = "inputRequirements", source = "inputRequirementsSnapshot")
    @Mapping(target = "expectedOutputs", source = "expectedOutputsSnapshot")
    OrderChecklistItemResponse toResponse(OrderChecklistItem item);

    @Mapping(source = "sourceChecklist.id", target = "sourceChecklistId")
    @Mapping(source = "sourceChecklist.version", target = "sourceChecklistVersion")
    @Mapping(target = "description", source = "descriptionSnapshot")
    @Mapping(target = "executionSteps", source = "executionStepsSnapshot")
    @Mapping(target = "inputRequirements", source = "inputRequirementsSnapshot")
    @Mapping(target = "expectedOutputs", source = "expectedOutputsSnapshot")
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
