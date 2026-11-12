package com.ondemandmonitoring.refund.websocket;

import com.ondemandmonitoring.refund.event.RefundStatusChangedEvent;
import com.ondemandmonitoring.user.domain.UserIdentity;
import com.ondemandmonitoring.user.repository.UserIdentityRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class CustomerRefundNotificationListener {
    private static final String CUSTOMER_ORDER_EVENTS_DESTINATION = "/queue/order-events";

    private final UserIdentityRepository identities;
    private final SimpMessagingTemplate messagingTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void notifyRefundStatus(RefundStatusChangedEvent event) {
        var message = new CustomerRefundEvent("DEPOSIT_REFUND_STATUS_CHANGED", event.orderId(), event.refundId(),
                event.status().name(), event.occurredAt());
        List<String> subjects = identities.findAllByUserId(event.customerId()).stream()
                .map(UserIdentity::getCognitoSub)
                .filter(subject -> subject != null && !subject.isBlank())
                .distinct()
                .toList();
        for (String subject : subjects) {
            try {
                messagingTemplate.convertAndSendToUser(subject, CUSTOMER_ORDER_EVENTS_DESTINATION, message);
            } catch (RuntimeException exception) {
                log.warn("Unable to send Deposit refund update for Order {}", event.orderId(), exception);
            }
        }
    }

    public record CustomerRefundEvent(
            String eventType,
            String orderId,
            String refundId,
            String status,
            java.time.Instant occurredAt) {
    }
}
