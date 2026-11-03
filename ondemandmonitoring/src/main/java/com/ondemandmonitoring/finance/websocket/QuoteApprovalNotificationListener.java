package com.ondemandmonitoring.finance.websocket;

import com.ondemandmonitoring.finance.event.QuoteApprovedEvent;
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
public class QuoteApprovalNotificationListener {
    private static final String CUSTOMER_ORDER_EVENTS_DESTINATION = "/queue/order-events";

    private final UserIdentityRepository identities;
    private final SimpMessagingTemplate messagingTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void notifyCustomer(QuoteApprovedEvent event) {
        List<String> cognitoSubjects = identities.findAllByUserId(event.customerId()).stream()
                .map(UserIdentity::getCognitoSub)
                .filter(subject -> subject != null && !subject.isBlank())
                .distinct()
                .toList();

        CustomerOrderEvent message = new CustomerOrderEvent(
                "QUOTE_APPROVED", event.orderId(), event.quoteId(), event.approvedAt());
        for (String cognitoSubject : cognitoSubjects) {
            try {
                messagingTemplate.convertAndSendToUser(
                        cognitoSubject, CUSTOMER_ORDER_EVENTS_DESTINATION, message);
            } catch (RuntimeException exception) {
                log.warn("Unable to send quote approval event for order {}", event.orderId(), exception);
            }
        }
    }
}
