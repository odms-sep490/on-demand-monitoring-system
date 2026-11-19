package com.ondemandmonitoring.delivery.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import com.ondemandmonitoring.delivery.domain.ResultRevisionCaseItem;
import com.ondemandmonitoring.delivery.enums.ResultRevisionStrategy;
import org.junit.jupiter.api.Test;

class ReworkDeliveryEvidencePolicyTest {
    @Test
    void onlyCurrentReworkMediaCanReplaceOrRemainInTheReworkDelivery() {
        var item = new ResultRevisionCaseItem();
        item.setStrategy(ResultRevisionStrategy.REWORK);
        item.setAssignedReworkMissionId("current");
        assertThat(ReworkDeliveryEvidencePolicy.allows(item, "current")).isTrue();
        assertThat(ReworkDeliveryEvidencePolicy.allows(item, "original")).isFalse();
        assertThat(ReworkDeliveryEvidencePolicy.allows(item, "earlier-rework")).isFalse();
        assertThat(ReworkDeliveryEvidencePolicy.allows(item, null)).isFalse();
        item.setAssignedReworkMissionId(null);
        assertThat(ReworkDeliveryEvidencePolicy.allows(item, "current")).isFalse();
    }
    @Test
    void originalAndReserveDeliveryKeepTheirExistingSources() {
        var item = new ResultRevisionCaseItem();
        item.setStrategy(ResultRevisionStrategy.RESERVE_MEDIA);
        assertThat(ReworkDeliveryEvidencePolicy.allows(item, "original")).isTrue();
        assertThat(ReworkDeliveryEvidencePolicy.allows(null, "original")).isTrue();
    }

    @Test
    void acceptedSupplementReplacesOnlyTheDelegatedReworkChecklist() {
        var checklist = new com.ondemandmonitoring.order.domain.OrderChecklistItem(); checklist.setId("fence");
        var item = new ResultRevisionCaseItem(); item.setOrderChecklistItem(checklist);
        item.setStrategy(ResultRevisionStrategy.REWORK); item.setAssignedReworkMissionId("rework");
        var execution = new com.ondemandmonitoring.mission.domain.MissionChecklistExecution();
        execution.setOrderChecklistItemId("fence");
        var accepted = new com.ondemandmonitoring.media.domain.MediaAsset();
        accepted.setId("accepted"); accepted.setMissionId("supplement");
        var link = new com.ondemandmonitoring.mission.domain.MissionChecklistEvidence();
        link.setMissionChecklistExecution(execution); link.setMediaAsset(accepted);
        var originals = new com.ondemandmonitoring.media.domain.MediaAsset();
        originals.setId("old"); originals.setMissionId("rework");
        var reserve = new com.ondemandmonitoring.media.domain.MediaAsset();
        reserve.setId("unselected"); reserve.setMissionId("supplement");
        assertThat(ReworkDeliveryEvidencePolicy.allows(item, accepted, java.util.List.of(link))).isTrue();
        assertThat(ReworkDeliveryEvidencePolicy.allows(item, originals, java.util.List.of(link))).isFalse();
        assertThat(ReworkDeliveryEvidencePolicy.allows(item, reserve, java.util.List.of(link))).isFalse();
        checklist.setId("other");
        assertThat(ReworkDeliveryEvidencePolicy.allows(item, originals, java.util.List.of(link))).isTrue();
        assertThat(ReworkDeliveryEvidencePolicy.allows(item, accepted, java.util.List.of(link))).isFalse();
    }

    @Test
    void eligibilityRejectsNonCompliantUnfinishedDetachedAndDelegatedExecutions() {
        var execution = new com.ondemandmonitoring.mission.domain.MissionChecklistExecution();
        execution.setId("exec-1");
        execution.setExecutionStatus(com.ondemandmonitoring.mission.enums.ChecklistExecutionStatus.COMPLETED);
        execution.setAssessmentStatus(com.ondemandmonitoring.mission.enums.ChecklistAssessmentStatus.COMPLIANT);
        var asset = new com.ondemandmonitoring.media.domain.MediaAsset();
        asset.setId("media-1");
        var link = new com.ondemandmonitoring.mission.domain.MissionChecklistEvidence();
        link.setMissionChecklistExecution(execution);
        link.setMediaAsset(asset);

        assertThat(ReworkDeliveryEvidencePolicy.isEligibleChecklistEvidence(link, java.util.Set.of())).isTrue();
        assertThat(ReworkDeliveryEvidencePolicy.isEligibleChecklistEvidence(link, java.util.Set.of("exec-1"))).isFalse();

        execution.setAssessmentStatus(com.ondemandmonitoring.mission.enums.ChecklistAssessmentStatus.NON_COMPLIANT);
        assertThat(ReworkDeliveryEvidencePolicy.isEligibleChecklistEvidence(link, java.util.Set.of())).isFalse();

        execution.setAssessmentStatus(com.ondemandmonitoring.mission.enums.ChecklistAssessmentStatus.COMPLIANT);
        execution.setExecutionStatus(com.ondemandmonitoring.mission.enums.ChecklistExecutionStatus.IN_PROGRESS);
        assertThat(ReworkDeliveryEvidencePolicy.isEligibleChecklistEvidence(link, java.util.Set.of())).isFalse();

        execution.setExecutionStatus(com.ondemandmonitoring.mission.enums.ChecklistExecutionStatus.COMPLETED);
        link.setDetachedAt(java.time.Instant.now());
        assertThat(ReworkDeliveryEvidencePolicy.isEligibleChecklistEvidence(link, java.util.Set.of())).isFalse();
    }
}
