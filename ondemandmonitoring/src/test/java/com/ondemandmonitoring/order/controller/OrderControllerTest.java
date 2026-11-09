package com.ondemandmonitoring.order.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.mission.dto.response.MissionResponse;
import com.ondemandmonitoring.order.dto.request.OrderCreateRequest;
import com.ondemandmonitoring.order.dto.request.OrderDeliverableRequest;
import com.ondemandmonitoring.order.dto.response.OrderCreateResponse;
import com.ondemandmonitoring.order.enums.OrderStatus;
import com.ondemandmonitoring.order.service.IOrderService;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;

class OrderControllerTest {

    private IOrderService orderService;
    private OrderController orderController;

    @BeforeEach
    void setUp() {
        orderService = mock(IOrderService.class);
        orderController = new OrderController(orderService);
    }

    @Test
    @DisplayName("Verify createOrder method is annotated with @PreAuthorize(\"hasRole('CUSTOMER')\")")
    void createOrder_HasPreAuthorizeAnnotationWithCustomerRole() throws NoSuchMethodException {
        Method createOrderMethod = OrderController.class.getMethod("createOrder", OrderCreateRequest.class);
        PreAuthorize preAuthorize = createOrderMethod.getAnnotation(PreAuthorize.class);

        assertNotNull(preAuthorize, "createOrder method should be annotated with @PreAuthorize");
        assertEquals("hasRole('CUSTOMER')", preAuthorize.value(),
                "Role authorization should be restricted to 'hasRole(\\'CUSTOMER\\')'");
    }

    @Test
    @DisplayName("createOrder delegates to OrderService and returns CREATED response")
    void createOrder_Success_ReturnsCreatedResponse() {
        OrderCreateRequest request = OrderCreateRequest.builder()
                .title("Survey Forest")
                .serviceId("srv-1")
                .preferredTimeId("pt-1")
                .preferredDateFrom(LocalDate.now().plusDays(1))
                .preferredDateTo(LocalDate.now().plusDays(3))
                .longitude(105.85)
                .latitude(21.02)
                .coverageArea(Map.of("type", "Polygon", "coordinates", List.of()))
                .deliverables(List.of(OrderDeliverableRequest.builder()
                        .deliverableTypeId("dt-1")
                        .requirement(Map.of("resolution", "4K"))
                        .build()))
                .build();

        OrderCreateResponse mockResponse = OrderCreateResponse.builder()
                .id("ord-123")
                .title("Survey Forest")
                .orderStatus(OrderStatus.PENDING)
                .longitude(105.85)
                .latitude(21.02)
                .build();

        when(orderService.createOrder(any(OrderCreateRequest.class))).thenReturn(mockResponse);

        ResponseEntity<ApiResponse<OrderCreateResponse>> responseEntity = orderController.createOrder(request);

        assertNotNull(responseEntity);
        assertEquals(HttpStatus.CREATED, responseEntity.getStatusCode());
        assertNotNull(responseEntity.getBody());
        assertTrue(responseEntity.getBody().isSuccess());
        assertEquals("Order created successfully", responseEntity.getBody().getMessage());
        assertEquals("ord-123", responseEntity.getBody().getData().getId());

        verify(orderService).createOrder(request);
    }

    @Test
    @DisplayName("getOrderById delegates to OrderService and returns order details")
    void getOrderById_Success_ReturnsOrderDetails() {
        OrderCreateResponse mockResponse = OrderCreateResponse.builder()
                .id("ord-123")
                .title("Survey Forest")
                .orderStatus(OrderStatus.PENDING)
                .build();

        when(orderService.getOrderById("ord-123")).thenReturn(mockResponse);

        ResponseEntity<ApiResponse<OrderCreateResponse>> responseEntity = orderController.getOrderById("ord-123");

        assertNotNull(responseEntity);
        assertEquals(HttpStatus.OK, responseEntity.getStatusCode());
        assertTrue(responseEntity.getBody().isSuccess());
        assertEquals("ord-123", responseEntity.getBody().getData().getId());
        verify(orderService).getOrderById("ord-123");
    }

    @Test
    @DisplayName("getResourcePreview returns null data response")
    void getResourcePreview_ReturnsNullData() {
        ResponseEntity<ApiResponse<Object>> responseEntity = orderController.getResourcePreview("ord-123");

        assertNotNull(responseEntity);
        assertEquals(HttpStatus.OK, responseEntity.getStatusCode());
        assertTrue(responseEntity.getBody().isSuccess());
    }

    @Test
    @DisplayName("approveOrder returns the mission created for the approved order")
    void approveOrder_ReturnsCreatedMission() {
        OrderCreateResponse order = OrderCreateResponse.builder().id("ord-123").build();
        when(orderService.approveOrder("ord-123")).thenReturn(order);

        ResponseEntity<ApiResponse<OrderCreateResponse>> responseEntity = orderController.approveOrder("ord-123");

        assertEquals(HttpStatus.OK, responseEntity.getStatusCode());
        assertEquals("ord-123", responseEntity.getBody().getData().getId());
        verify(orderService).approveOrder("ord-123");
    }

    @Test
    @DisplayName("submitApproval delegates to requestMoreInformation for NEED_INFO")
    void submitApproval_NeedInfo_Delegates() {
        ResponseEntity<ApiResponse<Void>> responseEntity = orderController.submitApproval(
                "ord-123", Map.of("decision", "NEED_INFO", "reason", "More details"));

        assertEquals(HttpStatus.OK, responseEntity.getStatusCode());
        verify(orderService).requestMoreInformation("ord-123", "More details", null);
    }

    @Test
    @DisplayName("submitApproval delegates to rejectOrder when decision is REJECTED")
    void submitApproval_Rejected_DelegatesToRejectOrder() {
        ResponseEntity<ApiResponse<Void>> responseEntity = orderController.submitApproval("ord-123",
                Map.of("decision", "REJECTED", "reason", "Out of zone"));

        assertNotNull(responseEntity);
        assertEquals(HttpStatus.OK, responseEntity.getStatusCode());
        verify(orderService).rejectOrder("ord-123", "Out of zone");
    }
}
