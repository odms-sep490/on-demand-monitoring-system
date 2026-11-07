package com.ondemandmonitoring.delivery.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.delivery.domain.OrderDelivery;
import com.ondemandmonitoring.delivery.dto.request.ReleasePreviewRequest;
import com.ondemandmonitoring.delivery.dto.request.ChecklistDeliveryPublishRequest;
import com.ondemandmonitoring.delivery.dto.request.RevisionRequest;
import com.ondemandmonitoring.delivery.dto.response.DeliveryAssetResponse;
import com.ondemandmonitoring.delivery.dto.response.DeliveryResponse;
import com.ondemandmonitoring.delivery.enums.DeliveryStatus;
import com.ondemandmonitoring.delivery.repository.OrderDeliveryRepository;
import com.ondemandmonitoring.delivery.repository.ResultReviewRoundItemRepository;
import com.ondemandmonitoring.delivery.repository.ResultRevisionCaseRepository;
import com.ondemandmonitoring.delivery.repository.ResultRevisionCaseItemRepository;
import com.ondemandmonitoring.delivery.service.IDeliveryWorkflowService;
import com.ondemandmonitoring.finance.domain.Invoice;
import com.ondemandmonitoring.finance.enums.InvoiceStatus;
import com.ondemandmonitoring.finance.enums.QuoteStatus;
import com.ondemandmonitoring.finance.repository.InvoiceRepository;
import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.enums.MediaStatus;
import com.ondemandmonitoring.media.repository.MediaAssetRepository;
import com.ondemandmonitoring.media.service.IMediaObjectStorage;
import com.ondemandmonitoring.media.service.impl.MediaDerivativeService;
import com.ondemandmonitoring.media.service.IResultRetentionAccessService;
import com.ondemandmonitoring.mission.domain.MissionChecklistEvidence;
import com.ondemandmonitoring.mission.repository.MissionChecklistEvidenceRepository;
import com.ondemandmonitoring.mission.service.impl.ChecklistEvidencePolicy;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import com.ondemandmonitoring.order.enums.RequiredEvidenceType;
import com.ondemandmonitoring.order.repository.OrderChecklistItemRepository;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.mission.enums.MissionResultApprovalStatus;
import com.ondemandmonitoring.mission.repository.MissionResultRepository;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.enums.OrderStatus;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.time.Instant;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.experimental.NonFinal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional implementation of the order delivery state machine.
 *
 * <p>The service is the single authority for advancing delivery state. It also
 * prevents original media from being exposed before final payment and customer
 * acceptance, and retains explicit release for legacy recovery.</p>
 */
@Service
@RequiredArgsConstructor(onConstructor_ = @org.springframework.beans.factory.annotation.Autowired)
public class DeliveryWorkflowServiceImpl implements IDeliveryWorkflowService {
    private final OrderDeliveryRepository deliveries;
    private final OrderRepository orders;
    private final MissionRepository missions;
    private final MissionResultRepository missionResults;
    private final InvoiceRepository invoices;
    private final MediaAssetRepository media;
    private final IMediaObjectStorage storage;
    private final MediaDerivativeService derivatives;
    private final IResultRetentionAccessService retentionAccess;
    private final AuthenticatedUserResolver currentUser;
    private final com.ondemandmonitoring.order.service.IOrderExecutionCompletionPolicy completionPolicy;
    private final ResultReviewRoundPublisher reviewRoundPublisher;
    private final com.ondemandmonitoring.delivery.repository.ResultReviewRoundRepository reviewRounds;
    private final com.ondemandmonitoring.delivery.repository.ResultReviewRoundEvidenceRepository roundEvidence;
    private final ResultReviewRoundItemRepository reviewRoundItems;
    private final MissionChecklistEvidenceRepository missionEvidence;
    private final OrderChecklistItemRepository checklistItems;
    private final ChecklistEvidencePolicy evidencePolicy;
    private final ResultRevisionCaseRepository revisionCases;
    private final ResultRevisionCaseItemRepository revisionCaseItems;
    @Autowired @NonFinal
    private com.ondemandmonitoring.support.service.impl.SupportTicketService supportTickets;

    @Override
    @Transactional
    public void markProcessing(String orderId) {
        Order order = orders.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        deliveries.findByOrderIdForUpdate(orderId).orElseGet(() -> create(order));
        keepOrderInProgress(order);
    }

