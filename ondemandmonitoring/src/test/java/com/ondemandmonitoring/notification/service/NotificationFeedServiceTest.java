package com.ondemandmonitoring.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.finance.repository.PaymentRepository;
import com.ondemandmonitoring.mission.repository.MissionStaffAssignmentRepository;
import com.ondemandmonitoring.notification.dto.NotificationType;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.enums.OrderStatus;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class NotificationFeedServiceTest {

    @Mock
    private AuthenticatedUserResolver currentUser;
    @Mock
    private OrderRepository orders;
    @Mock
    private MissionRepository missions;
    @Mock
    private PaymentRepository payments;
    @Mock
    private MissionStaffAssignmentRepository staffAssignments;
    @InjectMocks
    private NotificationFeedService service;

    @Test
    void staffFeedOnlyQueriesMissionsWithCurrentAssignments() {
        when(currentUser.getCurrentUser()).thenReturn(user("staff-1", RoleCode.STAFF));
        Mission mission = mission("mission-1", "MS-1", MissionStatus.READY);
        when(missions.findCurrentAssignmentsForStaff(eq("staff-1"), any(), any(Pageable.class)))
                .thenReturn(List.of(mission));

        var response = service.listForCurrentUser(30);

        assertEquals(1, response.items().size());
        assertEquals(NotificationType.MISSION_ASSIGNED, response.items().get(0).type());
        assertEquals("mission-1", response.items().get(0).entityId());
        verify(missions).findCurrentAssignmentsForStaff(eq("staff-1"), any(), any(Pageable.class));
    }

    @Test
    void managerFeedMapsAwaitingReviewAndFailureToManagerActionTypes() {
        when(currentUser.getCurrentUser()).thenReturn(user("manager-1", RoleCode.MANAGER));
        Mission review = mission("mission-1", "MS-1", MissionStatus.AWAITING_REVIEW);
        Mission failed = mission("mission-2", "MS-2", MissionStatus.FAILED);
        when(orders.findByOrderStatusInOrderByCreatedAtDesc(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.<Order>of()));
        when(missions.findByStatusInOrderByUpdatedAtDesc(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(review, failed)));

        var response = service.listForCurrentUser(30);

        assertEquals(2, response.items().size());
        assertEquals(NotificationType.MISSION_REVIEW_REQUIRED, response.items().get(0).type());
        assertEquals(NotificationType.MISSION_WARNING, response.items().get(1).type());
    }

    @Test
    void customerFeedShowsTheLatestOrderApprovalStatus() {
        when(currentUser.getCurrentUser()).thenReturn(user("customer-1", RoleCode.CUSTOMER));
        Order order = new Order();
        order.setId("order-1");
        order.setOrderCode("ORD-1");
        order.setOrderStatus(OrderStatus.APPROVED);
        order.setCreatedAt(Instant.parse("2026-11-12T09:00:00Z"));
        order.setUpdatedAt(Instant.parse("2026-11-12T10:00:00Z"));
        order.setReviewAt(Instant.parse("2026-11-12T09:59:00Z"));
        when(orders.findByCustomer_IdOrderByCreatedAtDesc(eq("customer-1"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(order)));
        when(missions.findByOrder_Customer_IdAndStatusIn(eq("customer-1"), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        var response = service.listForCurrentUser(30);

        assertEquals(1, response.items().size());
        assertEquals(NotificationType.ORDER_APPROVED, response.items().get(0).type());
        assertEquals("order-1", response.items().get(0).entityId());
        assertEquals(Instant.parse("2026-11-12T09:59:00Z"), response.items().get(0).createdAt());
    }

    @Test
    void adminReceivesTheManagerOperationalFeed() {
        when(currentUser.getCurrentUser()).thenReturn(user("admin-1", RoleCode.ADMIN));
        when(orders.findByOrderStatusInOrderByCreatedAtDesc(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(missions.findByStatusInOrderByUpdatedAtDesc(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        var response = service.listForCurrentUser(30);

        assertEquals(0, response.items().size());
        verify(orders).findByOrderStatusInOrderByCreatedAtDesc(any(), any(Pageable.class));
        verify(missions).findByStatusInOrderByUpdatedAtDesc(any(), any(Pageable.class));
    }

    private static User user(String id, RoleCode code) {
        User user = new User();
        user.setId(id);
        Role role = new Role();
        role.setCode(code);
        user.setRole(role);
        return user;
    }

    private static Mission mission(String id, String code, MissionStatus status) {
        Mission mission = new Mission();
        mission.setId(id);
        mission.setMissionCode(code);
        mission.setStatus(status);
        mission.setCreatedAt(Instant.parse("2026-11-12T10:00:00Z"));
        mission.setUpdatedAt(Instant.parse("2026-11-12T10:00:00Z"));
        Order order = new Order();
        order.setId("order-1");
        mission.setOrder(order);
        return mission;
    }
}
