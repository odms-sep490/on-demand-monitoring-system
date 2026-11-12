package com.ondemandmonitoring.finance.service.impl;

import com.ondemandmonitoring.device.enums.DeviceStatus;
import com.ondemandmonitoring.finance.domain.Invoice;
import com.ondemandmonitoring.finance.domain.MissionPaymentMilestone;
import com.ondemandmonitoring.finance.enums.DepositStatus;
import com.ondemandmonitoring.finance.enums.InvoiceStatus;
import com.ondemandmonitoring.finance.enums.MissionPaymentMilestoneStatus;
import com.ondemandmonitoring.finance.enums.QuoteStatus;
import com.ondemandmonitoring.finance.repository.MissionPaymentMilestoneRepository;
import com.ondemandmonitoring.finance.service.IMissionPaymentEligibilityService;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.domain.MissionDeviceAssignment;
import com.ondemandmonitoring.mission.domain.MissionStaffAssignment;
import com.ondemandmonitoring.mission.domain.ResourceTimeLock;
import com.ondemandmonitoring.mission.enums.DeviceRole;
import com.ondemandmonitoring.mission.enums.MissionStaffAssignmentStatus;
import com.ondemandmonitoring.mission.enums.MissionStaffRole;
import com.ondemandmonitoring.mission.enums.MissionPurpose;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.repository.MissionDeviceAssignmentRepository;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.mission.repository.MissionStaffAssignmentRepository;
import com.ondemandmonitoring.mission.repository.ResourceTimeLockRepository;
import com.ondemandmonitoring.mission.service.impl.ExecutionPlanCoverageService;
import com.ondemandmonitoring.order.enums.ExecutionPlanStatus;
import com.ondemandmonitoring.order.enums.OrderStatus;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MissionPaymentEligibilityService implements IMissionPaymentEligibilityService {
    private static final Set<MissionStaffRole> REQUIRED_ROLES = EnumSet.of(
            MissionStaffRole.PILOT, MissionStaffRole.OPERATOR,
            MissionStaffRole.MAINTAINER, MissionStaffRole.INSPECTOR);

    private final MissionPaymentMilestoneRepository milestones;
    private final MissionRepository missions;
    private final MissionStaffAssignmentRepository staffAssignments;
    private final MissionDeviceAssignmentRepository deviceAssignments;
    private final ResourceTimeLockRepository resourceLocks;

    @Override
    public boolean isDepositSatisfied(Invoice invoice) {
        return invoice != null && invoice.effectiveDepositStatus() == DepositStatus.DEPOSIT_PAID;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isMissionExecutionEligible(Invoice invoice) {
        if (invoice == null || invoice.getOrder() == null || invoice.getQuote() == null
                || invoice.getOrder().getExecutionPlan() == null) return false;
        var order = invoice.getOrder();
        if (order.getOrderStatus() != OrderStatus.APPROVED && order.getOrderStatus() != OrderStatus.IN_PROGRESS) return false;
        if (order.getExecutionPlan().getStatus() != ExecutionPlanStatus.FINALIZED) return false;
        if (invoice.getQuote().getStatus() != QuoteStatus.ACCEPTED_BY_CUSTOMER
                || invoice.getStatus() == InvoiceStatus.CANCELLED
                || invoice.getStatus() == InvoiceStatus.REFUNDED
                || invoice.getStatus() == InvoiceStatus.OVERDUE
                || !isDepositSatisfied(invoice)) return false;

        List<Mission> required = missions.findAllByOrderIdOrderByScheduledStartAtAscIdAsc(order.getId()).stream()
                .filter(ExecutionPlanCoverageService::effective)
                .filter(mission -> mission.getPurpose() != MissionPurpose.REWORK)
                .toList();
        if (required.isEmpty()) return false;
        List<MissionPaymentMilestone> allocated =
                milestones.findAllByInvoiceIdForDisplay(invoice.getId());
        Set<String> requiredMissionIds = required.stream().map(Mission::getId).collect(Collectors.toSet());
        Set<String> allocatedMissionIds = allocated.stream()
                .map(milestone -> milestone.getMission().getId()).collect(Collectors.toSet());
        if (invoice.getTotalAmount() == null || invoice.getDepositAmount() == null) return false;
        BigDecimal target = invoice.getTotalAmount().subtract(invoice.getDepositAmount());
        if (target.signum() < 0 || allocated.size() != required.size()
                || !allocatedMissionIds.equals(requiredMissionIds)
                || milestones.sumAmountByInvoiceIdAndStatusIn(invoice.getId(),
                        List.of(MissionPaymentMilestoneStatus.NOT_YET_DUE, MissionPaymentMilestoneStatus.UNPAID,
                                MissionPaymentMilestoneStatus.PENDING, MissionPaymentMilestoneStatus.PAID,
                                MissionPaymentMilestoneStatus.EXPIRED))
                        .compareTo(target) != 0) return false;
        Set<MissionStatus> alreadyActivated = EnumSet.of(MissionStatus.PENDING_CONFIRMATION, MissionStatus.READY,
                MissionStatus.IN_PROGRESS, MissionStatus.AWAITING_REVIEW, MissionStatus.PENDING_REVIEW,
                MissionStatus.COMPLETED, MissionStatus.FAILED);
        return required.stream().allMatch(mission ->
                mission.getStatus() == MissionStatus.PENDING_PAYMENT
                        ? isResourceAssignmentReady(mission)
                        : alreadyActivated.contains(mission.getStatus()));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isResourceAssignmentReady(Mission mission) {
        if (mission == null || mission.getId() == null || mission.getCancellationAt() != null
                || mission.getScheduledStartAt() == null
                || mission.getScheduledEndAt() == null
                || !mission.getScheduledStartAt().isBefore(mission.getScheduledEndAt())) return false;

        List<MissionStaffAssignment> crew = staffAssignments.findByMissionId(mission.getId());
        List<MissionDeviceAssignment> equipment = deviceAssignments.findByMissionId(mission.getId());

        List<MissionStaffAssignment> currentCrew = crew.stream()
                .filter(a -> a.getReleasedAt() == null && Boolean.TRUE.equals(a.getIsCurrent())).toList();
        List<MissionDeviceAssignment> currentDevices = equipment.stream()
                .filter(a -> a.getReleasedAt() == null && Boolean.TRUE.equals(a.getIsCurrent())).toList();
        if (currentCrew.size() != crew.stream().filter(a -> a.getReleasedAt() == null).count()
                || currentDevices.size() != equipment.stream().filter(a -> a.getReleasedAt() == null).count()) return false;

        Set<MissionStaffRole> roles = currentCrew.stream()
                .filter(a -> a.getStaff() != null && Boolean.TRUE.equals(a.getStaff().getIsActive())
                        && a.getResponseStatus() != MissionStaffAssignmentStatus.REJECTED)
                .map(MissionStaffAssignment::getAssignedRole).collect(Collectors.toSet());
        if (!roles.containsAll(REQUIRED_ROLES)) return false;
        boolean hasMain = currentDevices.stream().anyMatch(a -> a.getDevice() != null
                && a.getDeviceRole() == DeviceRole.MAIN && a.getDevice().getStatus() != DeviceStatus.MAINTENANCE);
        if (!hasMain) return false;

        Set<String> expectedIds = currentCrew.stream().filter(a -> a.getStaff() != null)
                .map(a -> a.getStaff().getId()).collect(Collectors.toSet());
        expectedIds.addAll(currentDevices.stream().filter(a -> a.getDevice() != null)
                .map(a -> a.getDevice().getId()).collect(Collectors.toSet()));
        if (expectedIds.isEmpty()) return false;

        List<ResourceTimeLock> activeLocks = resourceLocks.findByMissionId(mission.getId()).stream()
                .filter(lock -> lock.getReleasedAt() == null).toList();
        Set<String> activeIds = activeLocks.stream().map(ResourceTimeLock::getResourceId).collect(Collectors.toSet());
        return activeIds.equals(expectedIds)
                && resourceLocks.findConflictingResourceIds(expectedIds, mission.getId(),
                        mission.getScheduledStartAt(), mission.getScheduledEndAt()).isEmpty();
    }

}
