package com.ondemandmonitoring.order.service;

import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.dto.request.OrderChecklistItemRequest;
import com.ondemandmonitoring.order.dto.response.CustomerOrderChecklistItemResponse;
import com.ondemandmonitoring.order.dto.response.OrderCreateResponse;
import java.util.List;
import java.util.Map;

public interface IOrderChecklistSnapshotService {
    List<CustomerOrderChecklistItemResponse> capture(Order order, List<OrderChecklistItemRequest> items);
    /** Reprices the already-frozen checklist snapshots after an editable order pricing input changes. */
    void recalculate(Order order);
    Map<String, List<CustomerOrderChecklistItemResponse>> getByOrders(List<String> orderIds);
    default void enrichOrderResponse(Order order, OrderCreateResponse response, boolean includeItemRequiredMetadata) {}
}
