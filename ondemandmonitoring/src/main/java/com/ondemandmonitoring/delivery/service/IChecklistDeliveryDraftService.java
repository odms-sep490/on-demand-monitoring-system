package com.ondemandmonitoring.delivery.service;

import com.ondemandmonitoring.delivery.dto.request.SaveChecklistDeliveryDraftRequest;
import com.ondemandmonitoring.delivery.dto.response.ChecklistDeliveryDraftResponse;

public interface IChecklistDeliveryDraftService {
    ChecklistDeliveryDraftResponse get(String orderId);
    ChecklistDeliveryDraftResponse save(String orderId, SaveChecklistDeliveryDraftRequest request);
    void clear(String orderId, long expectedDraftVersion);
    void clearAfterPublish(String orderId);
}
