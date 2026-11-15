package com.ondemandmonitoring.notification.service;

import com.ondemandmonitoring.contract.repository.ContractSubmissionRepository;
import com.ondemandmonitoring.contract.repository.OrderContractRepository;
import com.ondemandmonitoring.contract.enums.ContractSubmissionStatus;
import com.ondemandmonitoring.notification.dto.NotificationItemResponse;
import com.ondemandmonitoring.notification.dto.NotificationType;
import com.ondemandmonitoring.order.domain.Order;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/** Durable notifications reconstructed from immutable contract and submission milestones. */
@Service
@RequiredArgsConstructor
public class ContractNotificationService {
    private final OrderContractRepository contracts;
    private final ContractSubmissionRepository submissions;

    public List<NotificationItemResponse> customerItems(String customerId, int limit) {
        var items = new ArrayList<NotificationItemResponse>();
        var page = PageRequest.of(0, limit);
        contracts.findByInvoice_Order_Customer_IdOrderByIssuedAtDesc(customerId, page)
                .forEach(c -> items.add(item(c.getId(), NotificationType.ORDER_CONTRACT_ISSUED,
                        c.getInvoice().getOrder(), c.getIssuedAt())));
        submissions.findByContract_Invoice_Order_Customer_IdAndReviewedAtIsNotNullOrderByReviewedAtDesc(customerId, page)
                .forEach(s -> {
                    var type = s.getStatus() == ContractSubmissionStatus.CONFIRMED
                            ? NotificationType.ORDER_CONTRACT_CONFIRMED
                            : NotificationType.ORDER_CONTRACT_CHANGES_REQUESTED;
                    items.add(item(s.getId(), type, s.getContract().getInvoice().getOrder(), s.getReviewedAt()));
                });
        return items;
    }

    public List<NotificationItemResponse> managerItems(int limit) {
        return submissions.findAllByOrderBySubmittedAtDesc(PageRequest.of(0, limit)).stream()
                .map(s -> item(s.getId(), NotificationType.ORDER_CONTRACT_SUBMITTED,
                        s.getContract().getInvoice().getOrder(), s.getSubmittedAt())).toList();
    }

    private NotificationItemResponse item(String milestoneId, NotificationType type, Order order, Instant at) {
        String reference = order.getOrderCode() == null || order.getOrderCode().isBlank()
                ? order.getId() : order.getOrderCode();
        return new NotificationItemResponse("contract:" + milestoneId + ":" + type.name(),
                type, order.getId(), null, reference, at);
    }
}
