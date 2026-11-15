package com.ondemandmonitoring.notification.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.ondemandmonitoring.contract.domain.*;
import com.ondemandmonitoring.contract.enums.ContractSubmissionStatus;
import com.ondemandmonitoring.contract.repository.*;
import com.ondemandmonitoring.finance.domain.Invoice;
import com.ondemandmonitoring.notification.dto.NotificationType;
import com.ondemandmonitoring.order.domain.Order;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ContractNotificationServiceTest {
    @Mock OrderContractRepository contracts;
    @Mock ContractSubmissionRepository submissions;
    @InjectMocks ContractNotificationService service;

    private OrderContract contract() {
        var order = new Order(); order.setId("o1"); order.setOrderCode("ORD-1");
        var invoice = new Invoice(); invoice.setOrder(order);
        var contract = new OrderContract(); contract.setId("c1"); contract.setInvoice(invoice);
        contract.setIssuedAt(Instant.parse("2026-11-15T01:00:00Z"));
        return contract;
    }
    private ContractSubmission submission(String id, ContractSubmissionStatus status) {
        var row = new ContractSubmission(); row.setId(id); row.setContract(contract());
        row.setSubmittedAt(Instant.parse("2026-11-15T02:00:00Z"));
        row.setStatus(status); row.setReviewedAt(Instant.parse("2026-11-15T03:00:00Z"));
        return row;
    }
    @Test void customerReceivesIssuedAndEachReviewWithStableIdentities() {
        when(contracts.findByInvoice_Order_Customer_IdOrderByIssuedAtDesc(eq("customer"), any()))
                .thenReturn(List.of(contract()));
        when(submissions.findByContract_Invoice_Order_Customer_IdAndReviewedAtIsNotNullOrderByReviewedAtDesc(eq("customer"), any()))
                .thenReturn(List.of(submission("s2", ContractSubmissionStatus.CONFIRMED),
                        submission("s1", ContractSubmissionStatus.CHANGES_REQUESTED)));
        var items = service.customerItems("customer", 20);
        assertEquals(List.of(NotificationType.ORDER_CONTRACT_ISSUED, NotificationType.ORDER_CONTRACT_CONFIRMED,
                NotificationType.ORDER_CONTRACT_CHANGES_REQUESTED), items.stream().map(i -> i.type()).toList());
        assertEquals(3, items.stream().map(i -> i.id()).distinct().count());
        assertTrue(items.stream().allMatch(i -> i.entityId().equals("o1")));
        assertEquals(items, service.customerItems("customer", 20));
    }
    @Test void managerGetsSeparateEventsForResubmissionsEvenAfterReview() {
        when(submissions.findAllByOrderBySubmittedAtDesc(any())).thenReturn(List.of(
                submission("s1", ContractSubmissionStatus.CHANGES_REQUESTED),
                submission("s2", ContractSubmissionStatus.CONFIRMED)));
        var items = service.managerItems(20);
        assertEquals(2, items.size());
        assertNotEquals(items.get(0).id(), items.get(1).id());
        assertTrue(items.stream().allMatch(i -> i.type() == NotificationType.ORDER_CONTRACT_SUBMITTED));
    }
}
