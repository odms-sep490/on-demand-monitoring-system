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
    @Test void publishingOneMissionKeepsAnotherMissionsOpenRoundAndExcludesItsChecklist() {
        var rounds = mock(ResultReviewRoundRepository.class);
        var items = mock(ResultReviewRoundItemRepository.class);
        var checklist = mock(OrderChecklistItemRepository.class);
        var quotes = mock(QuoteRepository.class);
        var order = new Order(); order.setId("order"); order.setChecklistRevision(1L);
        var delivery = new OrderDelivery(); delivery.setId("delivery-a"); delivery.setMissionId("mission-a");
        var previous = new ResultReviewRound(); previous.setDeliveryId("delivery-a"); previous.setRoundNumber(1);
        previous.setStatus(com.ondemandmonitoring.delivery.enums.ResultReviewRoundStatus.OPEN);
        var other = new ResultReviewRound(); other.setDeliveryId("delivery-b"); other.setRoundNumber(2);
        other.setStatus(com.ondemandmonitoring.delivery.enums.ResultReviewRoundStatus.OPEN);
        when(rounds.findByOrderIdOrderByRoundNumberDesc("order")).thenReturn(List.of(other, previous));
        when(rounds.save(any())).thenAnswer(call -> call.getArgument(0));
        when(items.save(any())).thenAnswer(call -> call.getArgument(0));
        var first = new OrderChecklistItem(); first.setId("a"); first.setContent("A"); first.setChecklistRevision(1L);
        first.setRequiredEvidenceType(RequiredEvidenceType.IMAGE);
        var second = new OrderChecklistItem(); second.setId("b"); second.setContent("B"); second.setChecklistRevision(1L);
        when(checklist.findAllByOrderIdOrderByDisplayOrderAscIdAsc("order")).thenReturn(List.of(first, second));
        var publisher = new com.ondemandmonitoring.delivery.service.impl.ResultReviewRoundPublisher(rounds, items,
                mock(ResultReviewRoundEvidenceRepository.class), mock(MissionChecklistExecutionRepository.class),
                mock(MissionChecklistEvidenceRepository.class), mock(MissionResultRepository.class), checklist, quotes,
                new ChecklistEvidencePolicy(), new ObjectMapper());
        var round = publisher.publishByChecklist(order, delivery, List.of(), java.util.Map.of("a", Set.of()),
                Instant.now(), null, java.util.Map.of(), java.util.Map.of(), Set.of("a"), true);
        assertThat(round.getMissionId()).isEqualTo("mission-a");
        assertThat(round.getRoundNumber()).isEqualTo(3);
        assertThat(previous.getStatus()).isEqualTo(com.ondemandmonitoring.delivery.enums.ResultReviewRoundStatus.SUPERSEDED);
        assertThat(other.getStatus()).isEqualTo(com.ondemandmonitoring.delivery.enums.ResultReviewRoundStatus.OPEN);
        verify(items).save(argThat(item -> item.getOrderChecklistItem().getId().equals("a")));
        verify(items, times(1)).save(any());
    }
}
