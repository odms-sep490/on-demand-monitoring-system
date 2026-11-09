package com.ondemandmonitoring.pricing.service;

import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import com.ondemandmonitoring.pricing.dto.PricingBreakdown;
import java.util.List;

public interface PricingEngine {
    PricingBreakdown calculate(Order order, List<OrderChecklistItem> checklistItems);
}
