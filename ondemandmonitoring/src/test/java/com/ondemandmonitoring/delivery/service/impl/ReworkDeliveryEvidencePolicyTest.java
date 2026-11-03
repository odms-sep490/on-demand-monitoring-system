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
}
