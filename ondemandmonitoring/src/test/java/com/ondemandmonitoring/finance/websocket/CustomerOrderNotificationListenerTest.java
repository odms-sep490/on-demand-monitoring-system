package com.ondemandmonitoring.finance.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ondemandmonitoring.finance.event.FinalPaymentRequiredEvent;
import com.ondemandmonitoring.user.domain.UserIdentity;
import com.ondemandmonitoring.user.repository.UserIdentityRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class CustomerOrderNotificationListenerTest {
    private final UserIdentityRepository identities = mock(UserIdentityRepository.class);
    private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    private final CustomerOrderNotificationListener listener =
            new CustomerOrderNotificationListener(identities, messaging);

    @Test
    void sendsFinalPaymentAvailabilityToEveryCustomerIdentity() {
        var identity = UserIdentity.builder().cognitoSub("cognito-customer").build();
        when(identities.findAllByUserId("customer-1")).thenReturn(List.of(identity));
        var occurredAt = Instant.parse("2026-11-02T10:00:00Z");

        listener.notifyFinalPaymentRequired(
                new FinalPaymentRequiredEvent("customer-1", "order-1", "invoice-1", occurredAt));

        ArgumentCaptor<CustomerOrderEvent> message = ArgumentCaptor.forClass(CustomerOrderEvent.class);
        verify(messaging).convertAndSendToUser(
                org.mockito.ArgumentMatchers.eq("cognito-customer"),
                org.mockito.ArgumentMatchers.eq("/queue/order-events"),
                message.capture());
        assertEquals("FINAL_PAYMENT_REQUIRED", message.getValue().eventType());
        assertEquals("order-1", message.getValue().orderId());
        assertEquals("invoice-1", message.getValue().invoiceId());
        assertEquals(occurredAt, message.getValue().occurredAt());
    }
}
