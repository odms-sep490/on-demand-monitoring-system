package com.ondemandmonitoring.mission.service.impl;

import com.ondemandmonitoring.imagequality.DeliveryImageQualityReader;
import com.ondemandmonitoring.imagequality.DeliveryImageQualityReader.Signal;
import com.ondemandmonitoring.imagequality.ImagePerceptualHash;
import com.ondemandmonitoring.mission.domain.MissionChecklistEvidence;
import com.ondemandmonitoring.mission.domain.MissionChecklistExecution;
import com.ondemandmonitoring.mission.dto.response.ChecklistDeliverySuggestionResponse;
import com.ondemandmonitoring.mission.dto.response.ChecklistDeliverySuggestionResponse.Candidate;
import com.ondemandmonitoring.mission.dto.response.ChecklistDeliverySuggestionResponse.Recommendation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ChecklistDeliverySuggestionPolicy {
    private static final String POLICY_VERSION = "image-delivery-suggestion-v1";
    private final DeliveryImageQualityReader quality;

    public ChecklistDeliverySuggestionResponse suggest(MissionChecklistExecution execution,
            List<MissionChecklistEvidence> images, int requiredImageCount) {
        var signals = quality.read(execution.getMissionId(), images.stream().map(MissionChecklistEvidence::getMediaAsset).toList());
        var ranked = images.stream()
                .map(link -> new RankedImage(link, signals.get(link.getMediaAsset().getId())))
                .sorted(Comparator.comparingInt(RankedImage::rank).thenComparing(image -> image.link().getId()))
                .toList();
        var suggestedIds = new ArrayList<String>();
        var suggestedHashes = new ArrayList<Long>();
        var suggestedContent = new HashSet<String>();
        var candidates = new ArrayList<Candidate>();
        for (RankedImage image : ranked) {
            String evidenceId = image.link().getId();
            String checkId = image.signal() == null ? null : image.signal().checkId();
            if (image.rank() == 2) {
                candidates.add(new Candidate(evidenceId, Recommendation.REVIEW,
                        List.of(checkId == null ? "QUALITY_NOT_CHECKED" : "QUALITY_REVIEW_REQUIRED"), checkId));
                continue;
            }
            String passReason = image.rank() == 0 ? "HUMAN_QUALITY_PASS" : "TECHNICAL_CHECKS_PASS";
            String contentKey = ChecklistEvidencePolicy.contentKey(image.link().getMediaAsset());
            Long hash = image.signal().hash();
            boolean similar = suggestedContent.contains(contentKey)
                    || suggestedHashes.stream().anyMatch(other -> ImagePerceptualHash.similar(hash, other));
            if (similar) {
                candidates.add(new Candidate(evidenceId, Recommendation.RESERVE,
                        List.of(passReason, "SIMILAR_TO_SUGGESTED_IMAGE"), checkId));
            } else if (suggestedIds.size() == requiredImageCount) {
                candidates.add(new Candidate(evidenceId, Recommendation.RESERVE, List.of(passReason), checkId));
            } else {
                suggestedIds.add(evidenceId);
                suggestedContent.add(contentKey);
                if (hash != null) suggestedHashes.add(hash);
                candidates.add(new Candidate(evidenceId, Recommendation.SUGGESTED, List.of(passReason), checkId));
            }
        }
        var warnings = suggestedIds.size() < requiredImageCount
                ? List.of("INSUFFICIENT_RECOMMENDABLE_IMAGES") : List.<String>of();
        return new ChecklistDeliverySuggestionResponse(execution.getVersion(), POLICY_VERSION, requiredImageCount,
                suggestedIds.size(), List.copyOf(suggestedIds), warnings, List.copyOf(candidates), false);
    }

    private record RankedImage(MissionChecklistEvidence link, Signal signal) {
        int rank() {
            if (signal == null) return 2;
            if (signal.humanDecision() != null) return "PASS".equals(signal.humanDecision()) ? 0 : 2;
            return "PASS".equals(signal.technicalDecision()) ? 1 : 2;
        }
    }
}
