package com.ondemandmonitoring.finance.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.finance.domain.Invoice;
import com.ondemandmonitoring.finance.domain.MissionPaymentMilestone;
import com.ondemandmonitoring.finance.dto.ConfigureMissionPaymentAllocationsRequest;
import com.ondemandmonitoring.finance.dto.MissionPaymentAllocationResponse;
import com.ondemandmonitoring.finance.dto.MissionPaymentMilestoneResponse;
import com.ondemandmonitoring.finance.enums.InvoiceStatus;
import com.ondemandmonitoring.finance.enums.DepositStatus;
import com.ondemandmonitoring.finance.enums.MissionPaymentMilestoneStatus;
import com.ondemandmonitoring.finance.mapper.FinanceMapper;
import com.ondemandmonitoring.finance.repository.InvoiceRepository;
import com.ondemandmonitoring.finance.repository.MissionPaymentMilestoneRepository;
import com.ondemandmonitoring.finance.repository.PaymentRepository;
import com.ondemandmonitoring.finance.service.impl.CommercialExpirationSettingsService;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.enums.MissionPurpose;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.mission.service.impl.ExecutionPlanCoverageService;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.order.enums.OrderStatus;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MissionPaymentMilestoneService {
    private static final List<MissionPaymentMilestoneStatus> OUTSTANDING_STATUSES = List.of(
            MissionPaymentMilestoneStatus.UNPAID, MissionPaymentMilestoneStatus.PENDING,
            MissionPaymentMilestoneStatus.EXPIRED);

    private final MissionPaymentMilestoneRepository milestones;
    private final InvoiceRepository invoices;
    private final MissionRepository missions;
    private final PaymentRepository payments;
    private final OrderRepository orders;
    private final AuthenticatedUserResolver currentUser;
    private final CommercialExpirationSettingsService expirationSettings;

    private record AllocationRemainder(String missionId, BigDecimal remainder, int missionOrder) {}

    @PreAuthorize("hasRole('MANAGER')")
    @Transactional
    public MissionPaymentAllocationResponse configure(String orderId, ConfigureMissionPaymentAllocationsRequest request) {
        var order = orders.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found"));
        if (order.getOrderStatus() != OrderStatus.APPROVED
                && order.getOrderStatus() != OrderStatus.IN_PROGRESS)
            throw invalid("Mission payment allocations can only be configured for an active Order");
        Invoice invoice = invoices.findByOrderId(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.INVOICE_NOT_FOUND));
        invoice = invoices.findByIdForUpdate(invoice.getId())
                .orElseThrow(() -> new ApiException(ErrorCode.INVOICE_NOT_FOUND));
        if (invoice.getStatus() == InvoiceStatus.CANCELLED || invoice.getStatus() == InvoiceStatus.REFUNDED
                || invoice.getStatus() == InvoiceStatus.OVERDUE) {
            throw invalid("Mission payment allocation is unavailable for this Invoice");
        }
        if (invoice.getTotalAmount() == null || invoice.getDepositAmount() == null
                || invoice.getDepositAmount().signum() < 0
                || invoice.getTotalAmount().compareTo(invoice.getDepositAmount()) < 0) {
            throw invalid("Invoice amounts are not valid for Mission payment allocation");
        }
        BigDecimal target = invoice.getTotalAmount().subtract(invoice.getDepositAmount());
        if (target.signum() <= 0 || invoice.getPaidAmount().compareTo(invoice.getDepositAmount()) > 0) {
            throw invalid("Mission allocations must be configured before any post-Deposit payment");
        }

        List<Mission> billableMissions = billableMissions(orderId);
        if (billableMissions.isEmpty()) throw invalid("The Order has no billable original Missions");
        Map<String, Mission> missionById = billableMissions.stream()
                .collect(Collectors.toMap(Mission::getId, mission -> mission));
        Map<String, BigDecimal> requested = new HashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (var item : request.allocations()) {
            if (item.amount().scale() > 0 && item.amount().stripTrailingZeros().scale() > 0) {
                throw invalid("Mission payment amounts must be whole VND values");
            }
            if (item.amount().signum() <= 0 || requested.putIfAbsent(item.missionId(), item.amount()) != null) {
                throw invalid("Each original Mission needs one positive payment allocation");
            }
            if (!missionById.containsKey(item.missionId())) {
                throw invalid("A Mission allocation does not belong to this Order's billable Missions");
            }
            total = total.add(item.amount());
        }
        if (!requested.keySet().equals(missionById.keySet())) {
            throw invalid("Provide one allocation for every billable original Mission");
        }
        if (total.compareTo(target) != 0) {
            throw invalid("The sum of Mission allocations must equal the Invoice balance after Deposit");
        }

        List<MissionPaymentMilestone> existing = milestones.findAllByInvoiceIdForDisplay(invoice.getId());
        Map<String, MissionPaymentMilestone> byMissionId = existing.stream()
                .collect(Collectors.toMap(item -> item.getMission().getId(), item -> item));
        for (MissionPaymentMilestone milestone : existing) {
            if (milestone.getHandedOverAt() != null
                    || milestone.getStatus() != MissionPaymentMilestoneStatus.NOT_YET_DUE
                    || payments.existsByMissionPaymentMilestoneId(milestone.getId())) {
                throw invalid("Mission allocations are locked after handover or a payment attempt");
            }
        }

        User manager = currentUser.getCurrentUser();
        Instant pricedAt = Instant.now();
        List<MissionPaymentMilestone> saved = new ArrayList<>();
        for (Mission mission : billableMissions) {
            MissionPaymentMilestone milestone = byMissionId.get(mission.getId());
            if (milestone == null) {
                milestone = new MissionPaymentMilestone();
                milestone.setMission(mission);
                milestone.setInvoice(invoice);
                milestone.setCurrency("VND");
                milestone.setStatus(MissionPaymentMilestoneStatus.NOT_YET_DUE);
            }
            if (mission.getStatus() != MissionStatus.PLANNING
                    && mission.getStatus() != MissionStatus.PENDING_PAYMENT) {
                throw invalid("Mission prices cannot be changed after execution begins");
            }
            milestone.setAmount(requested.get(mission.getId()).setScale(0, RoundingMode.UNNECESSARY));
            milestone.setPricedBy(manager);
            milestone.setPricedAt(pricedAt);
            saved.add(milestone);
        }
        milestones.saveAllAndFlush(saved);
        return response(invoice, saved, target);
    }

    /** Derives each Mission's payable balance from the accepted quote and its assigned checklist items. */
    @PreAuthorize("hasRole('MANAGER')")
    @Transactional
    public MissionPaymentAllocationResponse calculateFromChecklistPrices(String orderId) {
        var order = orders.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found"));
        if (order.getOrderStatus() != OrderStatus.APPROVED && order.getOrderStatus() != OrderStatus.IN_PROGRESS
                && order.getOrderStatus() != OrderStatus.COMPLETED) {
            throw invalid("Mission payment allocations can only be calculated for an active Order");
        }

        Invoice invoice = invoices.findByOrderId(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.INVOICE_NOT_FOUND));
        invoice = invoices.findByIdForUpdate(invoice.getId())
                .orElseThrow(() -> new ApiException(ErrorCode.INVOICE_NOT_FOUND));
        if (invoice.getStatus() == InvoiceStatus.CANCELLED || invoice.getStatus() == InvoiceStatus.REFUNDED
                || invoice.getStatus() == InvoiceStatus.OVERDUE) {
            throw invalid("Mission payment allocation is unavailable for this Invoice");
        }
        if (invoice.getTotalAmount() == null || invoice.getDepositAmount() == null
                || invoice.getDepositAmount().signum() < 0
                || invoice.getTotalAmount().compareTo(invoice.getDepositAmount()) < 0) {
            throw invalid("Invoice amounts are not valid for Mission payment allocation");
        }
        BigDecimal target = allocationTarget(invoice);
        if (target.signum() <= 0) {
            throw invalid("The Invoice has no balance remaining after the Deposit");
        }

        List<Mission> billableMissions = billableMissions(orderId);
        if (billableMissions.isEmpty()) throw invalid("The Order has no billable original Missions");
        Set<String> billableMissionIds = billableMissions.stream()
                .map(Mission::getId).collect(Collectors.toSet());

        if (milestones.countPricedChecklistItemsWithoutBillableMission(
                invoice.getQuote().getId(), orderId, billableMissionIds) > 0) {
            throw invalid("Assign every priced checklist to one original Mission before calculating payments");
        }

        Map<String, BigDecimal> grossByMissionId = new LinkedHashMap<>();
        billableMissions.forEach(mission -> grossByMissionId.put(mission.getId(), BigDecimal.ZERO));
        milestones.sumChecklistPricesByMission(invoice.getQuote().getId(), orderId, billableMissionIds)
                .forEach(row -> grossByMissionId.put(row.getMissionId(), row.getGrossAmount()));
        if (grossByMissionId.values().stream().anyMatch(amount -> amount.signum() <= 0)) {
            throw invalid("Every billable Mission needs a positive price from its assigned checklists");
        }

        Map<String, BigDecimal> calculatedAmounts = distributeRemainingBalance(target, grossByMissionId);
        List<MissionPaymentMilestone> existing = milestones.findAllByInvoiceIdForDisplay(invoice.getId());
        Map<String, MissionPaymentMilestone> existingByMissionId = existing.stream()
                .collect(Collectors.toMap(item -> item.getMission().getId(), item -> item));
        Map<String, MissionPaymentMilestone> payableExistingByMissionId = existingByMissionId.entrySet().stream()
                .filter(entry -> entry.getValue().getStatus() != MissionPaymentMilestoneStatus.CANCELLED)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        boolean paymentStarted = invoice.getPaidAmount().compareTo(invoice.getDepositAmount()) > 0
                || existing.stream().anyMatch(milestone -> milestone.getHandedOverAt() != null
                || (milestone.getStatus() != MissionPaymentMilestoneStatus.NOT_YET_DUE
                    && milestone.getStatus() != MissionPaymentMilestoneStatus.CANCELLED)
                || payments.existsByMissionPaymentMilestoneId(milestone.getId()));
        boolean alreadyMatches = payableExistingByMissionId.keySet().equals(calculatedAmounts.keySet())
                && calculatedAmounts.entrySet().stream().allMatch(entry -> {
                    MissionPaymentMilestone milestone = payableExistingByMissionId.get(entry.getKey());
                    return milestone != null && milestone.getAmount().compareTo(entry.getValue()) == 0;
                });
        if (paymentStarted) {
            if (!alreadyMatches) {
                throw invalid("Mission prices cannot be recalculated after handover or a post-Deposit payment");
            }
            return response(invoice, existing, target);
        }
        if (order.getOrderStatus() == OrderStatus.COMPLETED) {
            throw invalid("Completed Orders cannot receive new Mission payment milestones");
        }
        if (alreadyMatches) return response(invoice, existing, target);

        User manager = currentUser.getCurrentUser();
        Instant calculatedAt = Instant.now();
        List<MissionPaymentMilestone> saved = new ArrayList<>();
        for (Mission mission : billableMissions) {
            MissionPaymentMilestone milestone = existingByMissionId.get(mission.getId());
            if (milestone == null) {
                milestone = new MissionPaymentMilestone();
                milestone.setMission(mission);
                milestone.setInvoice(invoice);
                milestone.setCurrency("VND");
                milestone.setStatus(MissionPaymentMilestoneStatus.NOT_YET_DUE);
            }
            if (milestone.getStatus() == MissionPaymentMilestoneStatus.CANCELLED) {
                milestone.setStatus(MissionPaymentMilestoneStatus.NOT_YET_DUE);
                milestone.setHandedOverAt(null);
                milestone.setPaymentExpiresAt(null);
            }
            milestone.setAmount(calculatedAmounts.get(mission.getId()));
            milestone.setPricedBy(manager);
            milestone.setPricedAt(calculatedAt);
            saved.add(milestone);
        }
        milestones.saveAllAndFlush(saved);
        return response(invoice, saved, target);
    }

    private Map<String, BigDecimal> distributeRemainingBalance(
            BigDecimal target, Map<String, BigDecimal> grossByMissionId) {
        // The approved quote is the gross source. The remainder after the deposit is split
        // proportionally, which also distributes any order-level adjustment without charging
        // it twice or leaving part of the invoice unallocated.
        BigDecimal grossTotal = grossByMissionId.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (grossTotal.signum() <= 0) {
            throw invalid("The accepted quote does not contain positive checklist prices for the planned Missions");
        }

        Map<String, BigDecimal> allocated = new LinkedHashMap<>();
        List<AllocationRemainder> remainders = new ArrayList<>();
        BigDecimal allocatedTotal = BigDecimal.ZERO;
        int missionOrder = 0;
        for (var entry : grossByMissionId.entrySet()) {
            BigDecimal[] quotientAndRemainder = target.multiply(entry.getValue()).divideAndRemainder(grossTotal);
            allocated.put(entry.getKey(), quotientAndRemainder[0]);
            allocatedTotal = allocatedTotal.add(quotientAndRemainder[0]);
            remainders.add(new AllocationRemainder(entry.getKey(), quotientAndRemainder[1], missionOrder++));
        }

        int remainingVnd = target.subtract(allocatedTotal).intValueExact();
        remainders.sort(Comparator.comparing(AllocationRemainder::remainder).reversed()
                .thenComparingInt(AllocationRemainder::missionOrder));
        for (int index = 0; index < remainingVnd; index++) {
            AllocationRemainder remainder = remainders.get(index);
            allocated.computeIfPresent(remainder.missionId(), (ignored, amount) -> amount.add(BigDecimal.ONE));
        }
        if (allocated.values().stream().anyMatch(amount -> amount.signum() <= 0)) {
            throw invalid("The post-Deposit balance is too small to create a positive payment for every Mission");
        }
        return allocated;
    }

    @PreAuthorize("hasRole('MANAGER')")
    @Transactional(readOnly = true)
    public MissionPaymentAllocationResponse getForManager(String orderId) {
        Invoice invoice = invoices.findByOrderId(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.INVOICE_NOT_FOUND));
        return response(invoice, milestones.findAllByInvoiceIdForDisplay(invoice.getId()), allocationTarget(invoice));
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional(readOnly = true)
    public MissionPaymentAllocationResponse getForCustomer(String orderId) {
        var order = orders.findById(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found"));
        if (!order.getCustomer().getId().equals(currentUser.getCurrentUserId())) {
            throw new ApiException(ErrorCode.ACCESS_DENIED);
        }
        Invoice invoice = invoices.findByOrderId(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.INVOICE_NOT_FOUND));
        return response(invoice, milestones.findAllByInvoiceIdForDisplay(invoice.getId()), allocationTarget(invoice));
    }

    @Transactional
    public void markHandedOver(String orderId, String missionId, Instant handedOverAt) {
        MissionPaymentMilestone milestone = milestones.findByMissionIdForUpdate(missionId)
                .orElseThrow(() -> invalid("Configure the agreed Mission payment amount before handover"));
        if (!orderId.equals(milestone.getInvoice().getOrder().getId())) {
            throw new ApiException(ErrorCode.ACCESS_DENIED);
        }
        if (milestone.getStatus() == MissionPaymentMilestoneStatus.UNPAID
                || milestone.getStatus() == MissionPaymentMilestoneStatus.PENDING
                || milestone.getStatus() == MissionPaymentMilestoneStatus.PAID) {
            return;
        }
        if (milestone.getStatus() != MissionPaymentMilestoneStatus.NOT_YET_DUE) {
            throw invalid("This Mission payment milestone cannot be handed over");
        }
        if (milestone.getInvoice().effectiveDepositStatus()
                != DepositStatus.DEPOSIT_PAID) {
            throw invalid("The Invoice Deposit must be paid before Mission handover");
        }
        milestone.setHandedOverAt(handedOverAt);
        milestone.setPaymentExpiresAt(handedOverAt.plus(expirationSettings.current().missionPaymentTimeout()));
        milestone.setStatus(MissionPaymentMilestoneStatus.UNPAID);
    }

    @Transactional(readOnly = true)
    public boolean isConfiguredForHandover(String orderId, String missionId) {
        return milestones.findByMissionId(missionId).filter(milestone ->
                orderId.equals(milestone.getInvoice().getOrder().getId())
                        && milestone.getAmount() != null && milestone.getAmount().signum() > 0
                        && "VND".equals(milestone.getCurrency())
                        && milestone.getStatus() != MissionPaymentMilestoneStatus.CANCELLED).isPresent();
    }

    @Transactional(readOnly = true)
    public boolean hasCompleteAllocation(Invoice invoice) {
        if (invoice == null || invoice.getTotalAmount() == null || invoice.getDepositAmount() == null) return false;
        BigDecimal target = allocationTarget(invoice);
        if (target.signum() < 0) return false;
        List<Mission> billable = billableMissions(invoice.getOrder().getId());
        if (billable.isEmpty()) return false;
        List<MissionPaymentMilestone> allocated = milestones.findAllByInvoiceIdForDisplay(invoice.getId());
        if (allocated.size() != billable.size()) return false;
        Set<String> expectedIds = billable.stream().map(Mission::getId).collect(Collectors.toSet());
        Set<String> actualIds = allocated.stream().map(item -> item.getMission().getId()).collect(Collectors.toSet());
        if (!actualIds.equals(expectedIds) || allocated.stream().anyMatch(item ->
                item.getAmount() == null || item.getAmount().signum() <= 0 || !"VND".equals(item.getCurrency()))) return false;
        return allocated.stream().map(MissionPaymentMilestone::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add)
                .compareTo(target) == 0;
    }

    @Transactional(readOnly = true)
    public BigDecimal outstandingAmount() {
        return milestones.sumAmountByStatusIn(OUTSTANDING_STATUSES);
    }

    @Transactional(readOnly = true)
    public long outstandingCount() {
        return milestones.countByStatusIn(OUTSTANDING_STATUSES);
    }

    private List<Mission> billableMissions(String orderId) {
        return missions.findAllByOrderIdOrderByScheduledStartAtAscIdAsc(orderId).stream()
                .filter(ExecutionPlanCoverageService::effective)
                .filter(mission -> mission.getPurpose() != MissionPurpose.REWORK)
                .toList();
    }

    private BigDecimal allocationTarget(Invoice invoice) {
        if (invoice.getTotalAmount() == null || invoice.getDepositAmount() == null) return BigDecimal.ZERO;
        return invoice.getTotalAmount().subtract(invoice.getDepositAmount()).max(BigDecimal.ZERO);
    }

    private MissionPaymentAllocationResponse response(Invoice invoice, List<MissionPaymentMilestone> rows,
            BigDecimal target) {
        List<MissionPaymentMilestoneResponse> mapped = rows.stream().map(milestone ->
                new MissionPaymentMilestoneResponse(milestone.getId(), milestone.getMission().getId(),
                        milestone.getMission().getMissionCode(), milestone.getMission().getScopeLabel(),
                        milestone.getAmount(), milestone.getCurrency(), milestone.getStatus(),
                        milestone.getHandedOverAt(), milestone.getPaymentExpiresAt(),
                        payments.findAllByMissionPaymentMilestoneIdOrderByCreatedAtDesc(
                                milestone.getId()).stream().map(FinanceMapper::payment).toList())).toList();
        List<MissionPaymentMilestone> billableRows = rows.stream()
                .filter(milestone -> milestone.getStatus() != MissionPaymentMilestoneStatus.CANCELLED).toList();
        BigDecimal allocated = billableRows.stream().map(MissionPaymentMilestone::getAmount)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        Set<String> expected = billableMissions(invoice.getOrder().getId()).stream()
                .map(Mission::getId).collect(Collectors.toSet());
        Set<String> actual = billableRows.stream().map(item -> item.getMission().getId()).collect(Collectors.toSet());
        boolean complete = !rows.isEmpty() && actual.equals(expected) && allocated.compareTo(target) == 0;
        return new MissionPaymentAllocationResponse(invoice.getId(), target, allocated, complete, mapped);
    }

    private ApiException invalid(String message) {
        return new ApiException(ErrorCode.INVALID_REQUEST, message);
    }
}
