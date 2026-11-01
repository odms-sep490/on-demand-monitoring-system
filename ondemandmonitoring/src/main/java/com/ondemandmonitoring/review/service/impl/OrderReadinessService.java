package com.ondemandmonitoring.review.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import com.ondemandmonitoring.order.enums.ChecklistReviewStatus;
import com.ondemandmonitoring.order.enums.OrderStatus;
import com.ondemandmonitoring.order.repository.OrderChecklistItemRepository;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.review.domain.OrderReviewRecord;
import com.ondemandmonitoring.review.domain.ReviewRegionPolicy;
import com.ondemandmonitoring.review.dto.request.ManualCriterionRequest;
import com.ondemandmonitoring.review.dto.response.OrderReadinessResponse;
import com.ondemandmonitoring.review.enums.CriterionResult;
import com.ondemandmonitoring.review.enums.ReadinessRecommendation;
import com.ondemandmonitoring.review.enums.ReviewCriterionCode;
import com.ondemandmonitoring.review.enums.ReviewCriterionType;
import com.ondemandmonitoring.review.repository.OrderReviewRecordRepository;
import com.ondemandmonitoring.review.repository.ReviewRegionPolicyRepository;
import com.ondemandmonitoring.review.service.IOrderReadinessService;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderReadinessService implements IOrderReadinessService {

    private final OrderRepository orders;
    private final OrderChecklistItemRepository checklistItems;
    private final OrderReviewRecordRepository records;
    private final AuthenticatedUserResolver currentUser;
    private final ReviewRegionPolicyRepository regionPolicies;

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public OrderReadinessResponse evaluate(String orderId) {
        Order order = orders.findById(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found: " + orderId));
        return build(order, findPolicy(order));
    }

    @Override
    @Transactional
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public OrderReadinessResponse answer(String orderId, ReviewCriterionCode code, ManualCriterionRequest request) {
        if (!code.isActive()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Criterion is no longer used: " + code);
        }
        if (code.getType() != ReviewCriterionType.MANUAL) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Criterion is computed by the system: " + code);
        }
        if (request.result() == CriterionResult.PENDING
                || (request.result() == CriterionResult.WARN && code != ReviewCriterionCode.RISK_LEVEL)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Choose PASS or FAIL");
        }
        String note = trimToNull(request.note());
        if (request.result() == CriterionResult.FAIL && note == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "A reason is required when a criterion fails");
        }
        Order order = orders.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found: " + orderId));
        if (!OrderStatus.UNDER_REVIEW.equals(order.getOrderStatus())) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Order is no longer under review");
        }
        OrderReviewRecord record = records.findByOrderIdAndCode(orderId, code)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "Review criterion record not found for order " + orderId + ": " + code));
        record.setType(ReviewCriterionType.MANUAL);
        record.setResult(request.result());
        record.setNote(note);
        record.setDecidedBy(currentUser.getCurrentUser());
        record.setDecidedAt(Instant.now());
        records.saveAndFlush(record);
        return build(order, findPolicy(order));
    }

    @Override
    @Transactional
    public void requireApprovable(String orderId) {
        Order order = orders.findById(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found: " + orderId));
        if (order.getOrderStatus() == OrderStatus.REJECTED || order.getOrderStatus() == OrderStatus.CANCELLED) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Order was rejected or cancelled");
        }
        java.util.Optional<ReviewRegionPolicy> policy = findPolicy(order);
        OrderReadinessResponse readiness = build(order, policy);
        if (policy.map(ReviewRegionPolicy::isEnforceReview).orElse(true) && !readiness.canApprove()) {
            throw new ApiException(ErrorCode.ORDER_REVIEW_INCOMPLETE,
                    String.join("; ", readiness.blockingReasons()));
        }
        snapshotAutomatic(order, readiness);
    }

    // ---------------------------------------------------------------------

    private OrderReadinessResponse build(Order order, java.util.Optional<ReviewRegionPolicy> policy) {
        Map<ReviewCriterionCode, OrderReviewRecord> manual = new EnumMap<>(ReviewCriterionCode.class);
        for (OrderReviewRecord record : records.findByOrderId(order.getId())) {
            if (record.getType() == ReviewCriterionType.MANUAL) manual.put(record.getCode(), record);
        }
        List<OrderReadinessResponse.Criterion> criteria = new ArrayList<>();
        List<String> blocking = new ArrayList<>();
        boolean anyConcern = false;
        for (ReviewCriterionCode code : ReviewCriterionCode.values()) {
            if (!code.isActive()) continue;
            OrderReadinessResponse.Criterion criterion = code.getType() == ReviewCriterionType.AUTO
                    ? automatic(order, code, policy)
                    : manual(code, manual.get(code));
            criteria.add(criterion);
            if (criterion.required()) {
                if (criterion.result() == CriterionResult.FAIL) {
                    blocking.add(code.getTitle() + ": " + describe(criterion));
                } else if (criterion.result() == CriterionResult.PENDING) {
                    blocking.add(code.getTitle() + ": manager chưa xác nhận");
                }
            }
            if (criterion.result() == CriterionResult.WARN || criterion.result() == CriterionResult.FAIL) {
                anyConcern = true;
            }
        }
        boolean pendingRequired = criteria.stream()
                .anyMatch(c -> c.required() && c.result() == CriterionResult.PENDING);
        boolean failedRequired = criteria.stream()
                .anyMatch(c -> c.required() && c.result() == CriterionResult.FAIL);
        ReadinessRecommendation recommendation = failedRequired ? ReadinessRecommendation.REQUEST_INFO_OR_REJECT
                : pendingRequired ? ReadinessRecommendation.COMPLETE_REVIEW
                : anyConcern ? ReadinessRecommendation.APPROVE_WITH_NOTES
                : ReadinessRecommendation.APPROVE;
        return new OrderReadinessResponse(order.getId(), criteria, blocking.isEmpty(), blocking, recommendation);
    }

    private OrderReadinessResponse.Criterion manual(ReviewCriterionCode code, OrderReviewRecord record) {
        return new OrderReadinessResponse.Criterion(code, code.getType(), code.getTitle(), code.getDescription(),
                code.isRequired(),
                record == null ? CriterionResult.PENDING : record.getResult(),
                null,
                record == null ? null : record.getNote(),
                record == null || record.getDecidedBy() == null ? null : record.getDecidedBy().getFullName(),
                record == null ? null : record.getDecidedAt());
    }

    private OrderReadinessResponse.Criterion automatic(
            Order order, ReviewCriterionCode code, java.util.Optional<ReviewRegionPolicy> policy) {
        Verdict verdict = switch (code) {
            case SERVICE_AREA -> serviceArea(order, policy);
            case CHECKLIST_REVIEWED -> checklistReviewed(order);
            case SITE_CONTACT -> siteContact(order);
            case SCHEDULE_DATE -> scheduleDate(order, policy);
            default -> throw new IllegalStateException("Not an automatic criterion: " + code);
        };
        return new OrderReadinessResponse.Criterion(code, code.getType(), code.getTitle(), code.getDescription(),
                code.isRequired(), verdict.result(), verdict.reason(), null, null, null);
    }

    private Verdict serviceArea(Order order, java.util.Optional<ReviewRegionPolicy> policy) {
        if (order.getPoint() == null) return new Verdict(CriterionResult.FAIL, "Đơn chưa có tọa độ");
        return policy
                .map(value -> new Verdict(CriterionResult.PASS,
                        "Vị trí và vùng giám sát nằm trong vùng phục vụ " + value.getName()))
                .orElseGet(() -> new Verdict(CriterionResult.FAIL,
                        "Không có vùng phục vụ đang hoạt động trong database phù hợp với đơn"));
    }

    private Verdict checklistReviewed(Order order) {
        List<OrderChecklistItem> items = checklistItems.findAllByOrderIdOrderByDisplayOrderAscIdAsc(order.getId());
        long pending = items.stream()
                .filter(item -> item.getReviewStatus() == null || item.getReviewStatus() == ChecklistReviewStatus.PENDING)
                .count();
        if (pending > 0) return new Verdict(CriterionResult.FAIL, "Còn " + pending + "/" + items.size() + " mục chưa review");
        return new Verdict(CriterionResult.PASS, items.isEmpty() ? "Đơn không có mục checklist"
                : "Đã review " + items.size() + "/" + items.size() + " mục");
    }

    private Verdict siteContact(Order order) {
        boolean hasName = order.getSiteContactName() != null && !order.getSiteContactName().isBlank();
        boolean hasPhone = order.getSiteContactPhone() != null && !order.getSiteContactPhone().isBlank();
        if (hasName && hasPhone) {
            return new Verdict(
                    CriterionResult.PASS,
                    "Người liên hệ: " + order.getSiteContactName().strip()
                            + " · SĐT: " + order.getSiteContactPhone().strip());
        }
        if (hasName || hasPhone) return new Verdict(CriterionResult.WARN, "Thông tin liên hệ hiện trường chưa đầy đủ");
        return new Verdict(CriterionResult.WARN, "Chưa có người liên hệ tại hiện trường");
    }

    private Verdict scheduleDate(Order order, java.util.Optional<ReviewRegionPolicy> policy) {
        if (policy.isEmpty()) {
            return new Verdict(CriterionResult.WARN, "Không xác định được múi giờ vì chưa có policy vùng phù hợp");
        }
        ReviewRegionPolicy region = policy.get();
        LocalDate today = LocalDate.now(region.businessZoneId());
        LocalDate to = order.getPreferredDateTo();
        if (to != null && to.isBefore(today)) {
            return new Verdict(CriterionResult.WARN, "Ngày mong muốn đã qua, cần khách chọn lại");
        }
        LocalDate deadline = order.getResultDeadline();
        if (to != null && deadline != null && deadline.isBefore(to.plusDays(region.getTightDeadlineDays()))) {
            return new Verdict(CriterionResult.WARN, "Hạn nhận kết quả rất sát ngày thực hiện");
        }
        return new Verdict(CriterionResult.PASS, "Thời gian thực hiện hợp lệ");
    }

    private void snapshotAutomatic(Order order, OrderReadinessResponse readiness) {
        User actor = currentUser.getCurrentUser();
        Instant now = Instant.now();
        for (OrderReadinessResponse.Criterion criterion : readiness.criteria()) {
            if (criterion.type() != ReviewCriterionType.AUTO) continue;
            OrderReviewRecord record = records.findByOrderIdAndCode(order.getId(), criterion.code()).orElseGet(() -> {
                OrderReviewRecord created = new OrderReviewRecord();
                created.setOrder(order);
                created.setCode(criterion.code());
                return created;
            });
            record.setType(ReviewCriterionType.AUTO);
            record.setResult(criterion.result());
            record.setReason(criterion.reason());
            record.setDecidedBy(actor);
            record.setDecidedAt(now);
            records.save(record);
        }
    }

    private static String describe(OrderReadinessResponse.Criterion criterion) {
        if (criterion.reason() != null) return criterion.reason();
        return criterion.note() != null ? criterion.note() : "không đạt";
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private java.util.Optional<ReviewRegionPolicy> findPolicy(Order order) {
        if (order.getPoint() == null) return java.util.Optional.empty();
        return regionPolicies.findAllByActiveTrueOrderByPriorityDescCodeAsc().stream()
                .filter(policy -> policy.getServiceArea() != null)
                .filter(policy -> policy.getServiceArea().covers(order.getPoint()))
                .filter(policy -> order.getTargetArea() == null || policy.getServiceArea().covers(order.getTargetArea()))
                .findFirst();
    }

    private record Verdict(CriterionResult result, String reason) {}
}
