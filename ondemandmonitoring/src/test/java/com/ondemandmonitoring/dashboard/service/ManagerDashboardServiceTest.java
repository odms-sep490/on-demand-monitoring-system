package com.ondemandmonitoring.dashboard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ondemandmonitoring.finance.enums.InvoiceStatus;
import com.ondemandmonitoring.dashboard.repository.ManagerDashboardAnalyticsRepository;
import com.ondemandmonitoring.finance.enums.PaymentStatus;
import com.ondemandmonitoring.finance.enums.PaymentType;
import com.ondemandmonitoring.finance.repository.InvoiceRepository;
import com.ondemandmonitoring.finance.repository.PaymentRepository;
import com.ondemandmonitoring.finance.repository.QuoteRepository;
import com.ondemandmonitoring.mission.repository.MissionDeviceAssignmentRepository;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.mission.repository.MissionStaffAssignmentRepository;
import com.ondemandmonitoring.order.enums.ChecklistPricingStatus;
import com.ondemandmonitoring.order.enums.OrderChecklistSourceType;
import com.ondemandmonitoring.order.enums.OrderStatus;
import com.ondemandmonitoring.order.repository.OrderChecklistItemRepository;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.pricing.repository.ChecklistPricingAuditRepository;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

class ManagerDashboardServiceTest {
    OrderRepository orders = mock(OrderRepository.class);
    OrderChecklistItemRepository checklistItems = mock(OrderChecklistItemRepository.class);
    QuoteRepository quotes = mock(QuoteRepository.class);
    InvoiceRepository invoices = mock(InvoiceRepository.class);
    PaymentRepository payments = mock(PaymentRepository.class);
    MissionRepository missions = mock(MissionRepository.class);
    MissionStaffAssignmentRepository staff = mock(MissionStaffAssignmentRepository.class);
    MissionDeviceAssignmentRepository devices = mock(MissionDeviceAssignmentRepository.class);
    ChecklistPricingAuditRepository audits = mock(ChecklistPricingAuditRepository.class);
    ManagerDashboardAnalyticsRepository analytics = mock(ManagerDashboardAnalyticsRepository.class);
    AuthenticatedUserResolver currentUser = mock(AuthenticatedUserResolver.class);
    ManagerDashboardService service;

    @BeforeEach
    void setUp() {
        service = new ManagerDashboardService(orders, checklistItems, quotes, invoices, payments,
                missions, staff, devices, audits, analytics, currentUser);
        when(analytics.totalCurrentQuoteValue(any(), any())).thenReturn(java.math.BigDecimal.ZERO);
        when(analytics.orderStatuses(any(), any())).thenReturn(List.of());
        when(analytics.services(any(), any())).thenReturn(List.of());
        when(analytics.additionalServices(any(), any())).thenReturn(List.of());
        when(analytics.priceAdjustments(any(), any()))
                .thenReturn(new com.ondemandmonitoring.dashboard.dto.ManagerDashboardResponse.PriceAdjustmentSummary(0, 0));
        when(analytics.missionStatuses()).thenReturn(List.of());
        when(analytics.missionTrend(any(), any(), any())).thenReturn(List.of());
        when(currentUser.getCurrentUser()).thenReturn(new User());
        when(orders.findAllByOrderByCreatedAtDesc(any(Pageable.class))).thenReturn(Page.empty());
        when(orders.findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(
                any(), any(), any(Pageable.class))).thenReturn(Page.empty());
        when(orders.findByOrderStatusOrderByCreatedAtAsc(eq(OrderStatus.PENDING), any(Pageable.class)))
                .thenReturn(Page.empty());
        when(missions.findByStatusInOrderByScheduledStartAtAsc(anyCollection(), any(Pageable.class)))
                .thenReturn(Page.empty());
        when(missions.findByStatusInOrderByUpdatedAtDesc(anyCollection(), any(Pageable.class)))
                .thenReturn(Page.empty());
        when(missions.findMissingResources(anyCollection(), any(Pageable.class))).thenReturn(List.of());
        when(invoices.findDepositPaymentIssues(any(Pageable.class))).thenReturn(List.of());
        when(orders.findQuoteReadyOrders(anyCollection(), anyCollection(), anyCollection(), any(Pageable.class)))
                .thenReturn(Page.empty());
        when(checklistItems.findByPricingStatusOrderByCreatedAtAsc(any(), any(Pageable.class))).thenReturn(List.of());
        when(checklistItems.findByPricingStatusAndSourceTypeInOrderByCreatedAtAsc(any(), anyCollection(), any(Pageable.class)))
                .thenReturn(List.of());
        when(quotes.findByStatusInOrderByUpdatedAtDesc(anyCollection(), any(Pageable.class))).thenReturn(List.of());
        when(payments.findByStatusOrderByPaidAtDesc(any(), any(Pageable.class))).thenReturn(List.of());
        when(audits.findAllByOrderByReviewedAtDesc(any(Pageable.class))).thenReturn(List.of());
    }

