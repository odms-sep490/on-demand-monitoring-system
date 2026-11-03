package com.ondemandmonitoring.finance.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.device.enums.DeviceStatus;
import com.ondemandmonitoring.finance.domain.Invoice;
import com.ondemandmonitoring.finance.enums.DepositStatus;
import com.ondemandmonitoring.finance.enums.InvoiceStatus;
import com.ondemandmonitoring.finance.enums.PaymentProvider;
import com.ondemandmonitoring.finance.enums.PaymentStatus;
import com.ondemandmonitoring.finance.enums.PaymentType;
import com.ondemandmonitoring.finance.enums.QuoteStatus;
import com.ondemandmonitoring.finance.repository.InvoiceRepository;
import com.ondemandmonitoring.finance.repository.PaymentRepository;
import com.ondemandmonitoring.finance.service.IMissionPaymentEligibilityService;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.domain.MissionDeviceAssignment;
import com.ondemandmonitoring.mission.domain.MissionStaffAssignment;
import com.ondemandmonitoring.mission.domain.ResourceTimeLock;
import com.ondemandmonitoring.mission.enums.DeviceRole;
import com.ondemandmonitoring.mission.enums.MissionStaffAssignmentStatus;
import com.ondemandmonitoring.mission.enums.MissionStaffRole;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.repository.MissionDeviceAssignmentRepository;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.mission.repository.MissionStaffAssignmentRepository;
import com.ondemandmonitoring.mission.repository.ResourceTimeLockRepository;
import com.ondemandmonitoring.mission.service.impl.ExecutionPlanCoverageService;
import com.ondemandmonitoring.order.enums.LegalReviewStatus;
import com.ondemandmonitoring.order.enums.ExecutionPlanStatus;
import com.ondemandmonitoring.order.enums.OrderStatus;
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
    private static final List<PaymentStatus> ACTIVE_PAYMENT_STATUSES = List.of(
            PaymentStatus.PENDING, PaymentStatus.PROCESSING);
    private static final Set<MissionStaffRole> REQUIRED_ROLES = EnumSet.of(
            MissionStaffRole.PILOT, MissionStaffRole.OPERATOR,
            MissionStaffRole.MAINTAINER, MissionStaffRole.INSPECTOR);

    private final InvoiceRepository invoices;
    private final PaymentRepository payments;
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
    public boolean isFinalPaymentEligible(Invoice invoice) {
        if (invoice == null || invoice.getOrder() == null || invoice.getQuote() == null) return false;
        var order = invoice.getOrder();
        if (order.getOrderStatus() != OrderStatus.APPROVED && order.getOrderStatus() != OrderStatus.IN_PROGRESS) return false;
        if (order.getExecutionPlan().getStatus() != ExecutionPlanStatus.FINALIZED || !isLegalReady(order.getFlightAreaReview().getLegalReviewStatus())) return false;
        if (invoice.getQuote().getStatus() != QuoteStatus.ACCEPTED_BY_CUSTOMER
                || invoice.getStatus() == InvoiceStatus.PAID
                || invoice.getStatus() == InvoiceStatus.CANCELLED
                || invoice.getStatus() == InvoiceStatus.REFUNDED
                || invoice.getStatus() == InvoiceStatus.OVERDUE
                || invoice.getRemainingAmount() == null
                || invoice.getRemainingAmount().signum() <= 0
                || !isDepositSatisfied(invoice)) return false;

        List<Mission> required = missions.findAllByOrderIdOrderByScheduledStartAtAscIdAsc(order.getId()).stream()
                .filter(ExecutionPlanCoverageService::effective).toList();
        return !required.isEmpty() && required.stream().allMatch(mission ->
                mission.getStatus() == MissionStatus.PENDING_PAYMENT && isResourceAssignmentReady(mission));
    }

    @Override
    public void requireFinalPaymentEligible(Invoice invoice) {
        if (!isFinalPaymentEligible(invoice)) throw new ApiException(ErrorCode.FINAL_PAYMENT_NOT_ALLOWED);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isFinalPaymentConfirmed(Invoice invoice) {
        if (invoice == null || invoice.getOrder() == null || invoice.getStatus() != InvoiceStatus.PAID
                || invoice.getRemainingAmount() == null || invoice.getRemainingAmount().signum() != 0
                || !isDepositSatisfied(invoice) || invoice.getQuote() == null
                || invoice.getQuote().getStatus() != QuoteStatus.ACCEPTED_BY_CUSTOMER
                || (invoice.getOrder().getOrderStatus() != OrderStatus.APPROVED
                    && invoice.getOrder().getOrderStatus() != OrderStatus.IN_PROGRESS)
                || invoice.getOrder().getExecutionPlan().getStatus() != ExecutionPlanStatus.FINALIZED
                || !isLegalReady(invoice.getOrder().getFlightAreaReview().getLegalReviewStatus())) return false;
        return payments.existsByInvoiceIdAndPaymentTypeAndStatus(
                invoice.getId(), PaymentType.FINAL_PAYMENT, PaymentStatus.SUCCESS);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isOriginalFinalPaymentConfirmed(Invoice invoice) {
        if (invoice == null || invoice.getOrder() == null || invoice.getStatus() != InvoiceStatus.PAID
                || invoice.getRemainingAmount() == null || invoice.getRemainingAmount().signum() != 0
                || !isDepositSatisfied(invoice) || invoice.getQuote() == null
                || invoice.getQuote().getStatus() != QuoteStatus.ACCEPTED_BY_CUSTOMER
                || invoice.getOrder().getExecutionPlan().getStatus() != ExecutionPlanStatus.FINALIZED
                || !isLegalReady(invoice.getOrder().getFlightAreaReview().getLegalReviewStatus())) return false;
        return payments.existsByInvoiceIdAndPaymentTypeAndStatus(
                invoice.getId(), PaymentType.FINAL_PAYMENT, PaymentStatus.SUCCESS);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasActiveFinalPayment(String orderId) {
        return invoices.findByOrderId(orderId).map(invoice -> payments
                .findFirstByInvoiceIdAndPaymentTypeAndProviderAndStatusInOrderByCreatedAtDesc(
                        invoice.getId(), PaymentType.FINAL_PAYMENT, PaymentProvider.VNPAY, ACTIVE_PAYMENT_STATUSES)
                .isPresent()).orElse(false);
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

    private static boolean isLegalReady(LegalReviewStatus status) {
        return status == LegalReviewStatus.NOT_REQUIRED || status == LegalReviewStatus.APPROVED
                || status == LegalReviewStatus.PENDING_PERMIT;
    }
}
