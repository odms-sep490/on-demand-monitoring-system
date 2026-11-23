package com.ondemandmonitoring.mission.service;

import static com.ondemandmonitoring.mission.dto.response.ChecklistDeliverySuggestionResponse.Recommendation.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ondemandmonitoring.imagequality.DeliveryImageQualityReader;
import com.ondemandmonitoring.imagequality.DeliveryImageQualityReader.Signal;
import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.mission.domain.MissionChecklistEvidence;
import com.ondemandmonitoring.mission.domain.MissionChecklistExecution;
import com.ondemandmonitoring.mission.dto.response.ChecklistDeliverySuggestionResponse.Candidate;
import com.ondemandmonitoring.mission.service.impl.ChecklistDeliverySuggestionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChecklistDeliverySuggestionPolicyTest {
    @Test void ranksHumanPassFirstAndUsesExactContentAndInclusiveDHashDistance() {
        var reader = mock(DeliveryImageQualityReader.class);
        var policy = new ChecklistDeliverySuggestionPolicy(reader);
        var execution = new MissionChecklistExecution();
        execution.setMissionId("mission");
        execution.setVersion(17L);
        var images = new ArrayList<>(List.of(
                image("a-similar", "different"), image("b-exact", "SAME"), image("c-distinct", "six"),
                image("d-extra", "extra"), image("z-human", "same")));
        when(reader.read(eq("mission"), anyList())).thenReturn(Map.of(
                "a-similar", new Signal("near", null, "PASS", 31L),
                "b-exact", new Signal("exact", null, "PASS", null),
                "c-distinct", new Signal("distinct", null, "PASS", 63L),
                "d-extra", new Signal("extra", null, "PASS", 65535L),
                "z-human", new Signal("human", "PASS", null, 0L)));

        var response = policy.suggest(execution, images, 2);

        assertEquals(List.of("z-human", "c-distinct"), response.suggestedImageEvidenceIds());
        assertEquals(List.of(
                new Candidate("z-human", SUGGESTED, List.of("HUMAN_QUALITY_PASS"), "human"),
                new Candidate("a-similar", RESERVE, List.of("TECHNICAL_CHECKS_PASS", "SIMILAR_TO_SUGGESTED_IMAGE"), "near"),
                new Candidate("b-exact", RESERVE, List.of("TECHNICAL_CHECKS_PASS", "SIMILAR_TO_SUGGESTED_IMAGE"), "exact"),
                new Candidate("c-distinct", SUGGESTED, List.of("TECHNICAL_CHECKS_PASS"), "distinct"),
                new Candidate("d-extra", RESERVE, List.of("TECHNICAL_CHECKS_PASS"), "extra")),
                response.candidates());
        Collections.reverse(images);
        assertEquals(response, policy.suggest(execution, images, 2));
    }

    @Test void returnsPartialSuggestionWithoutFillingFromUnreviewedOrVetoedImages() {
        var reader = mock(DeliveryImageQualityReader.class);
        var policy = new ChecklistDeliverySuggestionPolicy(reader);
        var execution = new MissionChecklistExecution();
        execution.setMissionId("mission");
        execution.setVersion(1L);
        var images = List.of(image("fail", "a"), image("review", "b"), image("pending", "c"),
                image("unchecked", "d"), image("pass", "e"));
        when(reader.read(eq("mission"), anyList())).thenReturn(Map.of(
                "fail", new Signal("failed", "FAIL", "PASS", 0L),
                "review", new Signal("reviewed", "REVIEW_REQUIRED", "PASS", 7L),
                "pending", new Signal("pending", null, null, null),
                "pass", new Signal("passed", null, "PASS", 15L)));

        var response = policy.suggest(execution, images, 3);

        assertEquals(List.of("pass"), response.suggestedImageEvidenceIds());
        assertEquals(List.of("INSUFFICIENT_RECOMMENDABLE_IMAGES"), response.warnings());
        assertEquals(List.of(
                new Candidate("pass", SUGGESTED, List.of("TECHNICAL_CHECKS_PASS"), "passed"),
                new Candidate("fail", REVIEW, List.of("QUALITY_REVIEW_REQUIRED"), "failed"),
                new Candidate("pending", REVIEW, List.of("QUALITY_REVIEW_REQUIRED"), "pending"),
                new Candidate("review", REVIEW, List.of("QUALITY_REVIEW_REQUIRED"), "reviewed"),
                new Candidate("unchecked", REVIEW, List.of("QUALITY_NOT_CHECKED"), null)),
                response.candidates());
    }

    private MissionChecklistEvidence image(String id, String checksum) {
        var asset = new MediaAsset();
        asset.setId(id);
        asset.setChecksumSha256(checksum);
        var link = new MissionChecklistEvidence();
        link.setId(id);
        link.setMediaAsset(asset);
        return link;
    }
}
