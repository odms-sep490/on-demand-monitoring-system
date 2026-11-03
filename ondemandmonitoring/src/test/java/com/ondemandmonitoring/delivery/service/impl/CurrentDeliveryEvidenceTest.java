package com.ondemandmonitoring.delivery.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.ondemandmonitoring.delivery.domain.OrderDelivery;
import com.ondemandmonitoring.delivery.repository.OrderDeliveryRepository;
import com.ondemandmonitoring.delivery.repository.ResultReviewRoundEvidenceRepository;
import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.repository.MediaAssetRepository;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.order.domain.Order;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class CurrentDeliveryEvidenceTest {
    final DeliveryWorkflowServiceImpl service = mock(DeliveryWorkflowServiceImpl.class, CALLS_REAL_METHODS);
    final MissionRepository missions = mock(MissionRepository.class);
    final ResultReviewRoundEvidenceRepository evidence = mock(ResultReviewRoundEvidenceRepository.class);
    final OrderDeliveryRepository deliveries = mock(OrderDeliveryRepository.class);
    final MediaAssetRepository media = mock(MediaAssetRepository.class);
    final OrderDelivery delivery = new OrderDelivery();
    final Mission mission = new Mission();

    @BeforeEach
    void prepare() {
        ReflectionTestUtils.setField(service, "missions", missions);
        ReflectionTestUtils.setField(service, "roundEvidence", evidence);
        ReflectionTestUtils.setField(service, "deliveries", deliveries);
        ReflectionTestUtils.setField(service, "media", media);
        var order = new Order(); order.setId("order");
        mission.setId("mission"); mission.setOrder(order); mission.setStatus(MissionStatus.COMPLETED);
        delivery.setOrder(order); delivery.setPreviewReleasedAt(Instant.now());
        when(missions.findAllById(List.of("mission"))).thenReturn(List.of(mission));
        when(deliveries.findByOrderIdIn(Set.of("order"))).thenReturn(List.of(delivery));
    }

    @Test
    void currentSnapshotIncludesReplacementsAndUnchangedChecklistsButNeverResurrectsOldFlags() {
        delivery.setCurrentReviewRoundId("accepted-current-round");
        when(evidence.findCurrentPublishedMediaAssetIdsByOrderIds(List.of("order")))
                .thenReturn(Set.of("new-rework", "unchanged-checklist"));

        assertThat(service.publishedMediaAssetIds(List.of("mission")))
                .containsExactlyInAnyOrder("new-rework", "unchanged-checklist");
        verifyNoInteractions(media);
        verify(missions, never()).findAllByOrderIdOrderByScheduledStartAtAscIdAsc(anyString());
    }

    @Test
    void preSnapshotLegacyDeliveriesKeepOnlyTheirExplicitSelections() {
        delivery.setCurrentReviewRoundId(null);
        var selected = new MediaAsset(); selected.setId("legacy-selected"); selected.setPreviewSelected(true);
        var reserve = new MediaAsset(); reserve.setId("reserve-not-published"); reserve.setPreviewSelected(false);
        when(evidence.findCurrentPublishedMediaAssetIdsByOrderIds(List.of("order"))).thenReturn(Set.of());
        when(missions.findAllByOrderIdOrderByScheduledStartAtAscIdAsc("order")).thenReturn(List.of(mission));
        when(media.findByMissionIdOrderByCapturedAtDesc("mission")).thenReturn(List.of(selected, reserve));

        assertThat(service.publishedMediaAssetIds(List.of("mission"))).containsExactly("legacy-selected");
    }
}
