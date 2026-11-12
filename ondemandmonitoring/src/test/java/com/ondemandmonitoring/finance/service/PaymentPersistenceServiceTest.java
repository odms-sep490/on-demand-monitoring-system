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
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PaymentPersistenceServiceTest {
    InvoiceRepository invoices = mock(InvoiceRepository.class);
    MissionPaymentMilestoneRepository milestones = mock(MissionPaymentMilestoneRepository.class);
    PaymentRepository payments = mock(PaymentRepository.class);
    AuthenticatedUserResolver users = mock(AuthenticatedUserResolver.class);
    PaymentReferenceGenerator references = mock(PaymentReferenceGenerator.class);
    com.ondemandmonitoring.finance.service.IMissionPaymentEligibilityService eligibility =
            mock(com.ondemandmonitoring.finance.service.IMissionPaymentEligibilityService.class);
    com.ondemandmonitoring.order.repository.OrderRepository orders = mock(com.ondemandmonitoring.order.repository.OrderRepository.class);
    PaymentPersistenceService service = new PaymentPersistenceService(invoices, milestones, payments, users,
            eligibility,
            references, orders, java.time.Clock.systemUTC());
    Invoice invoice; User owner;

    @BeforeEach void setUp() {
        owner = new User(); owner.setId("owner");
        Order order = new Order(); order.setId("o1"); order.setCustomer(owner); order.getFlightAreaReview().setLegalReviewStatus(com.ondemandmonitoring.order.enums.LegalReviewStatus.NOT_REQUIRED);
        invoice = new Invoice(); invoice.setId("i1"); invoice.setOrder(order); invoice.setStatus(InvoiceStatus.ISSUED);
        invoice.setTotalAmount(new BigDecimal("1000")); invoice.setDepositAmount(new BigDecimal("300"));
        invoice.setPaidAmount(BigDecimal.ZERO); invoice.setRemainingAmount(new BigDecimal("1000"));
        when(invoices.findByIdForUpdate("i1")).thenReturn(Optional.of(invoice));
        when(invoices.findOrderIdById("i1")).thenReturn(Optional.of("o1"));
        when(orders.findByIdForUpdate("o1")).thenReturn(Optional.of(order));
        when(users.getCurrentUserId()).thenReturn("owner");
        when(eligibility.isDepositSatisfied(invoice)).thenReturn(true);
        when(payments.findFirstByInvoiceIdAndPaymentTypeAndProviderAndStatusInOrderByCreatedAtDesc(
                eq("i1"), any(), eq(PaymentProvider.VNPAY), any()))
                .thenReturn(Optional.empty());
        when(references.generate()).thenReturn("1760000000000123456");
        when(payments.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test void depositAmountComesFromInvoice() {
        when(eligibility.isDepositSatisfied(invoice)).thenReturn(false);
        PreparedPayment prepared = service.prepareDeposit("i1");
        assertThat(prepared.amount()).isEqualByComparingTo("300");
        verify(payments).saveAndFlush(argThat(payment -> payment.getAmount().compareTo(new BigDecimal("300")) == 0));
        assertThat(prepared.transactionReference()).isEqualTo("1760000000000123456");
    }

    @Test void customerCannotPayAnotherCustomersInvoice() {
        when(users.getCurrentUserId()).thenReturn("intruder");
        assertThatThrownBy(() -> service.prepareDeposit("i1")).isInstanceOf(ApiException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ACCESS_DENIED);
        verify(payments, never()).saveAndFlush(any());
    }

    @Test void depositCannotBeStartedAtOrAfterCommercialDeadline() {
        invoice.setDepositExpiresAt(java.time.Instant.EPOCH);
        assertThatThrownBy(() -> service.prepareDeposit("i1"))
                .isInstanceOf(ApiException.class).hasMessageContaining("deadline has expired");
        verify(payments, never()).saveAndFlush(any());
    }

    @Test void missionPaymentUsesOnlyTheAgreedHandedOverMilestoneAmount() {
        invoice.setPaidAmount(new BigDecimal("300")); invoice.setRemainingAmount(new BigDecimal("700"));
        invoice.setStatus(InvoiceStatus.PARTIALLY_PAID);
        Mission mission = new Mission(); mission.setId("m1"); mission.setMissionCode("MS-1");
        MissionPaymentMilestone milestone = new MissionPaymentMilestone();
        milestone.setId("milestone-1"); milestone.setInvoice(invoice); milestone.setMission(mission);
        milestone.setAmount(new BigDecimal("700")); milestone.setCurrency("VND");
        milestone.setStatus(MissionPaymentMilestoneStatus.UNPAID);
        milestone.setHandedOverAt(java.time.Instant.parse("2026-11-01T10:00:00Z"));
        when(milestones.findById("milestone-1")).thenReturn(Optional.of(milestone));
        when(milestones.findByIdForUpdate("milestone-1")).thenReturn(Optional.of(milestone));
        when(invoices.findByOrderId("o1")).thenReturn(Optional.of(invoice));
        when(payments.findFirstByMissionPaymentMilestoneIdAndProviderAndStatusInOrderByCreatedAtDesc(
                eq("milestone-1"), eq(PaymentProvider.VNPAY), any())).thenReturn(Optional.empty());
        when(payments.sumActivePaymentAmountsByInvoiceId("i1")).thenReturn(BigDecimal.ZERO);

        PreparedPayment prepared = service.prepareMissionPayment("milestone-1");

        assertThat(prepared.amount()).isEqualByComparingTo("700");
        assertThat(prepared.response().type()).isEqualTo(PaymentType.MISSION_PAYMENT);
        assertThat(prepared.response().missionPaymentMilestoneId()).isEqualTo("milestone-1");
        assertThat(milestone.getStatus()).isEqualTo(MissionPaymentMilestoneStatus.PENDING);
        verify(payments).saveAndFlush(argThat(payment -> payment.getPaymentType() == PaymentType.MISSION_PAYMENT
                && payment.getMissionPaymentMilestone().getId().equals("milestone-1")
                && payment.getAmount().compareTo(new BigDecimal("700")) == 0));
    }

    @Test void customerCannotPayAnotherCustomersMissionMilestone() {
        invoice.setPaidAmount(new BigDecimal("300")); invoice.setRemainingAmount(new BigDecimal("700"));
        Mission mission = new Mission(); mission.setId("m1");
        MissionPaymentMilestone milestone = new MissionPaymentMilestone();
        milestone.setId("milestone-1"); milestone.setInvoice(invoice); milestone.setMission(mission);
        when(milestones.findById("milestone-1")).thenReturn(Optional.of(milestone));
        when(invoices.findByOrderId("o1")).thenReturn(Optional.of(invoice));
        when(users.getCurrentUserId()).thenReturn("intruder");

        assertThatThrownBy(() -> service.prepareMissionPayment("milestone-1"))
                .isInstanceOf(ApiException.class).extracting("errorCode").isEqualTo(ErrorCode.ACCESS_DENIED);
        verify(payments, never()).saveAndFlush(any());
    }

    @Test void missionPaymentIsNotAvailableBeforeHandover() {
        invoice.setPaidAmount(new BigDecimal("300")); invoice.setRemainingAmount(new BigDecimal("700"));
        Mission mission = new Mission(); mission.setId("m1");
        MissionPaymentMilestone milestone = new MissionPaymentMilestone();
        milestone.setId("milestone-1"); milestone.setInvoice(invoice); milestone.setMission(mission);
        milestone.setAmount(new BigDecimal("700")); milestone.setStatus(MissionPaymentMilestoneStatus.NOT_YET_DUE);
        when(milestones.findById("milestone-1")).thenReturn(Optional.of(milestone));
        when(milestones.findByIdForUpdate("milestone-1")).thenReturn(Optional.of(milestone));
        when(invoices.findByOrderId("o1")).thenReturn(Optional.of(invoice));

        assertThatThrownBy(() -> service.prepareMissionPayment("milestone-1"))
                .isInstanceOf(ApiException.class).extracting("errorCode").isEqualTo(ErrorCode.INVALID_REQUEST);
        verify(payments, never()).saveAndFlush(any());
    }
}
