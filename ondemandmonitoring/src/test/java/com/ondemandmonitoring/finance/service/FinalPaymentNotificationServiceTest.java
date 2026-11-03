package com.ondemandmonitoring.finance.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ondemandmonitoring.finance.domain.Invoice;
import com.ondemandmonitoring.finance.event.FinalPaymentRequiredEvent;
import com.ondemandmonitoring.finance.repository.InvoiceRepository;
import com.ondemandmonitoring.finance.service.impl.FinalPaymentDeadlineService;
import com.ondemandmonitoring.finance.service.impl.FinalPaymentNotificationService;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.user.domain.User;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

class FinalPaymentNotificationServiceTest {
    private final InvoiceRepository invoices = mock(InvoiceRepository.class);
    private final FinalPaymentDeadlineService deadlines = mock(FinalPaymentDeadlineService.class);
    private final IMissionPaymentEligibilityService eligibility = mock(IMissionPaymentEligibilityService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final FinalPaymentNotificationService service = new FinalPaymentNotificationService(
            invoices, deadlines, eligibility, events);

    @Test
    void startsDeadlineAndPublishesCustomerEventWhenBalanceBecomesPayable() {
        User customer = new User();
        customer.setId("customer-1");
        Order order = new Order();
        order.setId("order-1");
        order.setCustomer(customer);
        Invoice invoice = new Invoice();
        invoice.setId("invoice-1");
        invoice.setOrder(order);

        when(invoices.findByOrderId("order-1")).thenReturn(Optional.of(invoice));
        when(deadlines.assignIfEligible(invoice)).thenReturn(Instant.parse("2026-11-03T10:00:00Z"));
        when(eligibility.isFinalPaymentEligible(invoice)).thenReturn(true);

        service.notifyIfEligible("order-1");

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent((Object) event.capture());
        FinalPaymentRequiredEvent notification = (FinalPaymentRequiredEvent) event.getValue();
        assertEquals("customer-1", notification.customerId());
        assertEquals("order-1", notification.orderId());
        assertEquals("invoice-1", notification.invoiceId());
    }

    @Test
    void doesNotNotifyWhenBalanceIsNotEligible() {
        Order order = new Order();
        order.setId("order-1");
        order.setCustomer(new User());
        Invoice invoice = new Invoice();
        invoice.setId("invoice-1");
        invoice.setOrder(order);
        when(invoices.findByOrderId("order-1")).thenReturn(Optional.of(invoice));
        when(deadlines.assignIfEligible(invoice)).thenReturn(null);

        service.notifyIfEligible("order-1");

        verify(eligibility, never()).isFinalPaymentEligible(invoice);
        verifyNoInteractions(events);
    }
}
