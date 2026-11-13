package com.ondemandmonitoring.finance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ondemandmonitoring.device.domain.Device;
import com.ondemandmonitoring.device.enums.DeviceStatus;
import com.ondemandmonitoring.finance.domain.Invoice;
import com.ondemandmonitoring.finance.domain.Quote;
import com.ondemandmonitoring.finance.enums.InvoiceStatus;
import com.ondemandmonitoring.finance.enums.QuoteStatus;
import com.ondemandmonitoring.finance.service.impl.MissionPaymentEligibilityService;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.domain.MissionDeviceAssignment;
import com.ondemandmonitoring.mission.domain.MissionStaffAssignment;
import com.ondemandmonitoring.mission.domain.ResourceTimeLock;
import com.ondemandmonitoring.mission.enums.DeviceRole;
import com.ondemandmonitoring.mission.enums.MissionDeviceCheckStatus;
import com.ondemandmonitoring.mission.enums.MissionStaffAssignmentStatus;
import com.ondemandmonitoring.mission.enums.MissionStaffRole;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.repository.MissionDeviceAssignmentRepository;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.mission.repository.MissionStaffAssignmentRepository;
import com.ondemandmonitoring.mission.repository.ResourceTimeLockRepository;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.enums.ExecutionPlanStatus;
import com.ondemandmonitoring.order.enums.LegalReviewStatus;
import com.ondemandmonitoring.order.enums.OrderStatus;
import com.ondemandmonitoring.user.domain.User;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MissionPaymentEligibilityServiceTest {
    MissionRepository missions = mock(MissionRepository.class);
    MissionStaffAssignmentRepository staff = mock(MissionStaffAssignmentRepository.class);
    MissionDeviceAssignmentRepository devices = mock(MissionDeviceAssignmentRepository.class);
    ResourceTimeLockRepository locks = mock(ResourceTimeLockRepository.class);
    MissionPaymentEligibilityService service = new MissionPaymentEligibilityService(
            missions, staff, devices, locks);
    Invoice invoice;
    Mission mission;
    List<MissionStaffAssignment> crew;
    MissionDeviceAssignment mainDevice;

    @BeforeEach void setUp() {
        var order = new Order();
        order.setId("o1");
        order.setOrderStatus(OrderStatus.APPROVED);
        order.getExecutionPlan().setStatus(ExecutionPlanStatus.FINALIZED);
        order.getFlightAreaReview().setLegalReviewStatus(LegalReviewStatus.NOT_REQUIRED);
        Quote quote = new Quote(); quote.setStatus(QuoteStatus.ACCEPTED_BY_CUSTOMER);
        invoice = new Invoice(); invoice.setId("i1"); invoice.setOrder(order); invoice.setQuote(quote);
        invoice.setTotalAmount(new BigDecimal("1000")); invoice.setDepositAmount(new BigDecimal("300"));
        invoice.setPaidAmount(new BigDecimal("300")); invoice.setRemainingAmount(new BigDecimal("700"));
        invoice.setStatus(InvoiceStatus.PARTIALLY_PAID);

        mission = new Mission(); mission.setId("m1"); mission.setOrder(order);
        mission.setStatus(MissionStatus.PLANNING);
        mission.setScheduledStartAt(Instant.parse("2026-11-10T02:00:00Z"));
        mission.setScheduledEndAt(Instant.parse("2026-11-10T04:00:00Z"));
        crew = java.util.Arrays.stream(MissionStaffRole.values()).map(role -> {
            User user = new User(); user.setId("staff-" + role.name()); user.setIsActive(true);
            return MissionStaffAssignment.builder().mission(mission).staff(user).assignedRole(role)
                    .responseStatus(MissionStaffAssignmentStatus.PENDING).isCurrent(true).build();
        }).toList();
        Device device = new Device(); device.setId("device-1"); device.setStatus(DeviceStatus.AVAILABLE);
        mainDevice = MissionDeviceAssignment.builder().mission(mission).device(device).deviceRole(DeviceRole.MAIN)
                .checkupStatus(MissionDeviceCheckStatus.PENDING).isCurrent(true).build();

        when(missions.findAllByOrderIdOrderByScheduledStartAtAscIdAsc("o1")).thenReturn(List.of(mission));
        when(staff.findByMissionId("m1")).thenReturn(crew);
        when(devices.findByMissionId("m1")).thenReturn(List.of(mainDevice));
        when(locks.findByMissionId("m1")).thenReturn(List.of(
                lock("staff-PILOT"), lock("staff-OPERATOR"), lock("staff-MAINTAINER"),
                lock("staff-INSPECTOR"), lock("device-1")));
        when(locks.findConflictingResourceIds(anySet(), eq("m1"), any(), any())).thenReturn(Set.of());
    }

    @Test void depositIsSatisfiedAtOrAboveRequirement() {
        assertThat(service.isDepositSatisfied(invoice)).isTrue();
        invoice.setPaidAmount(new BigDecimal("299"));
        assertThat(service.isDepositSatisfied(invoice)).isFalse();
    }

    @Test void missionExecutionEligibilityRequiresDepositPlanAndReadyResourcesButNotFinalPaymentAllocation() {
        assertThat(service.isMissionExecutionEligible(invoice)).isTrue();
        verify(locks).findConflictingResourceIds(anySet(), eq("m1"), eq(mission.getScheduledStartAt()),
                eq(mission.getScheduledEndAt()));
        mission.setStatus(MissionStatus.PENDING_CONFIRMATION);
        assertThat(service.isMissionExecutionEligible(invoice)).isTrue();
    }

    private ResourceTimeLock lock(String id) {
        return ResourceTimeLock.builder().resourceId(id).mission(mission)
                .startTime(mission.getScheduledStartAt()).endTime(mission.getScheduledEndAt()).build();
    }
}
