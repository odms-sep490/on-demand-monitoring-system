package com.ondemandmonitoring.delivery.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ondemandmonitoring.delivery.domain.*;
import com.ondemandmonitoring.delivery.enums.ResultReviewRoundStatus;
import com.ondemandmonitoring.delivery.repository.*;
import com.ondemandmonitoring.finance.enums.QuoteStatus;
import com.ondemandmonitoring.finance.repository.QuoteRepository;
import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.enums.ChecklistExecutionStatus;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.impl.ChecklistEvidencePolicy;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import com.ondemandmonitoring.order.repository.OrderChecklistItemRepository;
import com.ondemandmonitoring.user.domain.User;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Creates the immutable customer-visible snapshot as part of releasePreview's transaction. */
@Service
@RequiredArgsConstructor
public class ResultReviewRoundPublisher {
    private final ResultReviewRoundRepository rounds;
    private final ResultReviewRoundItemRepository roundItems;
    private final ResultReviewRoundEvidenceRepository roundEvidence;
    private final MissionChecklistExecutionRepository executions;
    private final MissionChecklistEvidenceRepository evidence;
    private final MissionResultRepository results;
    private final OrderChecklistItemRepository checklistItems;
    private final QuoteRepository quotes;
    private final ChecklistEvidencePolicy evidencePolicy;
    private final ObjectMapper mapper;

    public ResultReviewRound publish(Order order, OrderDelivery delivery, List<Mission> missions,
                                     Set<String> selectedMediaIds, Instant publishedAt, User publishedBy) {
        return publishInternal(order, delivery, missions, selectedMediaIds, null, publishedAt, publishedBy,
                Map.of(), Map.of(), Set.of(), true);
    }

    public ResultReviewRound publishByChecklist(Order order, OrderDelivery delivery, List<Mission> missions,
            Map<String, Set<String>> selectedByChecklist, Instant publishedAt, User publishedBy,
            Map<String, String> sourceRoundEvidenceByMedia, Map<String, String> supersededEvidenceByMedia,
            Set<String> changedChecklistIds, boolean initialRound) {
        Set<String> selectedMediaIds = selectedByChecklist.values().stream().flatMap(Set::stream)
                .collect(Collectors.toUnmodifiableSet());
        return publishInternal(order, delivery, missions, selectedMediaIds, selectedByChecklist, publishedAt,
                publishedBy, sourceRoundEvidenceByMedia, supersededEvidenceByMedia, changedChecklistIds, initialRound);
    }

