package com.ondemandmonitoring.notification.service;

import com.ondemandmonitoring.finance.domain.Payment;
import com.ondemandmonitoring.finance.enums.PaymentStatus;
import com.ondemandmonitoring.finance.enums.PaymentType;
import com.ondemandmonitoring.finance.repository.PaymentRepository;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.domain.MissionStaffAssignment;
import com.ondemandmonitoring.mission.enums.MissionStaffAssignmentStatus;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.mission.repository.MissionStaffAssignmentRepository;
import com.ondemandmonitoring.notification.dto.NotificationFeedResponse;
import com.ondemandmonitoring.notification.dto.NotificationItemResponse;
import com.ondemandmonitoring.notification.dto.NotificationType;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.enums.OrderStatus;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationFeedService {

    private static final int MAX_LIMIT = 50;
    private static final List<MissionStatus> STAFF_MISSION_STATUSES = List.of(
            MissionStatus.PENDING_CONFIRMATION,
            MissionStatus.READY,
            MissionStatus.IN_PROGRESS,
            MissionStatus.AWAITING_REVIEW,
            MissionStatus.PENDING_REVIEW,
            MissionStatus.COMPLETED,
            MissionStatus.FAILED);

    private final AuthenticatedUserResolver currentUser;
    private final OrderRepository orders;
    private final MissionRepository missions;
    private final PaymentRepository payments;
    private final MissionStaffAssignmentRepository staffAssignments;
    private final com.ondemandmonitoring.mission.repository.MissionDeviceAssignmentRepository deviceAssignments;

    public NotificationFeedService(
            AuthenticatedUserResolver currentUser,
            OrderRepository orders,
            MissionRepository missions,
            PaymentRepository payments,
            MissionStaffAssignmentRepository staffAssignments,
            com.ondemandmonitoring.mission.repository.MissionDeviceAssignmentRepository deviceAssignments) {
        this.currentUser = currentUser;
        this.orders = orders;
        this.missions = missions;
        this.payments = payments;
        this.staffAssignments = staffAssignments;
        this.deviceAssignments = deviceAssignments;
    }

    @Transactional(readOnly = true)
    public NotificationFeedResponse listForCurrentUser(int requestedLimit) {
        User user = currentUser.getCurrentUser();
        int limit = Math.max(1, Math.min(requestedLimit, MAX_LIMIT));
        List<NotificationItemResponse> items = new ArrayList<>(switch (user.getRole().getCode()) {
            case CUSTOMER -> customerItems(user.getId(), limit);
            case MANAGER -> managerItems(limit);
            case STAFF -> staffItems(user.getId(), limit);
            // New order alerts are reserved for managers.
            case ADMIN -> List.of();
        });
        items.sort(Comparator.comparing(NotificationItemResponse::createdAt,
                Comparator.nullsLast(Comparator.naturalOrder())).reversed());
        return new NotificationFeedResponse(List.copyOf(items));
    }

    private List<NotificationItemResponse> customerItems(String customerId, int limit) {
        List<NotificationItemResponse> items = new ArrayList<>();
        orders.findByCustomer_IdOrderByCreatedAtDesc(customerId, PageRequest.of(0, limit))
                .stream()
                .filter(order -> order.getOrderStatus() != OrderStatus.PENDING)
                .forEach(order -> items.add(orderStatusChanged(order)));
        missions.findByOrder_Customer_IdAndStatusIn(
                        customerId, List.of(MissionStatus.COMPLETED), PageRequest.of(0, limit))
                .forEach(mission -> items.add(missionItem(mission, NotificationType.MISSION_COMPLETED,
                        completedAt(mission))));
        return items.stream().sorted(byTimeDescending()).limit(limit).toList();
    }

    private List<NotificationItemResponse> managerItems(int limit) {
        List<NotificationItemResponse> items = new ArrayList<>();
        // Only new orders need manager action. UNDER_REVIEW and NEEDS_INFO are
        // customer-facing status updates and belong in the customer's feed.
        orders.findByOrderStatusInOrderByCreatedAtDesc(
                        List.of(OrderStatus.PENDING),
                        PageRequest.of(0, limit))
                .forEach(order -> items.add(orderStatusChanged(order)));

        payments.findRecentByStatusAndPaymentType(
                        PaymentStatus.SUCCESS, PaymentType.DEPOSIT, PageRequest.of(0, limit))
                .forEach(payment -> items.add(depositPaid(payment)));

        Set<String> acceptedEvents = new HashSet<>();
        Set<String> replacementEvents = new HashSet<>();
        staffAssignments.findByResponseStatusAndIsCurrentTrueAndReleasedAtIsNullOrderByRespondedAtDesc(
                MissionStaffAssignmentStatus.REJECTED, PageRequest.of(0, limit)).forEach(assignment -> {
            Mission mission = assignment.getMission();
            if (List.of(MissionStatus.COMPLETED, MissionStatus.CANCELLED, MissionStatus.FAILED).contains(mission.getStatus())) return;
            Instant at = assignment.getRespondedAt() != null ? assignment.getRespondedAt() : assignment.getUpdatedAt();
            String id = "mission-staff-replacement:" + mission.getId() + ":" + assignment.getStaff().getId() + ":" + at;
            if (!replacementEvents.add(id)) return;
            items.add(new NotificationItemResponse(id, NotificationType.MISSION_STAFF_REPLACEMENT_REQUIRED,
                    mission.getId(), mission.getOrder().getId(), mission.getMissionCode(), at,
                    assignment.getStaff().getFullName()));
        });
        deviceAssignments.findByIsCurrentTrueAndReleasedAtIsNullAndReplacementRequestedAtIsNotNullOrderByReplacementRequestedAtDesc(
                PageRequest.of(0, limit)).forEach(assignment -> {
            Mission mission = assignment.getMission();
            if (assignment.getReplacementAssignment() != null ||
                    List.of(MissionStatus.COMPLETED, MissionStatus.CANCELLED, MissionStatus.FAILED).contains(mission.getStatus())) return;
            Instant at = assignment.getReplacementRequestedAt();
            items.add(new NotificationItemResponse("mission-drone-replacement:" + assignment.getId() + ":" + at,
                    NotificationType.MISSION_DRONE_REPLACEMENT_REQUIRED, mission.getId(), mission.getOrder().getId(),
                    mission.getMissionCode(), at, assignment.getDevice().getDeviceCode()));
        });
        staffAssignments.findByResponseStatusAndIsCurrentTrueAndReleasedAtIsNullOrderByRespondedAtDesc(
                        MissionStaffAssignmentStatus.ACCEPTED, PageRequest.of(0, limit))
                .forEach(assignment -> {
                    Mission mission = assignment.getMission();
                    Instant acceptedAt = assignment.getRespondedAt() != null
                            ? assignment.getRespondedAt()
                            : assignment.getUpdatedAt() != null
                                    ? assignment.getUpdatedAt() : assignment.getCreatedAt();
                    String staffId = assignment.getStaff().getId();
                    String eventId = "mission-staff-accepted:" + mission.getId()
                            + ":" + staffId + ":" + acceptedAt;
                    // One staff response can accept several role rows at the same time.
                    if (!acceptedEvents.add(eventId)) return;

                    String reference = mission.getMissionCode() == null || mission.getMissionCode().isBlank()
                            ? mission.getId() : mission.getMissionCode();
                    items.add(new NotificationItemResponse(
                            eventId,
                            NotificationType.MISSION_STAFF_ACCEPTED,
                            mission.getId(),
                            mission.getOrder().getId(),
                            reference,
                            acceptedAt,
                            assignment.getStaff().getFullName()));
                });

        missions.findByStatusInOrderByUpdatedAtDesc(
                        List.of(MissionStatus.AWAITING_REVIEW, MissionStatus.PENDING_REVIEW,
                                MissionStatus.FAILED), PageRequest.of(0, limit))
                .forEach(mission -> {
                    NotificationType type = mission.getStatus() == MissionStatus.FAILED
                            ? NotificationType.MISSION_WARNING
                            : NotificationType.MISSION_REVIEW_REQUIRED;
                    items.add(missionItem(mission, type, eventAt(mission)));
                });
        return items.stream().sorted(byTimeDescending()).limit(limit).toList();
    }

    private NotificationItemResponse depositPaid(Payment payment) {
        Order order = payment.getInvoice().getOrder();
        String reference = order.getOrderCode() == null || order.getOrderCode().isBlank()
                ? order.getId() : order.getOrderCode();
        Instant timestamp = payment.getPaidAt() != null
                ? payment.getPaidAt()
                : payment.getUpdatedAt() != null ? payment.getUpdatedAt() : payment.getCreatedAt();
        return new NotificationItemResponse(
                "payment:" + payment.getId() + ":order_deposit_paid",
                NotificationType.ORDER_DEPOSIT_PAID,
                order.getId(),
                payment.getInvoice().getId(),
                reference,
                timestamp);
    }

    private List<NotificationItemResponse> staffItems(String staffId, int limit) {
        List<NotificationItemResponse> items = new ArrayList<>();
        missions.findCurrentAssignmentsForStaff(
                        staffId, STAFF_MISSION_STATUSES, PageRequest.of(0, limit))
                .forEach(mission -> {
                    NotificationType type = switch (mission.getStatus()) {
                        case COMPLETED -> NotificationType.MISSION_COMPLETED;
                        case FAILED -> NotificationType.MISSION_WARNING;
                        default -> NotificationType.MISSION_ASSIGNED;
                    };
                    Instant timestamp = mission.getStatus() == MissionStatus.COMPLETED
                            ? completedAt(mission) : eventAt(mission);
                    items.add(missionItem(mission, type, timestamp));
                });
        deviceAssignments.findRecentReplacementsForStaff(staffId, PageRequest.of(0, limit))
                .forEach(previous -> {
                    var mission = previous.getMission();
                    var replacement = previous.getReplacementAssignment();
                    var device = replacement.getDevice();
                    items.add(new NotificationItemResponse(
                            "mission-drone-replaced:" + replacement.getId(),
                            NotificationType.MISSION_DRONE_REPLACED,
                            mission.getId(), mission.getOrder().getId(),
                            mission.getMissionCode(), previous.getReleasedAt(),
                            device.getDeviceCode() != null ? device.getDeviceCode() : device.getId()));
                });
        return items.stream().sorted(byTimeDescending()).limit(limit).toList();
    }

    private NotificationItemResponse orderStatusChanged(Order order) {
        String reference = order.getOrderCode() == null || order.getOrderCode().isBlank()
                ? order.getId() : order.getOrderCode();
        NotificationType type = switch (order.getOrderStatus()) {
            case PENDING -> NotificationType.ORDER_CREATED;
            case UNDER_REVIEW -> NotificationType.ORDER_UNDER_REVIEW;
            case NEEDS_INFO -> NotificationType.ORDER_NEEDS_INFO;
            case APPROVED -> NotificationType.ORDER_APPROVED;
            case REJECTED -> NotificationType.ORDER_REJECTED;
            case IN_PROGRESS -> NotificationType.ORDER_IN_PROGRESS;
            case COMPLETED -> NotificationType.ORDER_COMPLETED;
            case CANCELLED -> NotificationType.ORDER_CANCELLED;
        };
        Instant timestamp = order.getOrderStatus() == OrderStatus.PENDING
                ? order.getCreatedAt() : eventAt(order);
        return new NotificationItemResponse(
                "order:" + order.getId() + ":" + type.name().toLowerCase(),
                type,
                order.getId(),
                null,
                reference,
                timestamp);
    }

    private NotificationItemResponse missionItem(
            Mission mission, NotificationType type, Instant timestamp) {
        String reference = mission.getMissionCode() == null || mission.getMissionCode().isBlank()
                ? mission.getId() : mission.getMissionCode();
        return new NotificationItemResponse(
                "mission:" + mission.getId() + ":" + type.name().toLowerCase(),
                type,
                mission.getId(),
                mission.getOrder().getId(),
                reference,
                timestamp);
    }

    private Instant completedAt(Mission mission) {
        return mission.getCompletedAt() != null ? mission.getCompletedAt() : eventAt(mission);
    }

    private Instant eventAt(Mission mission) {
        return mission.getUpdatedAt() != null ? mission.getUpdatedAt() : mission.getCreatedAt();
    }

    private Instant eventAt(Order order) {
        if ((order.getOrderStatus() == OrderStatus.APPROVED
                || order.getOrderStatus() == OrderStatus.REJECTED)
                && order.getReviewAt() != null) {
            return order.getReviewAt();
        }
        return order.getUpdatedAt() != null ? order.getUpdatedAt() : order.getCreatedAt();
    }

    private Comparator<NotificationItemResponse> byTimeDescending() {
        return Comparator.comparing(NotificationItemResponse::createdAt,
                Comparator.nullsLast(Comparator.naturalOrder())).reversed();
    }
}