    @Override
    @Transactional
    public void markReadyForManagerReview(String orderId) {
        Order order = orders.findByIdForUpdate(orderId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        OrderDelivery delivery = deliveries.findByOrderIdForUpdate(orderId).orElseGet(() -> create(order));
        if (delivery.getStatus() == DeliveryStatus.PROCESSING && isResultApproved(orderId)) {
            delivery.setStatus(DeliveryStatus.READY_FOR_MANAGER_REVIEW);
        }
        keepOrderInProgress(order);
    }

    @Override
    @Transactional
    public boolean publishInspectedResults(String orderId) {
        // Order -> all Missions serializes the last two confirmations and publication retries.
        var aggregate = completionPolicy.lockAggregate(orderId);
        Order order = aggregate.order();
        var effective = aggregate.missions().stream()
                .filter(com.ondemandmonitoring.mission.service.impl.ExecutionPlanCoverageService::effective).toList();
        var originals = effective.stream().filter(m -> m.getPurpose() != com.ondemandmonitoring.mission.enums.MissionPurpose.REWORK).toList();
        if (originals.isEmpty()) return false;
        var inspectedPackages = missionResults.findByMissionIds(effective.stream().map(Mission::getId).toList());
        var approved = inspectedPackages.stream()
                .filter(r -> r.getApprovalStatus() == MissionResultApprovalStatus.APPROVED
                        && r.getStatus() != com.ondemandmonitoring.mission.enums.MissionResultStatus.FAILED)
                .map(r -> r.getMission().getId()).collect(Collectors.toSet());
        if (originals.stream().anyMatch(m -> m.getStatus() != MissionStatus.COMPLETED || !approved.contains(m.getId())))
            return false;
        OrderDelivery delivery = deliveries.findByOrderIdForUpdate(orderId).orElse(null);
        var revisionCase = revisionCases.findByOrderIdAndActiveTrueForUpdate(orderId).orElse(null);
        var inspected = inspectedPackages.stream()
                .filter(com.ondemandmonitoring.mission.domain.MissionResult::isInspectorConfirmed)
                .map(r -> r.getMission().getId()).collect(Collectors.toSet());
        if (revisionCase == null && originals.stream().anyMatch(m -> !inspected.contains(m.getId()))) return false;

        if (delivery != null && (delivery.getStatus() == DeliveryStatus.CUSTOMER_REVIEW
                || delivery.getStatus() == DeliveryStatus.READY_FOR_DELIVERY
                || delivery.getStatus() == DeliveryStatus.DELIVERED
                || delivery.getStatus() == DeliveryStatus.FINAL_PAYMENT_PENDING)) return false;
        if (revisionCase == null && delivery != null && delivery.getCurrentReviewRoundId() != null) return false;
        var items = checklistItems.findAllByOrderIdOrderByDisplayOrderAscIdAsc(orderId);
        if (items.isEmpty()) return false;
        Map<String, com.ondemandmonitoring.delivery.domain.ResultRevisionCaseItem> reworkByItem = new LinkedHashMap<>();
        if (revisionCase != null) {
            for (var item : revisionCaseItems.findByResultRevisionCaseIdOrderByCreatedAtAsc(revisionCase.getId())) {
                if (item.getStatus() == com.ondemandmonitoring.delivery.enums.ResultRevisionCaseItemStatus.RESOLVED
                        || item.getStatus() == com.ondemandmonitoring.delivery.enums.ResultRevisionCaseItemStatus.OUT_OF_SCOPE_ACKNOWLEDGED)
                    continue;
                // Reserve-media decisions remain the Manager's work; never publish them implicitly.
                if (item.getScopeDecision() != com.ondemandmonitoring.delivery.enums.ResultRevisionScope.IN_SCOPE
                        || item.getStrategy() != com.ondemandmonitoring.delivery.enums.ResultRevisionStrategy.REWORK
                        || item.getStatus() != com.ondemandmonitoring.delivery.enums.ResultRevisionCaseItemStatus.IN_PROGRESS
                        || item.getAssignedReworkMissionId() == null) return false;
                var mission = effective.stream().filter(m -> m.getId().equals(item.getAssignedReworkMissionId())).findFirst().orElse(null);
                if (mission == null || mission.getPurpose() != com.ondemandmonitoring.mission.enums.MissionPurpose.REWORK
                        || !revisionCase.getId().equals(mission.getResultRevisionCaseId())
                        || mission.getStatus() != MissionStatus.COMPLETED || !approved.contains(mission.getId())
                        || !inspected.contains(mission.getId())) return false;
                reworkByItem.put(item.getOrderChecklistItem().getId(), item);
            }
            if (reworkByItem.isEmpty()) return false;
        } else if (order.getOrderStatus() != OrderStatus.COMPLETED && !completionPolicy.completeIfReady(aggregate)) {
            return false;
        }
        Map<String, List<MissionChecklistEvidence>> selected = new LinkedHashMap<>();
        for (var mission : effective) {
            if (mission.getStatus() != MissionStatus.COMPLETED || !approved.contains(mission.getId())) continue;
            if (revisionCase == null && mission.getPurpose() == com.ondemandmonitoring.mission.enums.MissionPurpose.REWORK) continue;
            for (var link : missionEvidence.findActiveByMissionId(mission.getId())) {
                String itemId = link.getMissionChecklistExecution().getOrderChecklistItemId();
                if (!link.isSelectedForCustomer() || link.getDetachedAt() != null) continue;
                if (revisionCase != null && (!reworkByItem.containsKey(itemId)
                        || !ReworkDeliveryEvidencePolicy.allows(reworkByItem.get(itemId), mission.getId()))) continue;
                selected.computeIfAbsent(itemId, ignored -> new ArrayList<>()).add(link);
            }
        }
        String baseRoundId = delivery == null ? null : delivery.getCurrentReviewRoundId();
        var baseRound = baseRoundId == null ? null : reviewRounds.findByIdForUpdate(baseRoundId).orElseThrow();
        Map<String, List<com.ondemandmonitoring.delivery.domain.ResultReviewRoundEvidence>> previous = new HashMap<>();
        if (baseRound != null) {
            var rowIds = reviewRoundItems.findByRoundIdOrderByDisplayOrderAsc(baseRoundId).stream().map(com.ondemandmonitoring.delivery.domain.ResultReviewRoundItem::getId).toList();
            if (!rowIds.isEmpty()) roundEvidence.findByRoundItemIdInOrderByRoundItemIdAscCreatedAtAsc(rowIds).forEach(row ->
                    previous.computeIfAbsent(row.getRoundItem().getOrderChecklistItem().getId(), ignored -> new ArrayList<>()).add(row));
        }
        List<ChecklistDeliveryPublishRequest.ChecklistSelection> operations = new ArrayList<>();
        for (var item : items) {
            if (revisionCase != null && !reworkByItem.containsKey(item.getId())) continue;
            var links = selected.getOrDefault(item.getId(), List.of());
            var assets = links.stream().map(MissionChecklistEvidence::getMediaAsset)
                    .collect(Collectors.toMap(MediaAsset::getId, asset -> asset, (left, right) -> left, TreeMap::new));
            for (var asset : assets.values()) {
                if (evidencePolicy.ineligibilityReason(asset, true) != null)
                    throw new ApiException(ErrorCode.EVIDENCE_NOT_ELIGIBLE);
                retentionAccess.requireAccessible(asset);
            }
            validateChecklistMinimum(item, List.copyOf(assets.values()));
            var newIds = new ArrayList<>(assets.keySet());
            var old = previous.getOrDefault(item.getId(), List.of());
            List<ChecklistDeliveryPublishRequest.Replacement> replacements = new ArrayList<>();
            int paired = Math.min(old.size(), newIds.size());
            for (int i = 0; i < paired; i++) replacements.add(new ChecklistDeliveryPublishRequest.Replacement(old.get(i).getId(), newIds.get(i)));
            operations.add(new ChecklistDeliveryPublishRequest.ChecklistSelection(item.getId(),
                    newIds.subList(paired, newIds.size()), old.subList(paired, old.size()).stream().map(com.ondemandmonitoring.delivery.domain.ResultReviewRoundEvidence::getId).toList(), replacements));
        }
        if (operations.isEmpty()) return false;
        if (revisionCase == null) {
            validateSelectedOrderQuantity(order, selected.values().stream().flatMap(List::stream)
                    .map(MissionChecklistEvidence::getMediaAsset).toList());
        }
        if (delivery != null && delivery.getStatus() == DeliveryStatus.PROCESSING) {
            delivery.setStatus(DeliveryStatus.READY_FOR_MANAGER_REVIEW);
            // Capture the command version after Hibernate persists this transition. Otherwise
            // the first query in releaseChecklistPreview auto-flushes and makes our own version stale.
            deliveries.flush();
        }
        // Surplus has already been explicitly confirmed by the Inspector on each selection command.
        var draft = new ChecklistDeliveryPublishRequest(UUID.randomUUID(), delivery == null ? null : delivery.getVersion(),
                baseRoundId, baseRound == null ? null : baseRound.getVersion(), null, true, null, operations);
        String fingerprint = checklistPublishFingerprint(orderId, draft);
        var command = new ChecklistDeliveryPublishRequest(draft.clientRequestId(), draft.expectedDeliveryVersion(),
                draft.baseReviewRoundId(), draft.expectedReviewRoundVersion(), null, true, fingerprint, operations);
        releaseChecklistPreview(orderId, command);
        return true;
    }

    @Override
    @Transactional
    public DeliveryResponse releasePreview(String orderId, ReleasePreviewRequest request) {
        List<Mission> effective = completedMissions(orderId);
        if (!isResultApproved(orderId) || !completionPolicy.completeIfReady(completionPolicy.lockAggregate(orderId))) {
            throw new ApiException(ErrorCode.DELIVERY_NOT_READY,
                    "Approve the mission result before releasing a customer preview");
        }
        // Legacy completed Orders may have no delivery row. Initialize it only
        // inside this validated write command, never from the read endpoint.
        OrderDelivery delivery = deliveries.findByOrderIdForUpdate(orderId).orElseGet(() -> {
            OrderDelivery initialized = create(orders.findByIdForUpdate(orderId).orElseThrow());
            initialized.setStatus(DeliveryStatus.READY_FOR_MANAGER_REVIEW);
            return initialized;
        });
        if (delivery.getStatus() != DeliveryStatus.READY_FOR_MANAGER_REVIEW
                && delivery.getStatus() != DeliveryStatus.REVISION_REQUESTED
                && !(delivery.getStatus() == DeliveryStatus.CUSTOMER_REVIEW && delivery.getCurrentReviewRoundId() == null)) {
            throw new ApiException(ErrorCode.INVALID_DELIVERY_STATE);
        }
        List<MediaAsset> assets = assets(effective);
        Set<String> selected = new HashSet<>(request.mediaAssetIds());
        if (selected.isEmpty() || assets.stream().filter(a -> selected.contains(a.getId()))
                .anyMatch(a -> a.getMediaStatus() != MediaStatus.AVAILABLE)
                || assets.stream().noneMatch(a -> selected.contains(a.getId()))) {
            throw new ApiException(ErrorCode.DELIVERY_NOT_READY, "Select at least one approved mission asset");
        }
        if (selected.stream().anyMatch(id -> assets.stream().noneMatch(a -> a.getId().equals(id)))) {
            throw new ApiException(ErrorCode.MEDIA_ACCESS_DENIED);
        }
        assets.forEach(asset -> asset.setPreviewSelected(selected.contains(asset.getId())));
        media.saveAll(assets);
        Instant now = Instant.now();
        var order = delivery.getOrder();
        var round = reviewRoundPublisher.publish(order, delivery, effective, selected, now, currentUser.getCurrentUser());
        delivery.setCurrentReviewRoundId(round.getId());
        delivery.setStatus(DeliveryStatus.CUSTOMER_REVIEW);
        delivery.setDeliveryNotes(trim(request.notes()));
        delivery.setPreviewReleasedAt(now);
        delivery.setPreviewReleasedBy(currentUser.getCurrentUser());
        delivery.setRevisionReason(null);
        delivery.setRevisionRequestedAt(null);
        return response(delivery, effective, assets.stream().filter(MediaAsset::isPreviewSelected).toList(), false);
    }

    @Override
    @Transactional
    public DeliveryResponse releaseChecklistPreview(String orderId, ChecklistDeliveryPublishRequest request) {
        if (request == null || request.clientRequestId() == null || request.checklists() == null)
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        Order order = orders.findByIdForUpdate(orderId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        List<Mission> allEffectiveMissions = effectiveMissions(orderId);
        List<Mission> publishableMissions = checklistPublicationMissions(orderId);
        if (order.getOrderStatus() != OrderStatus.COMPLETED
                && !completionPolicy.completeIfReady(completionPolicy.lockAggregate(orderId)))
            throw new ApiException(ErrorCode.DELIVERY_NOT_READY, "Approve every original Mission result before delivery");
        OrderDelivery delivery = deliveries.findByOrderIdForUpdate(orderId).orElse(null);
        var activeRevisionCase = revisionCases.findByOrderIdAndActiveTrueForUpdate(orderId).orElse(null);
        String fingerprint = checklistPublishFingerprint(orderId, request);
        if (delivery != null && Objects.equals(delivery.getLastChecklistPublishRequestId(), request.clientRequestId())) {
            if (!Objects.equals(delivery.getLastChecklistPublishFingerprint(), fingerprint))
                throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            return response(delivery, allEffectiveMissions, releasableAssets(allEffectiveMissions), false);
        }
        if (delivery != null && !Objects.equals(delivery.getVersion(), request.expectedDeliveryVersion()))
            throw new ApiException(ErrorCode.RESULT_REVIEW_REFRESH_REQUIRED);
        if (delivery == null && request.expectedDeliveryVersion() != null)
            throw new ApiException(ErrorCode.RESULT_REVIEW_REFRESH_REQUIRED);

        String currentRoundId = delivery == null ? null : delivery.getCurrentReviewRoundId();
        if (!Objects.equals(currentRoundId, request.baseReviewRoundId()))
            throw new ApiException(ErrorCode.RESULT_REVIEW_REFRESH_REQUIRED);
        var previousRound = currentRoundId == null ? null : reviewRounds.findByIdForUpdate(currentRoundId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESULT_REVIEW_REFRESH_REQUIRED));
        if (previousRound == null && request.expectedReviewRoundVersion() != null
                || previousRound != null && !Objects.equals(previousRound.getVersion(), request.expectedReviewRoundVersion()))
            throw new ApiException(ErrorCode.RESULT_REVIEW_REFRESH_REQUIRED);

        if (delivery == null) {
            delivery = create(order);
            delivery.setStatus(DeliveryStatus.READY_FOR_MANAGER_REVIEW);
        }
        if (delivery.getStatus() != DeliveryStatus.READY_FOR_MANAGER_REVIEW
                && delivery.getStatus() != DeliveryStatus.REVISION_REQUESTED
                && !(delivery.getStatus() == DeliveryStatus.CUSTOMER_REVIEW && currentRoundId == null))
            throw new ApiException(ErrorCode.INVALID_DELIVERY_STATE);

        List<OrderChecklistItem> orderItems = checklistItems.findAllByOrderIdOrderByDisplayOrderAscIdAsc(orderId);
        Map<String, OrderChecklistItem> itemById = orderItems.stream()
                .collect(Collectors.toMap(OrderChecklistItem::getId, item -> item));
        Map<String, Map<String, MissionChecklistEvidence>> eligibleByChecklist = eligibleEvidenceByChecklist(orderItems, publishableMissions);
        Map<String, List<com.ondemandmonitoring.delivery.domain.ResultReviewRoundEvidence>> previousByChecklist = new HashMap<>();
        if (previousRound != null) {
            List<String> previousRoundItemIds = reviewRoundItems.findByRoundIdOrderByDisplayOrderAsc(previousRound.getId())
                    .stream().map(com.ondemandmonitoring.delivery.domain.ResultReviewRoundItem::getId).toList();
            if (!previousRoundItemIds.isEmpty()) {
                roundEvidence.findByRoundItemIdInOrderByRoundItemIdAscCreatedAtAsc(previousRoundItemIds)
                        .forEach(evidence -> previousByChecklist.computeIfAbsent(
                                evidence.getRoundItem().getOrderChecklistItem().getId(), ignored -> new ArrayList<>()).add(evidence));
            }
        }

        Map<String, ChecklistDeliveryPublishRequest.ChecklistSelection> operations = new LinkedHashMap<>();
        for (var selection : request.checklists()) {
            if (selection == null || !itemById.containsKey(selection.orderChecklistItemId())
                    || operations.putIfAbsent(selection.orderChecklistItemId(), selection) != null)
                throw new ApiException(ErrorCode.RESULT_REVIEW_ITEM_INVALID);
        }
        if (previousRound == null && !operations.keySet().equals(itemById.keySet()))
            throw new ApiException(ErrorCode.RESULT_REVIEW_ITEM_INVALID, "Select a media bundle for every checklist");

        Map<String, Set<String>> candidateByChecklist = new LinkedHashMap<>();
        Map<String, String> sourceEvidenceByMedia = new HashMap<>();
        Map<String, String> supersededEvidenceByMedia = new HashMap<>();
        Set<String> changedChecklistIds = new HashSet<>();
        Map<String, MediaAsset> selectedAssetsById = new HashMap<>();
        for (OrderChecklistItem item : orderItems) {
            String itemId = item.getId();
            List<com.ondemandmonitoring.delivery.domain.ResultReviewRoundEvidence> priorEvidence = previousByChecklist
                    .getOrDefault(itemId, List.of());
            Set<String> candidate = new LinkedHashSet<>();
            for (var prior : priorEvidence) {
                String mediaId = prior.getMediaAsset().getId();
                if (!eligibleByChecklist.getOrDefault(itemId, Map.of()).containsKey(mediaId))
                    throw new ApiException(ErrorCode.RESULT_REVIEW_REFRESH_REQUIRED,
                            "A previously delivered file is no longer eligible; replace it or refresh the delivery");
                candidate.add(mediaId);
                    sourceEvidenceByMedia.put(provenanceKey(itemId, mediaId), prior.getId());
                selectedAssetsById.put(mediaId, prior.getMediaAsset());
            }
            var operation = operations.get(itemId);
            if (operation != null) {
                Set<String> replacedEvidenceIds = new HashSet<>();
                Set<String> removedEvidenceIds = new HashSet<>();
                for (String evidenceId : operation.removeRoundEvidenceIds() == null
                        ? List.<String>of() : operation.removeRoundEvidenceIds()) {
                    if (!removedEvidenceIds.add(evidenceId))
                        throw new ApiException(ErrorCode.RESULT_REVIEW_ITEM_INVALID);
                    var old = priorEvidence.stream().filter(row -> row.getId().equals(evidenceId)).findFirst()
                            .orElseThrow(() -> new ApiException(ErrorCode.RESULT_REVIEW_ITEM_INVALID));
                    candidate.remove(old.getMediaAsset().getId());
                    sourceEvidenceByMedia.remove(provenanceKey(itemId, old.getMediaAsset().getId()));
                    changedChecklistIds.add(itemId);
                }
                for (var replacement : Objects.requireNonNullElse(operation.replacements(), List.<ChecklistDeliveryPublishRequest.Replacement>of())) {
                    if (!replacedEvidenceIds.add(replacement.sourceRoundEvidenceId())
                            || removedEvidenceIds.contains(replacement.sourceRoundEvidenceId()))
                        throw new ApiException(ErrorCode.RESULT_REVIEW_ITEM_INVALID);
                    var old = priorEvidence.stream().filter(row -> row.getId().equals(replacement.sourceRoundEvidenceId())).findFirst()
                            .orElseThrow(() -> new ApiException(ErrorCode.RESULT_REVIEW_ITEM_INVALID));
                    Map<String, MissionChecklistEvidence> eligible = eligibleByChecklist.getOrDefault(itemId, Map.of());
                    MissionChecklistEvidence newLink = eligible.get(replacement.newMediaAssetId());
                    if (newLink == null || candidate.contains(replacement.newMediaAssetId()))
                        throw new ApiException(ErrorCode.MEDIA_ACCESS_DENIED);
                    candidate.remove(old.getMediaAsset().getId());
                    candidate.add(replacement.newMediaAssetId());
                    sourceEvidenceByMedia.remove(provenanceKey(itemId, old.getMediaAsset().getId()));
                    sourceEvidenceByMedia.put(provenanceKey(itemId, replacement.newMediaAssetId()), old.getId());
                    supersededEvidenceByMedia.put(provenanceKey(itemId, replacement.newMediaAssetId()), old.getId());
                    selectedAssetsById.put(replacement.newMediaAssetId(), newLink.getMediaAsset());
                    changedChecklistIds.add(itemId);
                }
                Set<String> addedIds = new HashSet<>();
                List<String> additions = operation.addMediaAssetIds() == null
                        ? List.of() : operation.addMediaAssetIds();
                for (String mediaId : additions) {
                    if (!addedIds.add(mediaId) || candidate.contains(mediaId))
                        throw new ApiException(ErrorCode.RESULT_REVIEW_ITEM_INVALID);
                    MissionChecklistEvidence link = eligibleByChecklist.getOrDefault(itemId, Map.of()).get(mediaId);
                    if (link == null) throw new ApiException(ErrorCode.MEDIA_ACCESS_DENIED);
                    candidate.add(mediaId);
                    selectedAssetsById.put(mediaId, link.getMediaAsset());
                    changedChecklistIds.add(itemId);
                }
            }
            candidateByChecklist.put(itemId, candidate);
        }

        if (previousRound != null && changedChecklistIds.isEmpty())
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Select at least one checklist change before publishing another round");
        if (activeRevisionCase != null && !changedChecklistIds.isEmpty()) {
            Map<String, com.ondemandmonitoring.delivery.domain.ResultRevisionCaseItem> caseItemsByChecklist =
                    revisionCaseItems.findByResultRevisionCaseIdOrderByCreatedAtAsc(activeRevisionCase.getId()).stream()
                            .collect(Collectors.toMap(item -> item.getOrderChecklistItem().getId(), item -> item, (first, ignored) -> first));
            for (String changedChecklistId : changedChecklistIds) {
                var caseItem = caseItemsByChecklist.get(changedChecklistId);
                if (caseItem == null
                        || caseItem.getScopeDecision() != com.ondemandmonitoring.delivery.enums.ResultRevisionScope.IN_SCOPE
                        || caseItem.getStrategy() == com.ondemandmonitoring.delivery.enums.ResultRevisionStrategy.UNDECIDED
                        || caseItem.getStatus() != com.ondemandmonitoring.delivery.enums.ResultRevisionCaseItemStatus.IN_PROGRESS)
                    throw new ApiException(ErrorCode.INVALID_DELIVERY_STATE);
                if (caseItem.getStrategy() == com.ondemandmonitoring.delivery.enums.ResultRevisionStrategy.REWORK) {
                    Mission rework = caseItem.getAssignedReworkMissionId() == null ? null
                            : missions.findById(caseItem.getAssignedReworkMissionId()).orElse(null);
                    if (rework == null || rework.getPurpose() != com.ondemandmonitoring.mission.enums.MissionPurpose.REWORK
                            || !activeRevisionCase.getId().equals(rework.getResultRevisionCaseId())
                            || rework.getStatus() != MissionStatus.COMPLETED || !isMissionResultApproved(rework))
                        throw new ApiException(ErrorCode.INVALID_DELIVERY_STATE);
                    for (String mediaId : candidateByChecklist.getOrDefault(changedChecklistId, Set.of())) {
                        MediaAsset asset = selectedAssetsById.get(mediaId);
                        if (asset == null || !ReworkDeliveryEvidencePolicy.allows(caseItem, asset.getMissionId()))
                            throw new ApiException(ErrorCode.MEDIA_ACCESS_DENIED,
                                    "Rework delivery requires newly accepted media from its assigned rework Mission");
                    }
                } else if (caseItem.getAssignedReworkMissionId() != null) {
                    Mission assignedRework = missions.findById(caseItem.getAssignedReworkMissionId()).orElse(null);
                    if (assignedRework != null && !Set.of(MissionStatus.FAILED, MissionStatus.CANCELLED)
                            .contains(assignedRework.getStatus())
                            && (assignedRework.getStatus() != MissionStatus.COMPLETED || !isMissionResultApproved(assignedRework)))
                        throw new ApiException(ErrorCode.INVALID_DELIVERY_STATE);
                }
            }
        }

        boolean surplus = false;
        for (OrderChecklistItem item : orderItems) {
            List<MediaAsset> selectedForItem = candidateByChecklist.getOrDefault(item.getId(), Set.of()).stream()
                    .map(selectedAssetsById::get).filter(Objects::nonNull).toList();
            validateChecklistMinimum(item, selectedForItem);
            surplus |= exceedsChecklistMinimum(item, selectedForItem);
        }
        if (surplus && (!request.confirmSurplus()
                || !Objects.equals(request.surplusConfirmationFingerprint(), fingerprint)))
            throw new ApiException(ErrorCode.DELIVERY_SURPLUS_CONFIRMATION_REQUIRED);

        Instant now = Instant.now();
        Set<String> allSelectedIds = candidateByChecklist.values().stream().flatMap(Set::stream).collect(Collectors.toSet());
        List<MediaAsset> allAssets = assets(allEffectiveMissions);
        allAssets.forEach(asset -> asset.setPreviewSelected(allSelectedIds.contains(asset.getId())));
        media.saveAll(allAssets);
        var reviewRound = reviewRoundPublisher.publishByChecklist(order, delivery, publishableMissions, candidateByChecklist,
                now, currentUser.getCurrentUser(), sourceEvidenceByMedia, supersededEvidenceByMedia,
                previousRound == null ? itemById.keySet() : changedChecklistIds, previousRound == null);
        delivery.setCurrentReviewRoundId(reviewRound.getId());
        delivery.setStatus(DeliveryStatus.CUSTOMER_REVIEW);
        delivery.setDeliveryNotes(trim(request.notes()));
        delivery.setPreviewReleasedAt(now);
        delivery.setPreviewReleasedBy(currentUser.getCurrentUser());
        delivery.setRevisionReason(null);
        delivery.setRevisionRequestedAt(null);
        delivery.setLastChecklistPublishRequestId(request.clientRequestId());
        delivery.setLastChecklistPublishFingerprint(fingerprint);

        if (activeRevisionCase != null) {
            revisionCaseItems.findByResultRevisionCaseIdAndOrderChecklistItemIdInOrderByCreatedAtAsc(
                                activeRevisionCase.getId(), changedChecklistIds).forEach(item -> {
                            if (item.getScopeDecision() == com.ondemandmonitoring.delivery.enums.ResultRevisionScope.IN_SCOPE)
                                item.setStatus(com.ondemandmonitoring.delivery.enums.ResultRevisionCaseItemStatus.WAITING_CUSTOMER);
                        });
        }
        List<MediaAsset> selectedAssets = allSelectedIds.stream().map(selectedAssetsById::get)
                .filter(Objects::nonNull).toList();
        return response(delivery, allEffectiveMissions, selectedAssets, false);
    }

    @Override
    @Transactional
    public DeliveryResponse acceptResult(String orderId, String expectedReviewRoundId, Long expectedRoundVersion) {
        assertOwner(orderId);
        orders.findByIdForUpdate(orderId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        var activeRevisionCase = revisionCases.findByOrderIdAndActiveTrueForUpdate(orderId).orElse(null);
        List<Mission> effective = activeRevisionCase == null ? completedMissions(orderId) : effectiveMissions(orderId);
        OrderDelivery delivery = locked(orderId);
        var round = delivery.getCurrentReviewRoundId() == null ? null : reviewRounds.findByIdForUpdate(delivery.getCurrentReviewRoundId()).orElse(null);
        if (round == null || expectedReviewRoundId == null || expectedRoundVersion == null
                || !round.getId().equals(expectedReviewRoundId))
            throw new ApiException(ErrorCode.RESULT_REVIEW_REFRESH_REQUIRED);
        Invoice invoice = invoice(orderId);
        if (delivery.getCustomerAcceptedAt() != null
                && round.getStatus() == com.ondemandmonitoring.delivery.enums.ResultReviewRoundStatus.ACCEPTED
                && (delivery.getStatus() == DeliveryStatus.FINAL_PAYMENT_PENDING
                    || delivery.getStatus() == DeliveryStatus.READY_FOR_DELIVERY
                    || delivery.getStatus() == DeliveryStatus.READY_FOR_MANAGER_REVIEW
                    || delivery.getStatus() == DeliveryStatus.DELIVERED)) {
            if (delivery.getStatus() == DeliveryStatus.FINAL_PAYMENT_PENDING && isInvoiceFullyPaid(invoice))
                delivery.setStatus(DeliveryStatus.READY_FOR_DELIVERY);
            if (delivery.getStatus() == DeliveryStatus.READY_FOR_DELIVERY && isInvoiceFullyPaid(invoice))
                return releaseOriginals(orderId);
            return response(delivery, effective, List.of(), false);
        }
        if (!Objects.equals(round.getVersion(), expectedRoundVersion))
            throw new ApiException(ErrorCode.RESULT_REVIEW_REFRESH_REQUIRED);
        if (delivery.getStatus() != DeliveryStatus.CUSTOMER_REVIEW) {
            throw new ApiException(delivery.getCustomerAcceptedAt() != null
                    ? ErrorCode.RESULT_ALREADY_ACCEPTED : ErrorCode.RESULT_NOT_READY_FOR_REVIEW);
        }
        delivery.setCustomerAcceptedAt(Instant.now());
        round.setStatus(com.ondemandmonitoring.delivery.enums.ResultReviewRoundStatus.ACCEPTED);
        boolean caseResolved = resolveAcceptedRevisionItems(activeRevisionCase, round.getId());
        // A customer can accept the checklist changes currently published while a
        // different checklist is still being reworked. Keep the case open and return
        // delivery to Manager until every case item has reached a terminal decision.
        delivery.setStatus(activeRevisionCase != null && !caseResolved
                ? DeliveryStatus.READY_FOR_MANAGER_REVIEW
                : isInvoiceFullyPaid(invoice) ? DeliveryStatus.READY_FOR_DELIVERY : DeliveryStatus.FINAL_PAYMENT_PENDING);
        if (delivery.getStatus() == DeliveryStatus.READY_FOR_DELIVERY) return releaseOriginals(orderId);
        return response(delivery, effective, List.of(), false);
    }

    @Override
    @Transactional
    public void completeAcceptedRevisionCase(String orderId) {
        orders.findByIdForUpdate(orderId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if (revisionCases.findByOrderIdAndActiveTrue(orderId).isPresent()) return;

        OrderDelivery delivery = deliveries.findByOrderIdForUpdate(orderId).orElse(null);
        if (delivery == null || delivery.getStatus() != DeliveryStatus.READY_FOR_MANAGER_REVIEW
                || delivery.getCustomerAcceptedAt() == null || delivery.getCurrentReviewRoundId() == null)
            return;

        var acceptedRound = reviewRounds.findByIdForUpdate(delivery.getCurrentReviewRoundId()).orElse(null);
        if (acceptedRound == null
                || acceptedRound.getStatus() != com.ondemandmonitoring.delivery.enums.ResultReviewRoundStatus.ACCEPTED)
            return;

        Invoice invoice = invoice(orderId);
        delivery.setStatus(isInvoiceFullyPaid(invoice)
                ? DeliveryStatus.READY_FOR_DELIVERY
                : DeliveryStatus.FINAL_PAYMENT_PENDING);
        if (delivery.getStatus() == DeliveryStatus.READY_FOR_DELIVERY) releaseOriginals(orderId);
    }

    private boolean resolveAcceptedRevisionItems(
            com.ondemandmonitoring.delivery.domain.ResultRevisionCase revisionCase, String roundId) {
        if (revisionCase == null) return true;
        Set<String> changedChecklistIds = reviewRoundItems.findByRoundIdOrderByDisplayOrderAsc(roundId).stream()
                .filter(com.ondemandmonitoring.delivery.domain.ResultReviewRoundItem::isChangedInRound)
                .map(item -> item.getOrderChecklistItem().getId()).collect(Collectors.toSet());
        if (changedChecklistIds.isEmpty()) return false;
        var items = revisionCaseItems.findByResultRevisionCaseIdAndOrderChecklistItemIdInOrderByCreatedAtAsc(
                revisionCase.getId(), changedChecklistIds);
        items.stream()
                .filter(item -> item.getStatus() == com.ondemandmonitoring.delivery.enums.ResultRevisionCaseItemStatus.WAITING_CUSTOMER)
                .forEach(item -> item.setStatus(com.ondemandmonitoring.delivery.enums.ResultRevisionCaseItemStatus.RESOLVED));
        revisionCaseItems.saveAll(items);
        var all = revisionCaseItems.findByResultRevisionCaseIdOrderByCreatedAtAsc(revisionCase.getId());
        boolean finished = !all.isEmpty() && all.stream().allMatch(item ->
                item.getStatus() == com.ondemandmonitoring.delivery.enums.ResultRevisionCaseItemStatus.RESOLVED
                        || item.getStatus() == com.ondemandmonitoring.delivery.enums.ResultRevisionCaseItemStatus.OUT_OF_SCOPE_ACKNOWLEDGED);
        if (finished) {
            revisionCase.close(Instant.now());
            supportTickets.resolveResultRevisionTicket(revisionCase.getTicketId(), null);
        }
        return finished;
    }

    @Override
    @Transactional
    public DeliveryResponse requestRevision(String orderId, RevisionRequest request) {
        throw new ApiException(ErrorCode.RESULT_REVIEW_REFRESH_REQUIRED);
    }

    @Override
    @Transactional
    public DeliveryResponse releaseOriginals(String orderId) {
        List<Mission> effective = completedMissions(orderId);
        Invoice invoice = invoice(orderId);
        if (invoice.getStatus() != InvoiceStatus.PAID) throw new ApiException(ErrorCode.INVOICE_NOT_PAID);
        OrderDelivery delivery = locked(orderId);
        if (delivery.getStatus() == DeliveryStatus.DELIVERED) {
            return response(delivery, effective, releasableAssets(effective), true);
        }
        // Legacy deliveries accepted before the assignment-first payment gate
        // can proceed after an external reconciliation updates the invoice.
        if (delivery.getStatus() == DeliveryStatus.FINAL_PAYMENT_PENDING
                && delivery.getCustomerAcceptedAt() != null) {
            delivery.setStatus(DeliveryStatus.READY_FOR_DELIVERY);
        }
        if (delivery.getStatus() != DeliveryStatus.READY_FOR_DELIVERY) throw new ApiException(ErrorCode.DELIVERY_NOT_READY);
        List<MediaAsset> assets = releasableAssets(effective);
        if (assets.isEmpty()) throw new ApiException(ErrorCode.DELIVERY_NOT_READY, "No approved originals exist");
        Instant handedOverAt = Instant.now();
        delivery.setStatus(DeliveryStatus.DELIVERED);
        delivery.setOriginalsReleasedAt(handedOverAt);
        delivery.setOriginalsReleasedBy(currentUser.getCurrentUser());
        Order order = delivery.getOrder();
        if (order.getResultExpiresAt() == null) {
            order.setResultExpiresAt(handedOverAt.plus(order.getDataRetentionDays(), java.time.temporal.ChronoUnit.DAYS));
        }
        if (!completionPolicy.completeIfReady(completionPolicy.lockAggregate(orderId)))
            throw new ApiException(ErrorCode.DELIVERY_NOT_READY);
        return response(delivery, effective, assets, true);
    }

    @Override @Transactional(readOnly = true)
    public DeliveryResponse get(String orderId) {
        assertOwnerOrManager(orderId);
        var effective = effectiveMissions(orderId);
        OrderDelivery delivery = deliveries.findByOrderId(orderId).orElseGet(() -> {
            OrderDelivery view = new OrderDelivery();
            view.setOrder(orders.findById(orderId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND)));
            view.setStatus(isResultApproved(orderId) ? DeliveryStatus.READY_FOR_MANAGER_REVIEW : DeliveryStatus.PROCESSING);
            return view; // Unsaved read model: GET must never create/reconcile business state.
        });
        List<MediaAsset> visible = isManager() ? assets(effective).stream()
                .filter(a -> a.getMediaStatus() == MediaStatus.AVAILABLE).toList() : List.of();
        return response(delivery, effective, visible, false);
    }

    @Override @Transactional(readOnly = true)
    public DeliveryResponse previews(String orderId) {
        assertOwnerOrManager(orderId);
        OrderDelivery delivery = deliveries.findByOrderId(orderId).orElseThrow(() -> new ApiException(ErrorCode.RESULT_NOT_READY_FOR_REVIEW));
        if (delivery.getPreviewReleasedAt() == null || delivery.getStatus().ordinal() < DeliveryStatus.CUSTOMER_REVIEW.ordinal()) {
            throw new ApiException(ErrorCode.RESULT_NOT_READY_FOR_REVIEW);
        }
        List<Mission> effective = effectiveMissions(orderId);
        Set<String> visibleIds = publishedMediaAssetIds(effective.stream().map(Mission::getId).toList());
        List<MediaAsset> assets = assets(effective).stream()
                .filter(asset -> visibleIds.contains(asset.getId()))
                .filter(a -> a.getMediaStatus() == MediaStatus.AVAILABLE).toList();
        return response(delivery, effective, assets, false);
    }

    @Override @Transactional(readOnly = true)
    public DeliveryResponse originals(String orderId) {
        assertOwnerOrManager(orderId);
        requireOriginalAccess(orderId);
        OrderDelivery delivery = deliveries.findByOrderId(orderId).orElseThrow();
        List<Mission> effective = effectiveMissions(orderId);
        List<MediaAsset> assets = releasableAssets(effective);
        return response(delivery, effective, assets, true);
    }

    @Override @Transactional(readOnly = true)
    public void requireOriginalAccess(String orderId) {
        if (isManager()) return;
        assertOwner(orderId);
        Invoice invoice = invoice(orderId);
        if (invoice.getStatus() != InvoiceStatus.PAID) throw new ApiException(ErrorCode.FINAL_PAYMENT_REQUIRED);
        OrderDelivery delivery = deliveries.findByOrderId(orderId).orElseThrow(() -> new ApiException(ErrorCode.DELIVERABLE_NOT_RELEASED));
        if (delivery.getStatus() != DeliveryStatus.DELIVERED) throw new ApiException(ErrorCode.DELIVERABLE_NOT_RELEASED);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> originalAccessibleMissionIds(List<String> missionIds) {
        if (missionIds == null || missionIds.isEmpty()) return List.of();
        String customerId = currentUser.getCurrentUserId();
        return missionIds.stream().filter(missionId -> missions.findByIdWithOrder(missionId)
                .map(Mission::getOrder)
                .filter(order -> order.getCustomer().getId().equals(customerId))
                .filter(order -> invoices.findByOrderId(order.getId())
                        .filter(invoice -> invoice.getStatus() == InvoiceStatus.PAID).isPresent())
                .filter(order -> deliveries.findByOrderId(order.getId())
                        .filter(delivery -> delivery.getStatus() == DeliveryStatus.DELIVERED).isPresent())
                .isPresent()).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> publishedMediaAssetIds(List<String> missionIds) {
        if (missionIds == null || missionIds.isEmpty()) return Set.of();
        Set<String> orderIds = missions.findAllById(missionIds).stream()
                .map(Mission::getOrder)
                .filter(Objects::nonNull)
                .map(Order::getId)
                .collect(java.util.stream.Collectors.toSet());
        if (orderIds.isEmpty()) return Set.of();
        Set<String> publishedIds = new HashSet<>(roundEvidence.findCurrentPublishedMediaAssetIdsByOrderIds(List.copyOf(orderIds)));

        // Preserve the explicitly selected preview for legacy deliveries that predate review rounds.
        deliveries.findByOrderIdIn(orderIds).stream()
                .filter(delivery -> delivery.getPreviewReleasedAt() != null && delivery.getCurrentReviewRoundId() == null)
                .map(OrderDelivery::getOrder)
                .flatMap(order -> missions.findAllByOrderIdOrderByScheduledStartAtAscIdAsc(order.getId()).stream())
                .filter(com.ondemandmonitoring.mission.service.impl.ExecutionPlanCoverageService::effective)
                .flatMap(mission -> media.findByMissionIdOrderByCapturedAtDesc(mission.getId()).stream())
                .filter(MediaAsset::isPreviewSelected)
                .map(MediaAsset::getId)
                .forEach(publishedIds::add);
        return Set.copyOf(publishedIds);
    }

    private OrderDelivery locked(String orderId) {
        return deliveries.findByOrderIdForUpdate(orderId).orElseThrow(() -> new ApiException(ErrorCode.DELIVERY_NOT_READY));
    }
    private OrderDelivery create(Order order) {
        OrderDelivery d = new OrderDelivery(); d.setOrder(order); d.setStatus(DeliveryStatus.PROCESSING); return deliveries.save(d);
    }
    private boolean isResultApproved(String orderId) {
        var effective = effectiveMissions(orderId);
        if (effective.isEmpty()) return false;
        var results = missionResults.findByMissionIds(effective.stream().map(Mission::getId).toList()).stream()
                .collect(java.util.stream.Collectors.toMap(r -> r.getMission().getId(), r -> r));
        return effective.stream().allMatch(m -> m.getStatus() == MissionStatus.COMPLETED
                && results.containsKey(m.getId())
                && results.get(m.getId()).getApprovalStatus() == MissionResultApprovalStatus.APPROVED
                && results.get(m.getId()).getStatus() != com.ondemandmonitoring.mission.enums.MissionResultStatus.FAILED);
    }
    private void keepOrderInProgress(Order order) {
        if (order.getOrderStatus() == OrderStatus.APPROVED) order.setOrderStatus(OrderStatus.IN_PROGRESS);
    }
    private List<Mission> effectiveMissions(String orderId) {
        return missions.findAllByOrderIdOrderByScheduledStartAtAscIdAsc(orderId).stream()
                .filter(com.ondemandmonitoring.mission.service.impl.ExecutionPlanCoverageService::effective).toList();
    }
    private List<Mission> completedMissions(String orderId) {
        // All write commands acquire Order before Mission and delivery locks.
        var aggregate = completionPolicy.lockAggregate(orderId);
        var effective = aggregate.missions().stream()
                .filter(com.ondemandmonitoring.mission.service.impl.ExecutionPlanCoverageService::effective).toList();
        if (effective.isEmpty() || effective.stream().anyMatch(m -> m.getStatus() != MissionStatus.COMPLETED))
            throw new ApiException(ErrorCode.MISSION_NOT_COMPLETED);
        return effective;
    }
    private List<Mission> checklistPublicationMissions(String orderId) {
        List<Mission> effective = effectiveMissions(orderId);
        List<Mission> original = effective.stream()
                .filter(mission -> mission.getPurpose() != com.ondemandmonitoring.mission.enums.MissionPurpose.REWORK)
                .toList();
        if (original.isEmpty() || original.stream().anyMatch(mission ->
                mission.getStatus() != MissionStatus.COMPLETED || !isMissionResultApproved(mission)))
            throw new ApiException(ErrorCode.DELIVERY_NOT_READY, "Approve every original Mission result before delivery");
        List<Mission> publishable = new ArrayList<>(original);
        effective.stream()
                .filter(mission -> mission.getPurpose() == com.ondemandmonitoring.mission.enums.MissionPurpose.REWORK)
                .filter(mission -> mission.getStatus() == MissionStatus.COMPLETED && isMissionResultApproved(mission))
                .forEach(publishable::add);
        return List.copyOf(publishable);
    }
    private boolean isMissionResultApproved(Mission mission) {
        return missionResults.findByMissionIds(List.of(mission.getId())).stream().anyMatch(result ->
                result.getApprovalStatus() == MissionResultApprovalStatus.APPROVED
                        && result.getStatus() != com.ondemandmonitoring.mission.enums.MissionResultStatus.FAILED);
    }
    private List<MediaAsset> assets(List<Mission> effective) {
        return effective.stream().flatMap(m -> media.findByMissionIdOrderByCapturedAtDesc(m.getId()).stream()).toList();
    }
    private List<MediaAsset> releasableAssets(List<Mission> effective) {
        Set<String> publishedIds = publishedMediaAssetIds(effective.stream().map(Mission::getId).toList());
        return assets(effective).stream()
                .filter(asset -> publishedIds.contains(asset.getId()))
                .filter(asset -> asset.getMediaStatus() == MediaStatus.AVAILABLE)
                .toList();
    }
    private Map<String, Map<String, MissionChecklistEvidence>> eligibleEvidenceByChecklist(
            List<OrderChecklistItem> items, List<Mission> effective) {
        Map<String, Map<String, MissionChecklistEvidence>> byChecklist = new HashMap<>();
        Set<String> currentItemIds = items.stream().map(OrderChecklistItem::getId).collect(Collectors.toSet());
        for (Mission mission : effective) {
            for (MissionChecklistEvidence link : missionEvidence.findActiveByMissionId(mission.getId())) {
                String itemId = link.getMissionChecklistExecution().getOrderChecklistItemId();
                if (!currentItemIds.contains(itemId) || link.getMediaAsset().getMediaStatus() != MediaStatus.AVAILABLE
                        || evidencePolicy.ineligibilityReason(link.getMediaAsset(), true) != null) continue;
                try {
                    retentionAccess.requireAccessible(link.getMediaAsset());
                } catch (ApiException ex) {
                    if (ex.getErrorCode() == ErrorCode.RESULT_RETENTION_EXPIRED || ex.getErrorCode() == ErrorCode.MEDIA_NOT_FOUND) continue;
                    throw ex;
                }
                OrderChecklistItem item = items.stream().filter(row -> row.getId().equals(itemId)).findFirst().orElse(null);
                if (item == null || !evidencePolicy.mediaTypeMatches(item.getRequiredEvidenceType(), link.getMediaAsset().getType())) continue;
                byChecklist.computeIfAbsent(itemId, ignored -> new LinkedHashMap<>())
                        .putIfAbsent(link.getMediaAsset().getId(), link);
            }
        }
        return byChecklist;
    }

    private void validateChecklistMinimum(OrderChecklistItem item, List<MediaAsset> assets) {
        Map<String, MediaAsset> unique = assets.stream().collect(Collectors.toMap(
                asset -> contentKey(asset), asset -> asset, (left, right) -> left));
        long images = unique.values().stream().filter(asset -> "IMAGE".equals(asset.getType())).count();
        long videos = unique.values().stream().filter(asset -> "VIDEO".equals(asset.getType())).count();
        int minImages = item.getMinimumImageCount();
        int minVideos = item.getMinimumVideoCount();
        if (minImages > 0 || minVideos > 0) {
            if (images < minImages || videos < minVideos)
                throw new ApiException(ErrorCode.ORDER_MEDIA_QUANTITY_NOT_MET);
            return;
        }
        if (item.getMinimumEvidenceCount() <= 0) return;
        boolean satisfied = switch (item.getRequiredEvidenceType()) {
            case IMAGE -> images >= item.getMinimumEvidenceCount();
            case VIDEO -> videos >= item.getMinimumEvidenceCount();
            case IMAGE_OR_VIDEO, IMAGE_AND_VIDEO -> unique.size() >= item.getMinimumEvidenceCount();
        };
        if (!satisfied) throw new ApiException(ErrorCode.ORDER_MEDIA_QUANTITY_NOT_MET);
    }

    private void validateSelectedOrderQuantity(Order order, List<MediaAsset> selected) {
        long requiredImages = 0, requiredVideos = 0;
        for (var deliverable : order.getDeliverables()) {
            var requirement = deliverable.getRequirement();
            if (requirement == null) continue;
            requiredImages += mediaQuantity(requirement.get("imageQuantity"));
            requiredVideos += mediaQuantity(requirement.get("videoQuantity"));
        }
        var distinct = selected.stream().collect(Collectors.toMap(DeliveryWorkflowServiceImpl::contentKey,
                asset -> asset, (left, right) -> left));
        long images = distinct.values().stream().filter(asset -> "IMAGE".equals(asset.getType())).count();
        long videos = distinct.values().stream().filter(asset -> "VIDEO".equals(asset.getType())).count();
        if (images < requiredImages || videos < requiredVideos)
            throw new ApiException(ErrorCode.ORDER_MEDIA_QUANTITY_NOT_MET,
                    "The customer selection must include the distinct image and video quantities ordered");
    }

    private static long mediaQuantity(Object value) {
        if (value instanceof Number number) return Math.max(0, number.longValue());
        try { return value == null ? 0 : Math.max(0, Long.parseLong(value.toString())); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private boolean exceedsChecklistMinimum(OrderChecklistItem item, List<MediaAsset> assets) {
        Map<String, MediaAsset> unique = assets.stream().collect(Collectors.toMap(
                DeliveryWorkflowServiceImpl::contentKey, asset -> asset, (left, right) -> left));
        long images = unique.values().stream().filter(asset -> "IMAGE".equals(asset.getType())).count();
        long videos = unique.values().stream().filter(asset -> "VIDEO".equals(asset.getType())).count();
        if (item.getMinimumImageCount() > 0 || item.getMinimumVideoCount() > 0)
            return images > item.getMinimumImageCount() || videos > item.getMinimumVideoCount();
        return item.getMinimumEvidenceCount() > 0 && unique.size() > item.getMinimumEvidenceCount();
    }

    private static String contentKey(MediaAsset asset) {
        String checksum = asset.getChecksumSha256();
        return checksum == null || checksum.isBlank() ? "id:" + asset.getId() : "sha:" + checksum.toLowerCase(Locale.ROOT);
    }

    private static String provenanceKey(String checklistItemId, String mediaAssetId) {
        return checklistItemId + "|" + mediaAssetId;
    }

    private String checklistPublishFingerprint(String orderId, ChecklistDeliveryPublishRequest request) {
        try {
            StringBuilder canonical = new StringBuilder(orderId).append('\n')
                    .append(Objects.toString(request.baseReviewRoundId(), "")).append('\n')
                    .append(Objects.toString(request.expectedReviewRoundVersion(), "")).append('\n')
                    .append(Objects.toString(request.notes(), "").trim()).append('\n');
            request.checklists().stream().sorted(Comparator.comparing(ChecklistDeliveryPublishRequest.ChecklistSelection::orderChecklistItemId))
                    .forEach(selection -> {
                        canonical.append(selection.orderChecklistItemId()).append('|');
                        Objects.requireNonNullElse(selection.addMediaAssetIds(), List.<String>of()).stream().sorted()
                                .forEach(id -> canonical.append("A:").append(id).append(';'));
                        Objects.requireNonNullElse(selection.removeRoundEvidenceIds(), List.<String>of()).stream().sorted()
                                .forEach(id -> canonical.append("D:").append(id).append(';'));
                        Objects.requireNonNullElse(selection.replacements(), List.<ChecklistDeliveryPublishRequest.Replacement>of()).stream()
                                .sorted(Comparator.comparing(ChecklistDeliveryPublishRequest.Replacement::sourceRoundEvidenceId))
                                .forEach(replacement -> canonical.append("R:").append(replacement.sourceRoundEvidenceId())
                                        .append(':').append(replacement.newMediaAssetId()).append(';'));
                        canonical.append('\n');
                    });
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to fingerprint checklist delivery command", exception);
        }
    }
    private MissionStatus executionStatus(List<Mission> effective) {
        if (effective.isEmpty()) return null;
        var states = effective.stream().map(Mission::getStatus).distinct().toList();
        return states.size() == 1 ? states.getFirst() : MissionStatus.IN_PROGRESS;
    }
    private Invoice invoice(String orderId) {
        return invoices.findByOrderId(orderId).orElseThrow(() -> new ApiException(ErrorCode.INVOICE_NOT_FOUND));
    }
    private static boolean isInvoiceFullyPaid(Invoice invoice) {
        return invoice.getStatus() == InvoiceStatus.PAID && invoice.getRemainingAmount() != null
                && invoice.getRemainingAmount().signum() == 0;
    }
    private void assertOwner(String orderId) {
        Order order = orders.findById(orderId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!order.getCustomer().getId().equals(currentUser.getCurrentUserId())) throw new ApiException(ErrorCode.ACCESS_DENIED);
    }
    private void assertOwnerOrManager(String orderId) { if (!isManager()) assertOwner(orderId); }
    private boolean isManager() {
        User user = currentUser.getCurrentUser();
        return user.getRole() != null && (user.getRole().getCode() == RoleCode.MANAGER || user.getRole().getCode() == RoleCode.ADMIN);
    }
    private DeliveryResponse response(OrderDelivery d, List<Mission> effective, List<MediaAsset> assets, boolean downloadable) {
        Invoice invoice = invoice(d.getOrder().getId());
        Instant expires = Instant.now().plusSeconds(storage.presignedUrlExpiresSeconds());
        List<DeliveryAssetResponse> mapped = assets.stream().map(a -> new DeliveryAssetResponse(a.getId(), a.getType(),
                a.getOriginalFileName(), a.getFileSize(), downloadable ? retainedUrl(a) : null,
                expires, downloadable, derivatives.thumbnailUrl(a), "VIDEO".equals(a.getType())
                        ? retainedUrl(a) : derivatives.previewUrl(a))).toList();
        return new DeliveryResponse(d.getOrder().getId(), d.getStatus(), executionStatus(effective), d.getOrder().getOrderStatus(),
                invoice.getStatus(), invoice.getTotalAmount(), invoice.getPaidAmount(), invoice.getRemainingAmount(),
                d.getDeliveryNotes(), d.getPreviewReleasedAt(), d.getCustomerAcceptedAt(), d.getRevisionRequestedAt(),
                d.getRevisionReason(), d.getRevisionCount(), d.getFinalPaymentConfirmedAt(), d.getOriginalsReleasedAt(), mapped);
    }
    private String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String retainedUrl(MediaAsset asset) {
        retentionAccess.requireAccessible(asset);
        return storage.createPresignedGetUrl(asset.getS3Bucket(), asset.getS3Key());
    }
}
