package com.ondemandmonitoring.delivery.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.ondemandmonitoring.delivery.domain.*;
import com.ondemandmonitoring.delivery.repository.*;
import com.ondemandmonitoring.delivery.service.impl.ResultReviewService;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.*;
import org.springframework.test.util.ReflectionTestUtils;

class ResultReviewQueryTest {
    @Test void historyPagesInTheDatabaseAndLoadsRoundItemsOnce() {
        var service = mock(ResultReviewService.class, CALLS_REAL_METHODS);
        var orders = mock(OrderRepository.class);
        var rounds = mock(ResultReviewRoundRepository.class);
        var items = mock(ResultReviewRoundItemRepository.class);
        var user = mock(AuthenticatedUserResolver.class);
        ReflectionTestUtils.setField(service, "orders", orders);
        ReflectionTestUtils.setField(service, "rounds", rounds);
        ReflectionTestUtils.setField(service, "roundItems", items);
        ReflectionTestUtils.setField(service, "currentUser", user);
        var customer = new User(); customer.setId("customer");
        var order = new Order(); order.setId("order"); order.setCustomer(customer);
        when(orders.findById("order")).thenReturn(Optional.of(order));
        when(user.getCurrentUserId()).thenReturn("customer");
        var round = new ResultReviewRound(); round.setId("round"); round.setRoundNumber(11);
        when(rounds.findByOrderIdOrderByRoundNumberDesc(eq("order"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(round), PageRequest.of(2, 5), 21));
        when(items.findByRoundIdInOrderByRoundIdAscDisplayOrderAscIdAsc(List.of("round"))).thenReturn(List.of());
        var result = service.getHistory("order", 2, 5);
        assertThat(result.totalItems()).isEqualTo(21);
        assertThat(result.rounds()).hasSize(1);
        verify(rounds).findByOrderIdOrderByRoundNumberDesc("order", PageRequest.of(2, 5));
        verify(rounds, never()).findByOrderIdOrderByRoundNumberDesc(anyString());
        verify(items, never()).findByRoundIdOrderByDisplayOrderAsc(anyString());
    }
    @Test void ticketIncludesFeedbackFromEveryMissionInsteadOfOnlyTheLatestRequest() {
        var service = mock(ResultReviewService.class, CALLS_REAL_METHODS);
        var requests = mock(ResultRevisionRequestRepository.class);
        var items = mock(ResultRevisionRequestItemRepository.class);
        var media = mock(ResultRevisionRequestItemMediaRepository.class);
        var tickets = mock(com.ondemandmonitoring.support.repository.SupportCustomerTicketRepository.class);
        var authorization = mock(com.ondemandmonitoring.support.service.ISupportAuthorizationService.class);
        var missions = mock(com.ondemandmonitoring.mission.repository.MissionRepository.class);
        ReflectionTestUtils.setField(service, "requests", requests);
        ReflectionTestUtils.setField(service, "requestItems", items);
        ReflectionTestUtils.setField(service, "requestMedia", media);
        ReflectionTestUtils.setField(service, "tickets", tickets);
        ReflectionTestUtils.setField(service, "missions", missions);
        ReflectionTestUtils.setField(service, "supportAuthorization", authorization);
        ReflectionTestUtils.setField(service, "currentUser", mock(AuthenticatedUserResolver.class));
        ReflectionTestUtils.setField(service, "revisionCases", mock(ResultRevisionCaseRepository.class));
        var ticket = new com.ondemandmonitoring.support.domain.SupportCustomerTicket(); ticket.setId("ticket");
        ticket.setTicketKind(com.ondemandmonitoring.support.enums.SupportTicketKind.RESULT_REVISION);
        when(tickets.findById("ticket")).thenReturn(Optional.of(ticket));
        when(authorization.canManageTickets()).thenReturn(true);
        var order = new Order(); order.setId("order");
        List<ResultRevisionRequest> submitted = new ArrayList<>();
        List<ResultRevisionRequestItem> rows = new ArrayList<>();
        List<com.ondemandmonitoring.mission.domain.Mission> missionRows = new ArrayList<>();
        for (int n = 1; n <= 2; n++) {
            var round = new ResultReviewRound(); round.setId("round-" + n); round.setRoundNumber(n);
            var request = new ResultRevisionRequest(); request.setId("request-" + n); request.setOrder(order); request.setRound(round);
            submitted.add(request);
            var checklist = new com.ondemandmonitoring.order.domain.OrderChecklistItem(); checklist.setId("checklist-" + n);
            var source = new ResultReviewRoundItem(); source.setId("snapshot-" + n); source.setOrderChecklistItem(checklist);
            source.setMissionId("mission-" + n);
            var row = new ResultRevisionRequestItem(); row.setId("feedback-" + n); row.setRequest(request); row.setRoundItem(source);
            row.setReasonCode(com.ondemandmonitoring.delivery.enums.ResultRevisionReasonCode.WRONG_LOCATION); row.setNote("note-" + n);
            rows.add(row);
            var mission = new com.ondemandmonitoring.mission.domain.Mission(); mission.setId("mission-" + n); mission.setMissionCode("MS-" + n);
            missionRows.add(mission);
        }
        when(requests.findByTicketIdOrderBySubmittedAtAscIdAsc("ticket")).thenReturn(submitted);
        when(items.findByRequestIdInOrderByCreatedAtAscIdAsc(List.of("request-1", "request-2"))).thenReturn(rows);
        when(missions.findAllById(List.of("mission-1", "mission-2"))).thenReturn(missionRows);
        var result = service.getTicketReview("ticket");
        assertThat(result.items()).extracting(item -> item.missionCode()).containsExactly("MS-1", "MS-2");
        assertThat(result.items()).extracting(item -> item.roundNumber()).containsExactly(1, 2);
        assertThat(result.items()).extracting(item -> item.note()).containsExactly("note-1", "note-2");
        verify(media).findByRequestItemIdIn(List.of("feedback-1", "feedback-2"));
    }
}
