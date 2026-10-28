package com.ondemandmonitoring.dashboard.service;

import com.ondemandmonitoring.dashboard.dto.ManagerDashboardResponse;
import com.ondemandmonitoring.dashboard.repository.ManagerDashboardAnalyticsRepository;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.finance.domain.Payment;
import com.ondemandmonitoring.finance.domain.Quote;
import com.ondemandmonitoring.finance.domain.Invoice;
import com.ondemandmonitoring.finance.enums.InvoiceStatus;
import com.ondemandmonitoring.finance.enums.PaymentStatus;
import com.ondemandmonitoring.finance.enums.PaymentType;
import com.ondemandmonitoring.finance.enums.QuoteStatus;
import com.ondemandmonitoring.finance.repository.InvoiceRepository;
import com.ondemandmonitoring.finance.repository.PaymentRepository;
import com.ondemandmonitoring.finance.repository.QuoteRepository;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.domain.MissionDeviceAssignment;
import com.ondemandmonitoring.mission.domain.MissionStaffAssignment;
import com.ondemandmonitoring.mission.enums.MissionStaffRole;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.repository.MissionDeviceAssignmentRepository;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.mission.repository.MissionStaffAssignmentRepository;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import com.ondemandmonitoring.order.enums.ChecklistPricingStatus;
import com.ondemandmonitoring.order.enums.OrderChecklistSourceType;
import com.ondemandmonitoring.order.enums.OrderStatus;
import com.ondemandmonitoring.order.repository.OrderChecklistItemRepository;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.pricing.domain.ChecklistPricingAudit;
import com.ondemandmonitoring.pricing.repository.ChecklistPricingAuditRepository;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ManagerDashboardService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final int PREVIEW_LIMIT = 8;
    private static final Collection<OrderChecklistSourceType> CUSTOMER_ADDED =
            EnumSet.of(OrderChecklistSourceType.CUSTOMER_SELECTED, OrderChecklistSourceType.CUSTOMER_CUSTOM);
    private static final Collection<MissionStatus> ACTIVE_MISSIONS = EnumSet.of(
            MissionStatus.SCHEDULED, MissionStatus.CONNECTED, MissionStatus.PREFLIGHT_CHECKING,
            MissionStatus.READY_TO_FLY, MissionStatus.IN_FLIGHT, MissionStatus.IN_PROGRESS,
            MissionStatus.RETURNING, MissionStatus.POSTFLIGHT_CHECKING);
    private static final Collection<MissionStatus> MISSION_ISSUES =
            EnumSet.of(MissionStatus.FAILED_PREFLIGHT, MissionStatus.FAILED);
    private static final Collection<MissionStatus> RESOURCE_PENDING = EnumSet.of(
            MissionStatus.PLANNING, MissionStatus.READY,
            MissionStatus.WAITING_OPERATOR_ACCEPTANCE, MissionStatus.SCHEDULED);
    private static final Collection<MissionStatus> RECENT_ACTIVITY_MISSIONS = EnumSet.of(
            MissionStatus.SCHEDULED, MissionStatus.CONNECTED, MissionStatus.PREFLIGHT_CHECKING,
            MissionStatus.READY_TO_FLY, MissionStatus.FAILED_PREFLIGHT, MissionStatus.IN_FLIGHT,
            MissionStatus.IN_PROGRESS, MissionStatus.RETURNING, MissionStatus.POSTFLIGHT_CHECKING,
            MissionStatus.COMPLETED, MissionStatus.FAILED, MissionStatus.CANCELLED);

    private final OrderRepository orders;
    private final OrderChecklistItemRepository checklistItems;
    private final QuoteRepository quotes;
    private final InvoiceRepository invoices;
    private final PaymentRepository payments;
    private final MissionRepository missions;
    private final MissionStaffAssignmentRepository staffAssignments;
    private final MissionDeviceAssignmentRepository deviceAssignments;
    private final ChecklistPricingAuditRepository pricingAudits;
    private final ManagerDashboardAnalyticsRepository analytics;
    private final AuthenticatedUserResolver currentUser;
    private final Clock clock = Clock.system(BUSINESS_ZONE);

    @Transactional(readOnly = true)
    public ManagerDashboardResponse getDashboard() {
        return getDashboard("30D", null, null);
    }

    @Transactional(readOnly = true)
    public ManagerDashboardResponse getDashboard(String range, LocalDate requestedFrom, LocalDate requestedTo) {
        // Resolving the authenticated account here keeps dashboard authority server-side.
        currentUser.getCurrentUser();
        DashboardPeriod selected = resolvePeriod(range, requestedFrom, requestedTo);
        Instant periodFrom = selected.from().atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant periodTo = selected.to().plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant();

        long pendingOrders = orders.countByOrderStatus(OrderStatus.PENDING);
        long pendingPricing = checklistItems.countByPricingStatusAndSourceTypeIn(
                ChecklistPricingStatus.NEEDS_MANAGER_REVIEW, CUSTOMER_ADDED);
        long waitingCustomer = quotes.countByStatus(QuoteStatus.APPROVED);
        long waitingDeposit = invoices.countWaitingDeposit(List.of(InvoiceStatus.ISSUED, InvoiceStatus.PARTIALLY_PAID));
        long activeMissionCount = missions.countByStatusIn(ACTIVE_MISSIONS);
        long missionIssues = missions.countByStatusIn(MISSION_ISSUES);
        long blockedPricing = checklistItems.countByPricingStatus(ChecklistPricingStatus.BLOCKED_BY_POLICY);
        long paymentIssues = invoices.countDepositPaymentIssues();
        long resourceIssues = missions.countMissingResources(RESOURCE_PENDING);
        long attention = missionIssues + blockedPricing + paymentIssues + resourceIssues;

        var summary = new ManagerDashboardResponse.Summary(
                analytics.countOrders(periodFrom, periodTo), analytics.totalCurrentQuoteValue(periodFrom, periodTo),
                pendingOrders, pendingPricing,
                waitingDeposit, activeMissionCount, attention);
        var pipeline = new ManagerDashboardResponse.PricingPipeline(
                pendingPricing,
                orders.countQuoteReadyOrders(
                        List.of(OrderStatus.REJECTED, OrderStatus.CANCELLED, OrderStatus.COMPLETED),
                        List.of(ChecklistPricingStatus.NEEDS_MANAGER_REVIEW, ChecklistPricingStatus.BLOCKED_BY_POLICY),
                        List.of(QuoteStatus.DRAFT, QuoteStatus.PENDING_REVIEW, QuoteStatus.APPROVED,
                                QuoteStatus.ACCEPTED_BY_CUSTOMER)),
                waitingCustomer,
                waitingDeposit,
                payments.countByPaymentTypeAndStatus(PaymentType.DEPOSIT, PaymentStatus.SUCCESS));

        Instant startOfToday = LocalDate.now(clock).atStartOfDay(BUSINESS_ZONE).toInstant();
        var missionOverview = new ManagerDashboardResponse.MissionOverview(
                missions.countByStatus(MissionStatus.SCHEDULED),
                missions.countByStatus(MissionStatus.READY_TO_FLY),
                missions.countByStatusIn(List.of(MissionStatus.IN_FLIGHT, MissionStatus.IN_PROGRESS)),
                missions.countByStatus(MissionStatus.RETURNING),
                missions.countByStatusAndCompletedAtGreaterThanEqual(MissionStatus.COMPLETED, startOfToday),
                missionIssues + resourceIssues);

        return new ManagerDashboardResponse(summary,
                analytics.orderStatuses(periodFrom, periodTo),
                analytics.services(periodFrom, periodTo),
                analytics.additionalServices(periodFrom, periodTo),
                analytics.priceAdjustments(periodFrom, periodTo),
                analytics.missionStatuses(),
                analytics.missionTrend(periodFrom, periodTo, selected.bucket()),
                actionItems(), recentOrders(periodFrom, periodTo), pipeline, missionOverview,
                activeMissions(), recentActivities(periodFrom, periodTo), Instant.now(clock));
    }

    private DashboardPeriod resolvePeriod(String range, LocalDate requestedFrom, LocalDate requestedTo) {
        LocalDate today = LocalDate.now(clock);
        String preset = range == null ? "30D" : range.toUpperCase(java.util.Locale.ROOT);
        if (requestedFrom != null || requestedTo != null || "CUSTOM".equals(preset)) {
            if (requestedFrom == null || requestedTo == null || requestedFrom.isAfter(requestedTo)) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "Khoảng thời gian Dashboard không hợp lệ");
            }
            long days = ChronoUnit.DAYS.between(requestedFrom, requestedTo) + 1;
            if (days > 366 || requestedTo.isAfter(today)) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "Dashboard chỉ hỗ trợ tối đa 366 ngày và không vượt quá hôm nay");
            }
            return new DashboardPeriod(requestedFrom, requestedTo, days > 60 ? "week" : "day");
        }
        int days = switch (preset) {
            case "7D" -> 7;
            case "30D" -> 30;
            case "90D" -> 90;
            default -> throw new ApiException(ErrorCode.INVALID_REQUEST, "range phải là 7D, 30D, 90D hoặc CUSTOM");
        };
        return new DashboardPeriod(today.minusDays(days - 1L), today, days == 90 ? "week" : "day");
    }

    private record DashboardPeriod(LocalDate from, LocalDate to, String bucket) {}

    private List<ManagerDashboardResponse.ActionItem> actionItems() {
        List<ManagerDashboardResponse.ActionItem> result = new ArrayList<>();
        checklistItems.findByPricingStatusOrderByCreatedAtAsc(ChecklistPricingStatus.BLOCKED_BY_POLICY,
                PageRequest.of(0, PREVIEW_LIMIT)).forEach(item -> result.add(checklistAction(item, "HIGH")));
        checklistItems.findByPricingStatusAndSourceTypeInOrderByCreatedAtAsc(
                ChecklistPricingStatus.NEEDS_MANAGER_REVIEW, CUSTOMER_ADDED, PageRequest.of(0, PREVIEW_LIMIT))
                .forEach(item -> result.add(checklistAction(item, "MEDIUM")));
        missions.findByStatusInOrderByUpdatedAtDesc(MISSION_ISSUES, PageRequest.of(0, PREVIEW_LIMIT))
                .forEach(mission -> result.add(missionAction(mission, "HIGH", "MISSION_ISSUE", "VIEW_MISSION")));
        missions.findMissingResources(RESOURCE_PENDING, PageRequest.of(0, PREVIEW_LIMIT))
                .forEach(mission -> result.add(missionAction(mission, "HIGH", "RESOURCE_MISSING", "VIEW_MISSION")));
        invoices.findDepositPaymentIssues(PageRequest.of(0, PREVIEW_LIMIT))
                .forEach(invoice -> result.add(paymentIssueAction(invoice)));
        orders.findByOrderStatusOrderByCreatedAtAsc(OrderStatus.PENDING, PageRequest.of(0, PREVIEW_LIMIT))
                .forEach(order -> result.add(orderAction(order)));
        orders.findQuoteReadyOrders(
                        List.of(OrderStatus.REJECTED, OrderStatus.CANCELLED, OrderStatus.COMPLETED),
                        List.of(ChecklistPricingStatus.NEEDS_MANAGER_REVIEW, ChecklistPricingStatus.BLOCKED_BY_POLICY),
                        List.of(QuoteStatus.DRAFT, QuoteStatus.PENDING_REVIEW, QuoteStatus.APPROVED,
                                QuoteStatus.ACCEPTED_BY_CUSTOMER), PageRequest.of(0, PREVIEW_LIMIT))
                .forEach(order -> result.add(quoteReadyOrderAction(order)));
        quotes.findByStatusInOrderByUpdatedAtDesc(List.of(QuoteStatus.DRAFT), PageRequest.of(0, PREVIEW_LIMIT))
                .forEach(quote -> result.add(quoteAction(quote)));
        return result.stream()
                .sorted(Comparator.comparingInt((ManagerDashboardResponse.ActionItem item) -> priority(item.priority()))
                        .thenComparing(ManagerDashboardResponse.ActionItem::createdAt,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(PREVIEW_LIMIT).toList();
    }

    private ManagerDashboardResponse.ActionItem checklistAction(OrderChecklistItem item, String priority) {
        Order order = item.getOrder();
        boolean blocked = item.getPricingStatus() == ChecklistPricingStatus.BLOCKED_BY_POLICY;
        return new ManagerDashboardResponse.ActionItem("checklist-" + item.getId(),
                blocked ? "PRICING_BLOCKED" : "ADDITIONAL_CHECKLIST_PRICING", priority,
                order.getId(), order.getOrderCode(), null, item.getId(),
                blocked ? "Định giá checklist đang bị chặn" : "Dịch vụ bổ sung chờ duyệt giá",
                item.getContent(), item.getPricingStatus().name(), item.getSystemCalculatedPrice(),
                item.getCreatedAt(), "REVIEW_PRICING");
    }

    private ManagerDashboardResponse.ActionItem missionAction(
            Mission mission, String priority, String type, String actionType) {
        return new ManagerDashboardResponse.ActionItem("mission-" + mission.getId(), type, priority,
                mission.getOrder().getId(), mission.getOrder().getOrderCode(), mission.getId(), null,
                "Mission cần xử lý", mission.getMissionCode(), mission.getStatus().name(), null,
                mission.getUpdatedAt(), actionType);
    }

    private ManagerDashboardResponse.ActionItem orderAction(Order order) {
        return new ManagerDashboardResponse.ActionItem("order-" + order.getId(), "ORDER_PENDING", "MEDIUM",
                order.getId(), order.getOrderCode(), null, null, "Yêu cầu mới chờ xem xét",
                order.getTitle(), order.getOrderStatus().name(), null, order.getCreatedAt(), "VIEW_ORDER");
    }

    private ManagerDashboardResponse.ActionItem quoteAction(Quote quote) {
        Order order = quote.getOrder();
        return new ManagerDashboardResponse.ActionItem("quote-" + quote.getId(), "QUOTE_READY", "MEDIUM",
                order.getId(), order.getOrderCode(), null, null, "Báo giá sẵn sàng gửi",
                "Bản nháp báo giá đã hoàn tất", quote.getStatus().name(), null,
                quote.getUpdatedAt(), "VIEW_QUOTE");
    }

    private ManagerDashboardResponse.ActionItem quoteReadyOrderAction(Order order) {
        return new ManagerDashboardResponse.ActionItem("quote-ready-" + order.getId(), "QUOTE_READY", "MEDIUM",
                order.getId(), order.getOrderCode(), null, null, "Báo giá sẵn sàng tạo",
                "Tất cả checklist đã được xử lý", "READY", null, order.getUpdatedAt(), "VIEW_QUOTE");
    }

    private ManagerDashboardResponse.ActionItem paymentIssueAction(Invoice invoice) {
        Order order = invoice.getOrder();
        return new ManagerDashboardResponse.ActionItem("payment-issue-" + invoice.getId(), "PAYMENT_ISSUE", "HIGH",
                order.getId(), order.getOrderCode(), null, null, "Thanh toán cọc cần kiểm tra",
                invoice.getInvoiceNumber(), invoice.getStatus().name(), null, invoice.getUpdatedAt(), "VIEW_ORDER");
    }

    private List<ManagerDashboardResponse.RecentOrder> recentOrders(Instant from, Instant to) {
        List<Order> recent = orders.findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(
                from, to, PageRequest.of(0, 5)).getContent();
        List<String> ids = recent.stream().map(Order::getId).toList();
        Map<String, OrderChecklistItemRepository.OrderAdditionalChecklistCount> counts = ids.isEmpty() ? Map.of()
                : checklistItems.countAdditionalByOrderIds(ids, CUSTOMER_ADDED).stream()
                        .collect(Collectors.toMap(OrderChecklistItemRepository.OrderAdditionalChecklistCount::getOrderId,
                                Function.identity()));
        return recent.stream().map(order -> {
            var count = counts.get(order.getId());
            long additional = count == null ? 0 : count.getAdditionalCount();
            long pending = count == null ? 0 : count.getPendingCount();
            String pricingState = pending > 0 ? "NEEDS_MANAGER_REVIEW" : "RESOLVED";
            return new ManagerDashboardResponse.RecentOrder(order.getId(), order.getOrderCode(),
                    order.getCustomer().getFullName(), order.getService().getName(), order.getCreatedAt(),
                    order.getOrderStatus().name(), additional, pending, pricingState);
        }).toList();
    }

    private List<ManagerDashboardResponse.ActiveMission> activeMissions() {
        List<Mission> active = missions.findByStatusInOrderByScheduledStartAtAsc(
                ACTIVE_MISSIONS, PageRequest.of(0, PREVIEW_LIMIT)).getContent();
        List<String> ids = active.stream().map(Mission::getId).toList();
        if (ids.isEmpty()) return List.of();
        Map<String, MissionStaffAssignment> operators = new HashMap<>();
        for (MissionStaffAssignment assignment : staffAssignments.findAllByMissionIdInAndIsCurrentTrue(ids)) {
            if (assignment.getAssignedRole() == MissionStaffRole.OPERATOR || assignment.getAssignedRole() == MissionStaffRole.PILOT) {
                operators.putIfAbsent(assignment.getMission().getId(), assignment);
            }
        }
        Map<String, MissionDeviceAssignment> devices = new HashMap<>();
        for (MissionDeviceAssignment assignment : deviceAssignments.findAllByMissionIdInAndIsCurrentTrue(ids)) {
            devices.putIfAbsent(assignment.getMission().getId(), assignment);
        }
        return active.stream().map(mission -> {
            MissionStaffAssignment operator = operators.get(mission.getId());
            MissionDeviceAssignment device = devices.get(mission.getId());
            boolean needsAttention = operator == null || device == null || MISSION_ISSUES.contains(mission.getStatus());
            return new ManagerDashboardResponse.ActiveMission(mission.getId(), mission.getMissionCode(),
                    mission.getOrder().getId(), mission.getOrder().getOrderCode(),
                    operator == null ? null : operator.getStaff().getFullName(),
                    device == null ? null : displayDevice(device), mission.getStatus().name(),
                    mission.getScheduledStartAt(), needsAttention);
        }).toList();
    }

    private String displayDevice(MissionDeviceAssignment assignment) {
        String code = assignment.getDevice().getDeviceCode();
        return code == null || code.isBlank() ? assignment.getDevice().getName() : code;
    }

    private List<ManagerDashboardResponse.Activity> recentActivities(Instant from, Instant to) {
        List<ManagerDashboardResponse.Activity> result = new ArrayList<>();
        for (ChecklistPricingAudit audit : pricingAudits.findAllByOrderByReviewedAtDesc(PageRequest.of(0, 5))) {
            Order order = audit.getOrderChecklistItem().getOrder();
            result.add(new ManagerDashboardResponse.Activity("pricing-" + audit.getId(), "PRICING_REVIEW",
                    order.getId(), order.getOrderCode(), null,
                    "Đã duyệt giá dịch vụ bổ sung cho " + order.getOrderCode(), audit.getReviewedAt()));
        }
        for (Payment payment : payments.findByStatusOrderByPaidAtDesc(PaymentStatus.SUCCESS, PageRequest.of(0, 5))) {
            Order order = payment.getInvoice().getOrder();
            result.add(new ManagerDashboardResponse.Activity("payment-" + payment.getId(), "PAYMENT_SUCCESS",
                    order.getId(), order.getOrderCode(), null,
                    (payment.getPaymentType() == PaymentType.DEPOSIT ? "Đặt cọc" : "Thanh toán")
                            + " cho " + order.getOrderCode() + " đã thành công",
                    payment.getPaidAt() == null ? payment.getUpdatedAt() : payment.getPaidAt()));
        }
        for (Quote quote : quotes.findByStatusInOrderByUpdatedAtDesc(
                List.of(QuoteStatus.APPROVED, QuoteStatus.ACCEPTED_BY_CUSTOMER), PageRequest.of(0, 5))) {
            Order order = quote.getOrder();
            String description = quote.getStatus() == QuoteStatus.ACCEPTED_BY_CUSTOMER
                    ? "Khách hàng đã xác nhận báo giá " + order.getOrderCode()
                    : "Đã gửi báo giá cho " + order.getOrderCode();
            result.add(new ManagerDashboardResponse.Activity("quote-" + quote.getId(), "QUOTE", order.getId(),
                    order.getOrderCode(), null, description, quote.getUpdatedAt()));
        }
        for (Mission mission : missions.findByStatusInOrderByUpdatedAtDesc(
                RECENT_ACTIVITY_MISSIONS, PageRequest.of(0, 5))) {
            Order order = mission.getOrder();
            result.add(new ManagerDashboardResponse.Activity("mission-" + mission.getId(), "MISSION",
                    order.getId(), order.getOrderCode(), mission.getId(),
                    "Mission " + mission.getMissionCode() + " chuyển sang " + mission.getStatus().name(),
                    mission.getUpdatedAt()));
        }
        return result.stream().filter(item -> item.occurredAt() != null
                        && !item.occurredAt().isBefore(from) && item.occurredAt().isBefore(to))
                .sorted(Comparator.comparing(ManagerDashboardResponse.Activity::occurredAt).reversed())
                .limit(PREVIEW_LIMIT).toList();
    }

    private int priority(String value) {
        return switch (value) { case "HIGH" -> 0; case "MEDIUM" -> 1; default -> 2; };
    }
}
