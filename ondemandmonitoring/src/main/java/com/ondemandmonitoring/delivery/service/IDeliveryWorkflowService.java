package com.ondemandmonitoring.delivery.service;

import com.ondemandmonitoring.delivery.dto.request.ReleasePreviewRequest;
import com.ondemandmonitoring.delivery.dto.request.RevisionRequest;
import com.ondemandmonitoring.delivery.dto.response.DeliveryResponse;
import java.util.List;

/**
 * Coordinates result review, final payment eligibility, and media release.
 *
 * <p>Implementations must preserve the delivery state machine and verify access
 * before exposing preview or original media.</p>
 */
public interface IDeliveryWorkflowService {
    void markProcessing(String orderId);
    void markReadyForManagerReview(String orderId);
    void confirmFinalPayment(String orderId);
    void requireFinalPaymentEligible(String orderId);
    void requireOriginalAccess(String orderId);
    List<String> originalAccessibleMissionIds(List<String> missionIds);
    DeliveryResponse get(String orderId);
    DeliveryResponse previews(String orderId);
    DeliveryResponse originals(String orderId);
    DeliveryResponse releasePreview(String orderId, ReleasePreviewRequest request);
    DeliveryResponse acceptResult(String orderId);
    DeliveryResponse requestRevision(String orderId, RevisionRequest request);
    DeliveryResponse releaseOriginals(String orderId);
}