    @Test
    void summaryUsesDatabaseCountsWithUnambiguousUnits() {
        when(orders.countByOrderStatus(OrderStatus.PENDING)).thenReturn(12L);
        when(checklistItems.countByPricingStatusAndSourceTypeIn(
                eq(ChecklistPricingStatus.NEEDS_MANAGER_REVIEW), anyCollection())).thenReturn(5L);
        when(quotes.countByStatus(com.ondemandmonitoring.finance.enums.QuoteStatus.APPROVED)).thenReturn(7L);
        when(invoices.countWaitingDeposit(anyCollection())).thenReturn(4L);
        when(missions.countByStatusIn(anyCollection())).thenReturn(8L, 2L);
        when(checklistItems.countByPricingStatus(ChecklistPricingStatus.BLOCKED_BY_POLICY)).thenReturn(1L);
        when(invoices.countDepositPaymentIssues()).thenReturn(1L);
        when(missions.countMissingResources(anyCollection())).thenReturn(2L);

        var dashboard = service.getDashboard();

        assertThat(dashboard.summary().pendingOrderCount()).isEqualTo(12);
        assertThat(dashboard.summary().pendingAdditionalChecklistCount()).isEqualTo(5);
        assertThat(dashboard.summary().waitingDepositCount()).isEqualTo(4);
        assertThat(dashboard.summary().activeMissionCount()).isEqualTo(8);
        assertThat(dashboard.summary().attentionRequiredCount()).isEqualTo(6);
        verify(checklistItems).countByPricingStatusAndSourceTypeIn(
                eq(ChecklistPricingStatus.NEEDS_MANAGER_REVIEW),
                org.mockito.ArgumentMatchers.argThat(sourceTypes ->
                        sourceTypes.size() == 2
                                && sourceTypes.contains(OrderChecklistSourceType.CUSTOMER_SELECTED)
                                && sourceTypes.contains(OrderChecklistSourceType.CUSTOMER_CUSTOM)));
        verify(invoices).countWaitingDeposit(eq(List.of(InvoiceStatus.ISSUED, InvoiceStatus.PARTIALLY_PAID)));
        verify(payments).countByPaymentTypeAndStatus(PaymentType.DEPOSIT, PaymentStatus.SUCCESS);
    }

    @Test
    void serviceResolvesAuthenticatedManagerAndLimitsPreviewQueries() {
        service.getDashboard();
        verify(currentUser).getCurrentUser();
        verify(orders).findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(any(), any(),
                org.mockito.ArgumentMatchers.argThat(page -> page.getPageSize() == 5));
    }

    @Test
    void sevenDayRangeUsesDailyAggregationWithoutFilteringCurrentWorkload() {
        when(checklistItems.countByPricingStatusAndSourceTypeIn(
                eq(ChecklistPricingStatus.NEEDS_MANAGER_REVIEW), anyCollection())).thenReturn(9L);

        var dashboard = service.getDashboard("7D", null, null);

        assertThat(dashboard.summary().pendingAdditionalChecklistCount()).isEqualTo(9);
        verify(analytics).missionTrend(any(), any(), eq("day"));
    }

    @Test
    void ninetyDayRangeUsesWeeklyAggregation() {
        service.getDashboard("90D", null, null);
        verify(analytics).missionTrend(any(), any(), eq("week"));
    }
}
