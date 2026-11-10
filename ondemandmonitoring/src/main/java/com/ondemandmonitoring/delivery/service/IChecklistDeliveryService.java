package com.ondemandmonitoring.delivery.service;

import com.ondemandmonitoring.delivery.dto.request.ChecklistDeliveryPublishRequest;
import com.ondemandmonitoring.delivery.dto.response.DeliveryResponse;
import com.ondemandmonitoring.delivery.dto.response.ManagerDeliveryChecklistsResponse;

public interface IChecklistDeliveryService {
    ManagerDeliveryChecklistsResponse get(String orderId);
    ManagerDeliveryChecklistsResponse get(String orderId, String missionId);
    DeliveryResponse publish(String orderId, ChecklistDeliveryPublishRequest request, String missionId);
    DeliveryResponse publish(String orderId, ChecklistDeliveryPublishRequest request);
}
