package com.ondemandmonitoring.delivery.service;

import com.ondemandmonitoring.delivery.dto.request.SaveChecklistDeliveryDraftRequest;
import com.ondemandmonitoring.delivery.dto.response.ChecklistDeliveryDraftResponse;

public interface IChecklistDeliveryDraftService {
    ChecklistDeliveryDraftResponse get(String orderId);
    ChecklistDeliveryDraftResponse save(String orderId, SaveChecklistDeliveryDraftRequest request);
    void clear(String orderId, long expectedDraftVersion);
    void clearAfterPublish(String orderId);
    ChecklistDeliveryDraftResponse get(String orderId, String missionId);
    ChecklistDeliveryDraftResponse save(String orderId, SaveChecklistDeliveryDraftRequest request, String missionId);
    void clear(String orderId, long expectedDraftVersion, String missionId);
    void clearAfterPublish(String orderId, String missionId);
}
