package com.ondemandmonitoring.delivery.service;

import com.ondemandmonitoring.delivery.dto.request.ReleasePreviewRequest;
import com.ondemandmonitoring.delivery.dto.request.ChecklistDeliveryPublishRequest;
import com.ondemandmonitoring.delivery.dto.request.RevisionRequest;
import com.ondemandmonitoring.delivery.dto.response.DeliveryResponse;
import java.util.List;
import java.util.Set;

/**
 * Coordinates result review and media release after the Order payment gate.
 *
 * <p>Implementations must preserve the delivery state machine and verify access
 * before exposing preview or original media.</p>
 */
public interface IDeliveryWorkflowService {
    void markProcessing(String orderId);
    void markReadyForManagerReview(String orderId);
    /** Publishes frozen inspector selections when the complete order/remediation scope is ready. */
    boolean publishInspectedResults(String orderId);
    List<com.ondemandmonitoring.delivery.dto.response.MissionDeliverySummary> missionDeliveries(String orderId);
    DeliveryResponse get(String orderId, String missionId);
    DeliveryResponse previews(String orderId, String missionId);
    DeliveryResponse originals(String orderId, String missionId);
    DeliveryResponse releaseOriginals(String orderId, String missionId);
    DeliveryResponse releaseChecklistPreview(String orderId, ChecklistDeliveryPublishRequest request, String missionId);
    void requireOriginalAccessForMedia(String orderId, String mediaId);
    void requireOriginalAccess(String orderId);
    List<String> originalAccessibleMissionIds(List<String> missionIds);
    Set<String> publishedMediaAssetIds(List<String> missionIds);
    DeliveryResponse get(String orderId);
    DeliveryResponse previews(String orderId);
    DeliveryResponse originals(String orderId);
    DeliveryResponse releasePreview(String orderId, ReleasePreviewRequest request);
    DeliveryResponse releaseChecklistPreview(String orderId, ChecklistDeliveryPublishRequest request);
    DeliveryResponse acceptResult(String orderId, String expectedReviewRoundId, Long expectedRoundVersion);
    void completeAcceptedRevisionCase(String orderId);
    DeliveryResponse requestRevision(String orderId, RevisionRequest request);
    DeliveryResponse releaseOriginals(String orderId);
}
