package com.ondemandmonitoring.finance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.finance.domain.Invoice;
import com.ondemandmonitoring.finance.domain.MissionPaymentMilestone;
import com.ondemandmonitoring.finance.dto.ConfigureMissionPaymentAllocationsRequest;
import com.ondemandmonitoring.finance.dto.MissionPaymentAllocationItem;
import com.ondemandmonitoring.finance.enums.InvoiceStatus;
import com.ondemandmonitoring.finance.enums.MissionPaymentMilestoneStatus;
import com.ondemandmonitoring.finance.repository.InvoiceRepository;
import com.ondemandmonitoring.finance.repository.MissionPaymentMilestoneRepository;
import com.ondemandmonitoring.finance.repository.PaymentRepository;
import com.ondemandmonitoring.finance.service.impl.MissionPaymentMilestoneService;
import com.ondemandmonitoring.finance.service.impl.CommercialExpirationSettingsService;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.enums.OrderStatus;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MissionPaymentMilestoneServiceTest {
    MissionPaymentMilestoneRepository milestones = mock(MissionPaymentMilestoneRepository.class);
    InvoiceRepository invoices = mock(InvoiceRepository.class);
    MissionRepository missions = mock(MissionRepository.class);
    PaymentRepository payments = mock(PaymentRepository.class);
    OrderRepository orders = mock(OrderRepository.class);
    AuthenticatedUserResolver users = mock(AuthenticatedUserResolver.class);
    CommercialExpirationSettingsService expirationSettings = mock(CommercialExpirationSettingsService.class);
    MissionPaymentMilestoneService service = new MissionPaymentMilestoneService(
            milestones, invoices, missions, payments, orders, users, expirationSettings);
    Order order;
    Invoice invoice;
    Mission first;
    Mission second;
    User manager;

    @BeforeEach void setUp() {
        order = new Order(); order.setId("order-1"); order.setOrderStatus(OrderStatus.APPROVED);
        invoice = new Invoice(); invoice.setId("invoice-1"); invoice.setOrder(order);
        invoice.setStatus(InvoiceStatus.PARTIALLY_PAID);
        invoice.setTotalAmount(new BigDecimal("1000")); invoice.setDepositAmount(new BigDecimal("300"));
        invoice.setPaidAmount(new BigDecimal("300")); invoice.setRemainingAmount(new BigDecimal("700"));
        first = mission("mission-1", "MS-1");
        second = mission("mission-2", "MS-2");
        manager = new User(); manager.setId("manager-1");

        when(orders.findByIdForUpdate("order-1")).thenReturn(Optional.of(order));
        when(invoices.findByOrderId("order-1")).thenReturn(Optional.of(invoice));
        when(invoices.findByIdForUpdate("invoice-1")).thenReturn(Optional.of(invoice));
        when(missions.findAllByOrderIdOrderByScheduledStartAtAscIdAsc("order-1"))
                .thenReturn(List.of(first, second));
        when(expirationSettings.current()).thenReturn(
                new com.ondemandmonitoring.finance.dto.CommercialExpirationSettingsResponse(
                        48, 24, 24, "admin", Instant.EPOCH));
        when(milestones.findAllByInvoiceIdForDisplay("invoice-1")).thenReturn(List.of());
        when(users.getCurrentUser()).thenReturn(manager);
    }

    @Test void savesExactlyOneAgreedMilestoneForEachMissionWhenAmountsMatchInvoiceBalance() {
        var response = service.configure("order-1", new ConfigureMissionPaymentAllocationsRequest(List.of(
                new MissionPaymentAllocationItem("mission-1", new BigDecimal("250")),
                new MissionPaymentAllocationItem("mission-2", new BigDecimal("450")))));

        assertThat(response.allocationComplete()).isTrue();
        assertThat(response.allocationTargetAmount()).isEqualByComparingTo("700");
        assertThat(response.allocatedAmount()).isEqualByComparingTo("700");
        assertThat(response.milestones()).hasSize(2);
        assertThat(response.milestones()).extracting("status")
                .containsOnly(MissionPaymentMilestoneStatus.NOT_YET_DUE);
        verify(milestones).saveAllAndFlush(anyList());
    }

    @Test void rejectsAllocationThatDoesNotEqualThePostDepositBalance() {
        assertThatThrownBy(() -> service.configure("order-1", new ConfigureMissionPaymentAllocationsRequest(List.of(
                new MissionPaymentAllocationItem("mission-1", new BigDecimal("250")),
                new MissionPaymentAllocationItem("mission-2", new BigDecimal("449"))))))
                .isInstanceOf(ApiException.class).extracting("errorCode").isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test void handoverMakesMilestonePayableAndDuplicateHandoverIsIdempotent() {
        var milestone = milestone(first, MissionPaymentMilestoneStatus.NOT_YET_DUE);
        when(milestones.findByMissionIdForUpdate("mission-1")).thenReturn(Optional.of(milestone));
        Instant handedOverAt = Instant.parse("2026-11-12T10:00:00Z");

        service.markHandedOver("order-1", "mission-1", handedOverAt);
        service.markHandedOver("order-1", "mission-1", handedOverAt.plusSeconds(10));

        assertThat(milestone.getStatus()).isEqualTo(MissionPaymentMilestoneStatus.UNPAID);
        assertThat(milestone.getHandedOverAt()).isEqualTo(handedOverAt);
    }

    private Mission mission(String id, String code) {
        Mission mission = new Mission();
        mission.setId(id); mission.setMissionCode(code); mission.setOrder(order);
        mission.setStatus(MissionStatus.PENDING_PAYMENT); mission.setScopeLabel(code);
        return mission;
    }

    private MissionPaymentMilestone milestone(Mission mission, MissionPaymentMilestoneStatus status) {
        MissionPaymentMilestone milestone = new MissionPaymentMilestone();
        milestone.setId("milestone-1"); milestone.setInvoice(invoice); milestone.setMission(mission);
        milestone.setAmount(new BigDecimal("700")); milestone.setCurrency("VND"); milestone.setStatus(status);
        return milestone;
    }
}
