package com.ondemandmonitoring.delivery.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.delivery.domain.ResultReviewRound;
import com.ondemandmonitoring.delivery.domain.ResultRevisionRequest;
import com.ondemandmonitoring.delivery.dto.request.RevisionRequest;
import com.ondemandmonitoring.delivery.repository.*;
import com.ondemandmonitoring.delivery.service.impl.ResultReviewService;
import com.ondemandmonitoring.media.service.IMediaObjectStorage;
import com.ondemandmonitoring.media.service.IResultRetentionAccessService;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.support.domain.SupportCustomerTicket;
import com.ondemandmonitoring.support.repository.SupportCustomerTicketRepository;
import com.ondemandmonitoring.support.service.ISupportAuthorizationService;
import com.ondemandmonitoring.support.service.impl.SupportTicketService;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ResultRevisionIdempotencyTest {
    @Test void sameCustomerKeyOnDifferentOrderOrRoundIsConflictInsteadOfReplayingFirstTicket() {
        var rounds = mock(ResultReviewRoundRepository.class);
        var requests = mock(ResultRevisionRequestRepository.class);
        var ticketRepo = mock(SupportCustomerTicketRepository.class);
        var orders = mock(OrderRepository.class);
        var users = mock(AuthenticatedUserResolver.class);
        User customer = new User(); customer.setId("customer");
        Order currentOrder = new Order(); currentOrder.setId("order-b"); currentOrder.setCustomer(customer);
        Order originalOrder = new Order(); originalOrder.setId("order-a"); originalOrder.setCustomer(customer);
        ResultReviewRound originalRound = new ResultReviewRound(); originalRound.setId("round-a"); originalRound.setOrder(originalOrder);
        UUID key = UUID.randomUUID();
        ResultRevisionRequest prior = new ResultRevisionRequest(); prior.setOrder(originalOrder); prior.setRound(originalRound);
        prior.setPayloadFingerprint("same-body-hash"); prior.setTicketId("ticket-a");
        when(users.getCurrentUser()).thenReturn(customer);
        when(users.getCurrentUserId()).thenReturn(customer.getId());
        when(orders.findByIdForUpdate("order-b")).thenReturn(Optional.of(currentOrder));
        when(requests.findByCustomerIdAndClientRequestId("customer", key)).thenReturn(Optional.of(prior));
        var service = new ResultReviewService(rounds, mock(ResultReviewRoundItemRepository.class),
                mock(ResultReviewRoundEvidenceRepository.class), requests, mock(ResultRevisionRequestItemRepository.class),
                mock(ResultRevisionRequestItemMediaRepository.class), mock(ResultRevisionCaseRepository.class),
                mock(ResultRevisionCaseItemRepository.class), mock(ResultRevisionMediaHoldRepository.class),
                mock(com.ondemandmonitoring.media.repository.MediaAssetRepository.class),
                mock(OrderDeliveryRepository.class), orders,
                mock(com.ondemandmonitoring.mission.repository.MissionRepository.class), ticketRepo,
                mock(SupportTicketService.class), mock(ISupportAuthorizationService.class), users,
                mock(IMediaObjectStorage.class), mock(IResultRetentionAccessService.class), new ObjectMapper());

        var command = new RevisionRequest("round-b", 0L, key, null,
                List.of(new RevisionRequest.Item("item-b", "POOR_QUALITY", "Blurry", List.of())));
        assertThatThrownBy(() -> service.submit("order-b", command))
                .isInstanceOf(ApiException.class).extracting("errorCode").isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        verifyNoInteractions(ticketRepo);
    }
}
