package com.ondemandmonitoring.delivery.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.delivery.domain.OrderDelivery;
import com.ondemandmonitoring.delivery.domain.ResultReviewRound;
import com.ondemandmonitoring.delivery.dto.request.RevisionRequest;
import com.ondemandmonitoring.delivery.enums.DeliveryStatus;
import com.ondemandmonitoring.delivery.enums.ResultReviewRoundStatus;
import com.ondemandmonitoring.delivery.repository.*;
import com.ondemandmonitoring.delivery.service.impl.ResultReviewService;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.support.service.impl.SupportTicketService;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ResultReviewLifecycleTest {
    @Mock OrderRepository orders;
    @Mock OrderDeliveryRepository deliveries;
    @Mock ResultReviewRoundRepository rounds;
    @Mock ResultRevisionRequestRepository requests;
    @Mock SupportTicketService tickets;
    @Mock ResultRevisionCaseRepository cases;
    @Mock AuthenticatedUserResolver users;
    @Spy ObjectMapper mapper = new ObjectMapper();
    @InjectMocks ResultReviewService service;

    final OrderDelivery delivery = new OrderDelivery();
    final ResultReviewRound round = new ResultReviewRound();

    @BeforeEach void setUp() {
        var customer = new User(); customer.setId("customer");
        var order = new Order(); order.setId("order"); order.setCustomer(customer);
        delivery.setOrder(order); delivery.setCurrentReviewRoundId("round");
        delivery.setStatus(DeliveryStatus.CUSTOMER_REVIEW);
        delivery.setCustomerAcceptedAt(Instant.now());
        round.setId("round"); round.setVersion(2L); round.setStatus(ResultReviewRoundStatus.OPEN);
        when(users.getCurrentUser()).thenReturn(customer);
        when(users.getCurrentUserId()).thenReturn("customer");
        when(orders.findByIdForUpdate("order")).thenReturn(Optional.of(order));
        when(deliveries.findByOrderIdForUpdate("order")).thenReturn(Optional.of(delivery));
        when(rounds.findByIdForUpdate("round")).thenReturn(Optional.of(round));
    }

    @ParameterizedTest
    @EnumSource(value = DeliveryStatus.class, names = {"READY_FOR_DELIVERY", "DELIVERED", "AWAITING_MISSION_PAYMENT"})
    void acceptedDeliveryCannotCreateAnotherRevisionCase(DeliveryStatus status) {
        delivery.setStatus(status);
        assertClosedToRevisions();
    }

    @Test void acceptedRoundCannotBeReopenedByAStaleDeliveryStatus() {
        round.setStatus(ResultReviewRoundStatus.ACCEPTED);
        assertClosedToRevisions();
    }

    private void assertClosedToRevisions() {
        var command = new RevisionRequest("round", 2L, UUID.randomUUID(), null,
                List.of(new RevisionRequest.Item("item", "WRONG_LOCATION", "Wrong angle", List.of())));
        assertThatThrownBy(() -> service.submit("order", command)).isInstanceOf(ApiException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESULT_REVIEW_REFRESH_REQUIRED);
        verifyNoInteractions(tickets, cases);
        verify(requests, never()).saveAndFlush(any());
    }
}
