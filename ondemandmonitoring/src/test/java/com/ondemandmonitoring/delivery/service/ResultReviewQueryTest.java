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
}