    private ResultReviewRound publishInternal(Order order, OrderDelivery delivery, List<Mission> missions,
            Set<String> selectedMediaIds, Map<String, Set<String>> selectedByChecklist, Instant publishedAt,
            User publishedBy, Map<String, String> sourceRoundEvidenceByMedia,
            Map<String, String> supersededEvidenceByMedia, Set<String> changedChecklistIds, boolean initialRound) {
        List<ResultReviewRound> history = rounds.findByOrderIdOrderByRoundNumberDesc(order.getId());
        history.stream().filter(r -> Objects.equals(r.getDeliveryId(), delivery.getId()) && r.getStatus() == ResultReviewRoundStatus.OPEN)
                .forEach(r -> r.setStatus(ResultReviewRoundStatus.SUPERSEDED));
        List<OrderChecklistItem> snapshot = checklistItems.findAllByOrderIdOrderByDisplayOrderAscIdAsc(order.getId()).stream()
                .filter(item -> delivery.getMissionId() == null || (selectedByChecklist != null && selectedByChecklist.containsKey(item.getId()))).toList();
        Map<String, OrderChecklistItem> byId = snapshot.stream().collect(Collectors.toMap(OrderChecklistItem::getId, i -> i));
        Map<String, List<MissionChecklistExecution>> executionByItem = new LinkedHashMap<>();
        Map<String, List<MissionChecklistEvidence>> evidenceByExecution = new HashMap<>();
        Map<String, MissionResult> resultByMission = results.findByMissionIds(missions.stream().map(Mission::getId).toList())
                .stream().collect(Collectors.toMap(r -> r.getMission().getId(), r -> r, (a,b) -> a));
        for (Mission mission : missions) {
            List<MissionChecklistEvidence> missionEvidence = evidence.findActiveByMissionId(mission.getId());
            for (MissionChecklistExecution execution : executions.findOrderedByMissionId(mission.getId())) {
                if (execution.getExecutionStatus() != ChecklistExecutionStatus.COMPLETED) continue;
                OrderChecklistItem item = byId.get(execution.getOrderChecklistItemId());
                if (item == null) continue;
                executionByItem.computeIfAbsent(item.getId(), ignored -> new ArrayList<>()).add(execution);
                evidenceByExecution.put(execution.getId(), missionEvidence.stream()
                        .filter(e -> execution.getId().equals(e.getMissionChecklistExecution().getId())).toList());
            }
        }
        QuoteStatus[] statuses = {QuoteStatus.ACCEPTED_BY_CUSTOMER};
        var accepted = quotes.findFirstByOrderIdAndStatusInOrderByQuoteVersionDesc(order.getId(), List.of(statuses));
        var guidance = accepted.map(q -> q.getChecklistGuidanceSnapshot()).orElse(null);

        ResultReviewRound round = new ResultReviewRound();
        round.setOrder(order);
        round.setDeliveryId(delivery.getId());
        round.setMissionId(delivery.getMissionId());
        round.setRoundNumber(history.stream()
                .mapToInt(ResultReviewRound::getRoundNumber).max().orElse(0) + 1);
        round.setChecklistRevision(order.getChecklistRevision());
        round.setPublishedAt(publishedAt);
        round.setPublishedBy(publishedBy);
        round.setSchemaVersion(1);
        round.setStatus(ResultReviewRoundStatus.OPEN);
        round = rounds.save(round);

        int displayOrder = 0;
        for (OrderChecklistItem item : snapshot) {
            if (item.getChecklistRevision() != order.getChecklistRevision()) continue;
            List<MissionChecklistExecution> itemExecutions = executionByItem.getOrDefault(item.getId(), List.of());
            ResultReviewRoundItem ri = new ResultReviewRoundItem();
            ri.setRound(round);
            ri.setOrderChecklistItem(item);
            ri.setContentSnapshot(item.getContent());
            ri.setEvidencePolicySnapshot(mapper.valueToTree(Map.of("version", item.getEvidencePolicyVersion(),
                    "minimumEvidenceCount", item.getMinimumEvidenceCount(), "minimumImageCount", item.getMinimumImageCount(),
                    "minimumVideoCount", item.getMinimumVideoCount(), "requiredEvidenceType", item.getRequiredEvidenceType().name())));
            var acceptedGuidanceItem = guidance == null || guidance.items() == null ? null : guidance.items().stream()
                    .filter(g -> item.getId().equals(g.itemId()) && item.getChecklistRevision() == g.checklistRevision()).findFirst().orElse(null);
            Object guidanceValue = acceptedGuidanceItem != null ? acceptedGuidanceItem : Map.of(
                    "description", Objects.toString(item.getEffectiveDescription(), ""),
                    "executionSteps", Objects.requireNonNullElse(item.getEffectiveExecutionSteps(), List.of()),
                    "inputRequirements", Objects.requireNonNullElse(item.getEffectiveInputRequirements(), List.of()),
                    "expectedOutputs", Objects.requireNonNullElse(item.getEffectiveExpectedOutputs(), List.of()));
            ri.setGuidanceSnapshot(mapper.valueToTree(guidanceValue));
            ri.setDisplayOrder(displayOrder++);
            ri.setChangedInRound(changedChecklistIds.contains(item.getId()));
            MissionChecklistExecution execution = itemExecutions.isEmpty() ? null : itemExecutions.getFirst();
            if (execution != null) {
                ri.setExecutionId(execution.getId());
                ri.setMissionId(execution.getMissionId());
                MissionResult result = resultByMission.get(execution.getMissionId());
                if (result != null) { ri.setMissionResultId(result.getId()); ri.setResultVersion(Objects.toString(result.getVersion(), "")); }
            }
            ri = roundItems.save(ri);
            for (MissionChecklistExecution source : itemExecutions) {
                MissionResult result = resultByMission.get(source.getMissionId());
                if (result == null) continue;
                Set<String> selectedForItem = selectedByChecklist == null
                        ? selectedMediaIds : selectedByChecklist.getOrDefault(item.getId(), Set.of());
                for (MissionChecklistEvidence link : evidenceByExecution.getOrDefault(source.getId(), List.of())) {
                    MediaAsset asset = link.getMediaAsset();
                    if (!selectedForItem.contains(asset.getId()) || evidencePolicy.ineligibilityReason(asset, true) != null
                            || !evidencePolicy.mediaTypeMatches(item.getRequiredEvidenceType(), asset.getType())) continue;
                    ResultReviewRoundEvidence re = new ResultReviewRoundEvidence();
                    re.setRoundItem(ri); re.setMediaAsset(asset); re.setExecutionId(source.getId()); re.setEvidenceId(link.getId());
                    re.setFileNameSnapshot(asset.getOriginalFileName()); re.setMediaTypeSnapshot(asset.getType());
                    re.setCapturedAtSnapshot(asset.getCapturedAt());
                    String provenanceKey = item.getId() + "|" + asset.getId();
                    String sourceId = sourceRoundEvidenceByMedia.get(provenanceKey);
                    String supersededId = supersededEvidenceByMedia.get(provenanceKey);
                    re.setSourceRoundEvidenceId(sourceId);
                    re.setSupersedesRoundEvidenceId(supersededId);
                    re.setChangeType(supersededId != null
                            ? com.ondemandmonitoring.delivery.enums.DeliveryEvidenceChangeType.REPLACEMENT
                            : sourceId != null
                                ? com.ondemandmonitoring.delivery.enums.DeliveryEvidenceChangeType.RETAINED
                                : initialRound
                                    ? com.ondemandmonitoring.delivery.enums.DeliveryEvidenceChangeType.INITIAL
                                    : com.ondemandmonitoring.delivery.enums.DeliveryEvidenceChangeType.ADDED);
                    roundEvidence.save(re);
                }
            }
        }
        return round;
    }
}
