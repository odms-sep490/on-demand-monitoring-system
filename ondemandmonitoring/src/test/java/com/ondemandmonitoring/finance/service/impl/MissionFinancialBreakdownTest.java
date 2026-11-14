package com.ondemandmonitoring.finance.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ondemandmonitoring.finance.domain.Invoice;
import com.ondemandmonitoring.finance.domain.MissionPaymentMilestone;
import com.ondemandmonitoring.finance.domain.Quote;
import com.ondemandmonitoring.finance.enums.MissionPaymentMilestoneStatus;
import com.ondemandmonitoring.finance.repository.InvoiceRepository;
import com.ondemandmonitoring.finance.repository.MissionPaymentMilestoneRepository;
import com.ondemandmonitoring.finance.repository.PaymentRepository;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class MissionFinancialBreakdownTest {
    private final MissionPaymentMilestoneRepository milestones = mock(MissionPaymentMilestoneRepository.class);
    private final MissionRepository missions = mock(MissionRepository.class);
    private final MissionPaymentMilestoneService service = new MissionPaymentMilestoneService(
            milestones, mock(InvoiceRepository.class), missions, mock(PaymentRepository.class),
            mock(OrderRepository.class), mock(AuthenticatedUserResolver.class),
            mock(CommercialExpirationSettingsService.class));

    @Test void explainsAdjustmentAndDepositForEachMissionWithoutChargingTheDepositTwice() {
        Invoice invoice = setup("3400000", "1020000", "1785000", "595000", "2250000", "750000");
        var first = service.priceBreakdown(invoice, "m1");
        var second = service.priceBreakdown(invoice, "m2");
        assertThat(first.checklistAmount()).isEqualByComparingTo("2250000");
        assertThat(first.allocatedAdjustment()).isEqualByComparingTo("300000");
        assertThat(first.missionTotalAmount()).isEqualByComparingTo("2550000");
        assertThat(first.allocatedDeposit()).isEqualByComparingTo("765000");
        assertThat(first.milestoneAmount()).isEqualByComparingTo("1785000");
        assertThat(first.missionTotalAmount().add(second.missionTotalAmount())).isEqualByComparingTo(invoice.getTotalAmount());
        assertThat(first.allocatedDeposit().add(second.allocatedDeposit())).isEqualByComparingTo(invoice.getDepositAmount());
    }

    @Test void keepsWholeDongRoundingReconciledWithTheInvoice() {
        Invoice invoice = setup("1001", "300", "467", "234", "2", "1");
        var first = service.priceBreakdown(invoice, "m1");
        var second = service.priceBreakdown(invoice, "m2");
        assertThat(first.allocatedDeposit().add(second.allocatedDeposit())).isEqualByComparingTo("300");
        assertThat(first.missionTotalAmount().subtract(first.allocatedDeposit())).isEqualByComparingTo("467");
    }

    @Test void doesNotInventABreakdownForAnOlderManualAllocation() {
        Invoice invoice = setup("1000", "300", "350", "350", "2", "1");
        assertThat(service.priceBreakdown(invoice, "m1")).isNull();
    }

    private Invoice setup(String total, String deposit, String firstAmount, String secondAmount,
            String firstGross, String secondGross) {
        Order order = new Order(); order.setId("order");
        Quote quote = new Quote(); quote.setId("quote");
        Invoice invoice = new Invoice(); invoice.setId("invoice"); invoice.setOrder(order); invoice.setQuote(quote);
        invoice.setTotalAmount(new BigDecimal(total)); invoice.setDepositAmount(new BigDecimal(deposit));
        Mission first = new Mission(); first.setId("m1");
        Mission second = new Mission(); second.setId("m2");
        when(missions.findAllByOrderIdOrderByScheduledStartAtAscIdAsc("order")).thenReturn(List.of(first, second));
        when(milestones.findAllByInvoiceIdForDisplay("invoice")).thenReturn(List.of(
                milestone(first, firstAmount), milestone(second, secondAmount)));
        when(milestones.sumChecklistPricesByMission(eq("quote"), eq("order"), anyList())).thenReturn(List.of(
                price("m1", firstGross), price("m2", secondGross)));
        return invoice;
    }

    private MissionPaymentMilestone milestone(Mission mission, String amount) {
        var row = new MissionPaymentMilestone(); row.setMission(mission); row.setCurrency("VND");
        row.setAmount(new BigDecimal(amount)); row.setStatus(MissionPaymentMilestoneStatus.UNPAID);
        return row;
    }

    private MissionPaymentMilestoneRepository.MissionChecklistPriceTotal price(String id, String amount) {
        var row = mock(MissionPaymentMilestoneRepository.MissionChecklistPriceTotal.class);
        when(row.getMissionId()).thenReturn(id); when(row.getGrossAmount()).thenReturn(new BigDecimal(amount));
        return row;
    }
}
