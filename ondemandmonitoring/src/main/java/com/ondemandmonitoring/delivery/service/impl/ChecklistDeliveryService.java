package com.ondemandmonitoring.delivery.service.impl;

import com.ondemandmonitoring.delivery.dto.request.ChecklistDeliveryPublishRequest;
import com.ondemandmonitoring.delivery.dto.response.DeliveryResponse;
import com.ondemandmonitoring.delivery.dto.response.ManagerDeliveryChecklistsResponse;
import com.ondemandmonitoring.delivery.service.IChecklistDeliveryService;
import com.ondemandmonitoring.delivery.service.IDeliveryWorkflowService;
import com.ondemandmonitoring.delivery.service.IChecklistDeliveryDraftService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChecklistDeliveryService implements IChecklistDeliveryService {
    private final ChecklistDeliveryQueryService query;
    private final IDeliveryWorkflowService delivery;
    private final IChecklistDeliveryDraftService drafts;

    @Override
    public ManagerDeliveryChecklistsResponse get(String orderId) {
        return query.get(orderId);
    }

    @Override
    @Transactional
    public DeliveryResponse publish(String orderId, ChecklistDeliveryPublishRequest request) {
        DeliveryResponse response = delivery.releaseChecklistPreview(orderId, request);
        drafts.clearAfterPublish(orderId);
        return response;
    }
}
