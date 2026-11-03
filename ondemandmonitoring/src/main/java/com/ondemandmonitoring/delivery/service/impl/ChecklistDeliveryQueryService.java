package com.ondemandmonitoring.delivery.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.delivery.domain.OrderDelivery;
import com.ondemandmonitoring.delivery.domain.ResultReviewRound;
import com.ondemandmonitoring.delivery.domain.ResultReviewRoundEvidence;
import com.ondemandmonitoring.delivery.domain.ResultReviewRoundItem;
import com.ondemandmonitoring.delivery.dto.response.ManagerDeliveryChecklistsResponse;
import com.ondemandmonitoring.delivery.enums.DeliveryStatus;
import com.ondemandmonitoring.delivery.repository.OrderDeliveryRepository;
import com.ondemandmonitoring.delivery.repository.ResultReviewRoundEvidenceRepository;
import com.ondemandmonitoring.delivery.repository.ResultReviewRoundItemRepository;
import com.ondemandmonitoring.delivery.repository.ResultReviewRoundRepository;
import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.service.IMediaObjectStorage;
import com.ondemandmonitoring.media.service.IResultRetentionAccessService;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.domain.MissionChecklistEvidence;
import com.ondemandmonitoring.mission.enums.MissionResultApprovalStatus;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.repository.MissionChecklistEvidenceRepository;
import com.ondemandmonitoring.mission.repository.MissionResultRepository;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.mission.service.impl.ChecklistEvidencePolicy;
import com.ondemandmonitoring.mission.service.impl.ExecutionPlanCoverageService;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import com.ondemandmonitoring.order.repository.OrderChecklistItemRepository;
import com.ondemandmonitoring.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChecklistDeliveryQueryService {
    private final OrderRepository orders;
    private final com.ondemandmonitoring.delivery.repository.ResultRevisionCaseRepository revisionCases;
    private final com.ondemandmonitoring.delivery.repository.ResultRevisionCaseItemRepository revisionCaseItems;
    private final OrderChecklistItemRepository checklistItems;
    private final MissionRepository missions;
    private final MissionResultRepository missionResults;
    private final MissionChecklistEvidenceRepository missionEvidence;
    private final OrderDeliveryRepository deliveries;
    private final ResultReviewRoundRepository rounds;
    private final ResultReviewRoundItemRepository roundItems;
    private final ResultReviewRoundEvidenceRepository roundEvidence;
    private final ChecklistEvidencePolicy evidencePolicy;
    private final IResultRetentionAccessService retentionAccess;
    private final IMediaObjectStorage storage;

    public ManagerDeliveryChecklistsResponse get(String orderId) {
        Order order = orders.findById(orderId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        OrderDelivery delivery = deliveries.findByOrderId(orderId).orElse(null);
        List<OrderChecklistItem> items = checklistItems.findAllByOrderIdOrderByDisplayOrderAscIdAsc(orderId);
        List<Mission> effectiveMissions = missions.findAllByOrderIdOrderByScheduledStartAtAscIdAsc(orderId).stream()
                .filter(ExecutionPlanCoverageService::effective).toList();
        List<String> effectiveMissionIds = effectiveMissions.stream().map(Mission::getId).toList();
        Set<String> approvedMissionIds = effectiveMissionIds.isEmpty() ? Set.of() : missionResults.findByMissionIds(effectiveMissionIds)
                .stream().filter(result -> result.getApprovalStatus() == MissionResultApprovalStatus.APPROVED
                        && result.getStatus() != com.ondemandmonitoring.mission.enums.MissionResultStatus.FAILED)
                .map(result -> result.getMission().getId()).collect(Collectors.toSet());
        Map<String, String> missionCodes = effectiveMissions.stream().collect(Collectors.toMap(Mission::getId, Mission::getMissionCode));

        Map<String, Map<String, MissionChecklistEvidence>> attachedByChecklist = new LinkedHashMap<>();
        for (Mission mission : effectiveMissions) {
            if (mission.getStatus() != MissionStatus.COMPLETED || !approvedMissionIds.contains(mission.getId())) continue;
            for (MissionChecklistEvidence link : missionEvidence.findActiveByMissionId(mission.getId())) {
                String checklistItemId = link.getMissionChecklistExecution().getOrderChecklistItemId();
                attachedByChecklist.computeIfAbsent(checklistItemId, ignored -> new LinkedHashMap<>())
                        .putIfAbsent(link.getMediaAsset().getId(), link);
            }
        }

        Map<String, List<ResultReviewRoundEvidence>> currentEvidenceByChecklist = new HashMap<>();
        ResultReviewRound currentRound = delivery == null || delivery.getCurrentReviewRoundId() == null ? null
                : rounds.findById(delivery.getCurrentReviewRoundId()).orElse(null);
        if (currentRound != null) {
            List<ResultReviewRoundItem> rows = roundItems.findByRoundIdOrderByDisplayOrderAsc(currentRound.getId());
            if (!rows.isEmpty()) {
                roundEvidence.findByRoundItemIdInOrderByRoundItemIdAscCreatedAtAsc(rows.stream().map(ResultReviewRoundItem::getId).toList())
                        .forEach(evidence -> currentEvidenceByChecklist.computeIfAbsent(
                                evidence.getRoundItem().getOrderChecklistItem().getId(), ignored -> new ArrayList<>()).add(evidence));
            }
        }

        var activeCase = revisionCases.findByOrderIdAndActiveTrue(orderId).orElse(null);
        Map<String, com.ondemandmonitoring.delivery.domain.ResultRevisionCaseItem> remediation = activeCase == null
                ? Map.of() : revisionCaseItems.findByResultRevisionCaseIdOrderByCreatedAtAsc(activeCase.getId()).stream()
                        .collect(Collectors.toMap(row -> row.getOrderChecklistItem().getId(), row -> row));
        Instant expiresAt = Instant.now().plusSeconds(storage.presignedUrlExpiresSeconds());
        List<ManagerDeliveryChecklistsResponse.Checklist> mapped = items.stream().map(item -> {
            Map<String, MissionChecklistEvidence> attached = attachedByChecklist.getOrDefault(item.getId(), Map.of());
            Map<String, ResultReviewRoundEvidence> currentByMedia = currentEvidenceByChecklist
                    .getOrDefault(item.getId(), List.of()).stream()
                    .collect(Collectors.toMap(evidence -> evidence.getMediaAsset().getId(), evidence -> evidence, (left, right) -> left));
            List<ManagerDeliveryChecklistsResponse.Asset> candidates = attached.values().stream()
                    .filter(link -> ReworkDeliveryEvidencePolicy.allows(remediation.get(item.getId()), link.getMissionId()))
                    .filter(link -> evidencePolicy.mediaTypeMatches(item.getRequiredEvidenceType(), link.getMediaAsset().getType()))
                    .filter(link -> evidencePolicy.ineligibilityReason(link.getMediaAsset(), true) == null)
                    .map(link -> mapAsset(link.getMediaAsset(), link.getMissionId(), missionCodes.get(link.getMissionId()),
                            currentByMedia.get(link.getMediaAsset().getId()), expiresAt))
                    .toList();
            List<ManagerDeliveryChecklistsResponse.Asset> current = currentEvidenceByChecklist
                    .getOrDefault(item.getId(), List.of()).stream()
                    .map(evidence -> mapAsset(evidence.getMediaAsset(), evidence.getMediaAsset().getMissionId(),
                            missionCodes.get(evidence.getMediaAsset().getMissionId()), evidence, expiresAt))
                    .toList();
            return new ManagerDeliveryChecklistsResponse.Checklist(item.getId(), item.getContent(),
                    item.getMinimumImageCount(), item.getMinimumVideoCount(), item.getMinimumEvidenceCount(),
                    item.getRequiredEvidenceType(), candidates, current);
        }).toList();

        DeliveryStatus status = delivery == null ? DeliveryStatus.READY_FOR_MANAGER_REVIEW : delivery.getStatus();
        return new ManagerDeliveryChecklistsResponse(order.getId(), status, delivery == null ? null : delivery.getVersion(),
                delivery == null ? null : delivery.getCurrentReviewRoundId(), currentRound == null ? null : currentRound.getVersion(), mapped);
    }

    private ManagerDeliveryChecklistsResponse.Asset mapAsset(MediaAsset asset, String missionId, String missionCode,
            ResultReviewRoundEvidence currentEvidence, Instant expiresAt) {
        String url = null;
        String reason = null;
        try {
            retentionAccess.requireAccessible(asset);
            url = storage.createPresignedGetUrl(asset.getS3Bucket(), asset.getS3Key());
        } catch (ApiException ex) {
            if (ex.getErrorCode() == ErrorCode.RESULT_RETENTION_EXPIRED) reason = "RETENTION_EXPIRED";
            else if (ex.getErrorCode() == ErrorCode.MEDIA_NOT_FOUND) reason = "MEDIA_UNAVAILABLE";
            else throw ex;
        }
        return new ManagerDeliveryChecklistsResponse.Asset(currentEvidence == null ? null : currentEvidence.getId(),
                asset.getId(), asset.getType(), asset.getOriginalFileName(), asset.getFileSize(), asset.getCapturedAt(),
                missionId, missionCode, url, url == null ? null : expiresAt, reason,
                currentEvidence == null ? null : currentEvidence.getChangeType());
    }
}
