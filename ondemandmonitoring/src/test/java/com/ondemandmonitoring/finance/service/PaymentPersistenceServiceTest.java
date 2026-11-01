package com.ondemandmonitoring.finance.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.delivery.service.IDeliveryWorkflowService;
import com.ondemandmonitoring.finance.domain.*;
import com.ondemandmonitoring.finance.enums.*;
import com.ondemandmonitoring.finance.record.PreparedPayment;
import com.ondemandmonitoring.finance.repository.*;
import com.ondemandmonitoring.finance.service.impl.MissionPaymentEligibilityService;
import com.ondemandmonitoring.finance.service.impl.PaymentPersistenceService;
import com.ondemandmonitoring.finance.util.PaymentReferenceGenerator;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PaymentPersistenceServiceTest {
    InvoiceRepository invoices = mock(InvoiceRepository.class);
    PaymentRepository payments = mock(PaymentRepository.class);
    MissionRepository missions = mock(MissionRepository.class);
    AuthenticatedUserResolver users = mock(AuthenticatedUserResolver.class);
    PaymentReferenceGenerator references = mock(PaymentReferenceGenerator.class);
    IDeliveryWorkflowService delivery = mock(IDeliveryWorkflowService.class);
    PaymentPersistenceService service = new PaymentPersistenceService(invoices, payments, missions, users,
            new MissionPaymentEligibilityService(), references, delivery);
    Invoice invoice; User owner;

    @BeforeEach void setUp() {
        owner = new User(); owner.setId("owner");
        Order order = new Order(); order.setId("o1"); order.setCustomer(owner); order.setLegalReviewStatus(com.ondemandmonitoring.order.enums.LegalReviewStatus.NOT_REQUIRED);
        invoice = new Invoice(); invoice.setId("i1"); invoice.setOrder(order); invoice.setStatus(InvoiceStatus.ISSUED);
        invoice.setTotalAmount(new BigDecimal("1000")); invoice.setDepositAmount(new BigDecimal("300"));
        invoice.setPaidAmount(BigDecimal.ZERO); invoice.setRemainingAmount(new BigDecimal("1000"));
        when(invoices.findByIdForUpdate("i1")).thenReturn(Optional.of(invoice));
        when(users.getCurrentUserId()).thenReturn("owner");
        when(payments.findFirstByInvoiceIdAndPaymentTypeAndProviderAndStatusInOrderByCreatedAtDesc(
                eq("i1"), any(), eq(PaymentProvider.VNPAY), any()))
                .thenReturn(Optional.empty());
        when(references.generate()).thenReturn("1760000000000123456");
        when(payments.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test void depositAmountComesFromInvoice() {
        PreparedPayment prepared = service.prepare("i1", PaymentType.DEPOSIT);
        assertThat(prepared.amount()).isEqualByComparingTo("300");
        verify(payments).saveAndFlush(argThat(payment -> payment.getAmount().compareTo(new BigDecimal("300")) == 0));
        assertThat(prepared.transactionReference()).isEqualTo("1760000000000123456");
    }

    @Test void customerCannotPayAnotherCustomersInvoice() {
        when(users.getCurrentUserId()).thenReturn("intruder");
        assertThatThrownBy(() -> service.prepare("i1", PaymentType.DEPOSIT)).isInstanceOf(ApiException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ACCESS_DENIED);
        verify(payments, never()).saveAndFlush(any());
    }

    @Test void finalPaymentUsesRemainingAmountAfterMissionCompletion() {
        invoice.setPaidAmount(new BigDecimal("300")); invoice.setRemainingAmount(new BigDecimal("700"));
        PreparedPayment prepared = service.prepare("i1", PaymentType.FINAL_PAYMENT);
        assertThat(prepared.amount()).isEqualByComparingTo("700");
    }

    @Test void customerCannotUseFinalPaymentForAnotherCustomersInvoice() {
        when(users.getCurrentUserId()).thenReturn("intruder");
        assertThatThrownBy(() -> service.prepare("i1", PaymentType.FINAL_PAYMENT)).isInstanceOf(ApiException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ACCESS_DENIED);
        verifyNoInteractions(delivery);
        verify(payments, never()).saveAndFlush(any());
    }

    @Test void finalPaymentIsBlockedBeforeMissionCompletion() {
        doThrow(new ApiException(ErrorCode.FINAL_PAYMENT_NOT_ALLOWED)).when(delivery).requireFinalPaymentEligible("o1");
        assertThatThrownBy(() -> service.prepare("i1", PaymentType.FINAL_PAYMENT)).isInstanceOf(ApiException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FINAL_PAYMENT_NOT_ALLOWED);
    }
}
