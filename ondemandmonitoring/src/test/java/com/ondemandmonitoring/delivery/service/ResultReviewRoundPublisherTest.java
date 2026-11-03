package com.ondemandmonitoring.delivery.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ondemandmonitoring.delivery.domain.OrderDelivery;
import com.ondemandmonitoring.delivery.domain.ResultReviewRound;
import com.ondemandmonitoring.delivery.domain.ResultReviewRoundItem;
import com.ondemandmonitoring.delivery.repository.*;
import com.ondemandmonitoring.finance.repository.QuoteRepository;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.impl.ChecklistEvidencePolicy;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import com.ondemandmonitoring.order.enums.RequiredEvidenceType;
import com.ondemandmonitoring.order.repository.OrderChecklistItemRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ResultReviewRoundPublisherTest {
    @Test void publishesChecklistEvenWhenItHasNoExecutionOrEvidence() {
        ResultReviewRoundRepository rounds = mock(ResultReviewRoundRepository.class);
        ResultReviewRoundItemRepository items = mock(ResultReviewRoundItemRepository.class);
        MissionChecklistExecutionRepository executions = mock(MissionChecklistExecutionRepository.class);
        MissionChecklistEvidenceRepository evidence = mock(MissionChecklistEvidenceRepository.class);
        MissionResultRepository results = mock(MissionResultRepository.class);
        OrderChecklistItemRepository checklist = mock(OrderChecklistItemRepository.class);
        QuoteRepository quotes = mock(QuoteRepository.class);
        Order order = new Order(); order.setId("order"); order.setChecklistRevision(3L);
        OrderChecklistItem missing = new OrderChecklistItem(); missing.setId("checklist"); missing.setChecklistRevision(3L);
        missing.setContent("Check the northern roof"); missing.setRequiredEvidenceType(RequiredEvidenceType.IMAGE_OR_VIDEO);
        when(rounds.findByOrderIdOrderByRoundNumberDesc("order")).thenReturn(List.of());
        when(rounds.save(any())).thenAnswer(call -> call.getArgument(0));
        when(items.save(any())).thenAnswer(call -> call.getArgument(0));
        when(checklist.findAllByOrderIdOrderByDisplayOrderAscIdAsc("order")).thenReturn(List.of(missing));
        when(results.findByMissionIds(List.of())).thenReturn(List.of());
        when(quotes.findFirstByOrderIdAndStatusInOrderByQuoteVersionDesc(eq("order"), anyCollection())).thenReturn(Optional.empty());

        var publisher = new com.ondemandmonitoring.delivery.service.impl.ResultReviewRoundPublisher(rounds, items,
                mock(ResultReviewRoundEvidenceRepository.class), executions, evidence, results, checklist, quotes,
                new ChecklistEvidencePolicy(), new ObjectMapper());
        OrderDelivery delivery = new OrderDelivery(); delivery.setId("delivery");

        ResultReviewRound published = publisher.publish(order, delivery, List.of(), Set.of(), Instant.now(), null);

        assertThat(published.getRoundNumber()).isEqualTo(1);
        verify(items).save(argThat(item -> item.getOrderChecklistItem().getId().equals("checklist")
                && item.getExecutionId() == null && item.getMissionId() == null));
        verifyNoInteractions(executions, evidence);
    }
}
