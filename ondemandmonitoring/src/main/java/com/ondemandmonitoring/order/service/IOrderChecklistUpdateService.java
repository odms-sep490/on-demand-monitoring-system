package com.ondemandmonitoring.order.service;

import com.ondemandmonitoring.order.dto.request.OrderChecklistUpdateRequest;
import com.ondemandmonitoring.order.dto.response.OrderCreateResponse;

public interface IOrderChecklistUpdateService {
    OrderCreateResponse updateChecklist(String orderId, OrderChecklistUpdateRequest request);
}

