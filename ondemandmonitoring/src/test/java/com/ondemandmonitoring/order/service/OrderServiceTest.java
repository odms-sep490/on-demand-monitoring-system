package com.ondemandmonitoring.order.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.dto.request.OrderCreateRequest;
import com.ondemandmonitoring.order.dto.request.OrderDeliverableRequest;
import com.ondemandmonitoring.order.dto.response.OrderCreateResponse;
import com.ondemandmonitoring.order.enums.MonitoringRelationship;
import com.ondemandmonitoring.order.enums.OrderStatus;
import com.ondemandmonitoring.order.mapper.OrderMapper;
import org.mapstruct.factory.Mappers;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.order.service.impl.OrderService;
import com.ondemandmonitoring.order.service.IOrderChecklistSnapshotService;
import com.ondemandmonitoring.review.service.OrderReviewRecordInitializer;
import com.ondemandmonitoring.service.domain.DeliverableType;
import com.ondemandmonitoring.service.domain.Service;
import com.ondemandmonitoring.service.repository.DeliverableTypeRepository;
import com.ondemandmonitoring.service.repository.ServiceDeliverableRepository;
import com.ondemandmonitoring.service.repository.ServiceRepository;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import com.ondemandmonitoring.warehouse.domain.PreferredTime;
import com.ondemandmonitoring.warehouse.repository.PreferredTimeRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OrderServiceTest {

    private OrderRepository orderRepository;
    private ServiceRepository serviceRepository;
    private DeliverableTypeRepository deliverableTypeRepository;
    private ServiceDeliverableRepository serviceDeliverableRepository;
    private PreferredTimeRepository preferredTimeRepository;
    private AuthenticatedUserResolver authenticatedUserResolver;
    private OrderMapper orderMapper;
    private OrderService orderService;
    private IOrderChecklistSnapshotService checklistSnapshots;
    private com.ondemandmonitoring.finance.repository.QuoteRepository quotes;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        serviceRepository = mock(ServiceRepository.class);
        deliverableTypeRepository = mock(DeliverableTypeRepository.class);
        serviceDeliverableRepository = mock(ServiceDeliverableRepository.class);
        preferredTimeRepository = mock(PreferredTimeRepository.class);
        authenticatedUserResolver = mock(AuthenticatedUserResolver.class);
        orderMapper = Mappers.getMapper(OrderMapper.class);
        checklistSnapshots = mock(IOrderChecklistSnapshotService.class);
        quotes = mock(com.ondemandmonitoring.finance.repository.QuoteRepository.class);

        orderService = new OrderService(
                orderRepository,
                serviceRepository,
                deliverableTypeRepository,
                serviceDeliverableRepository,
                preferredTimeRepository,
                authenticatedUserResolver,
                mock(com.ondemandmonitoring.user.repository.CustomerProfileRepository.class),
                orderMapper,
                checklistSnapshots,
                mock(OrderReviewRecordInitializer.class),
                quotes,
                mock(com.ondemandmonitoring.finance.repository.InvoiceRepository.class)
        );

        String userId = UUID.randomUUID().toString();
        User mockUser = User.builder().fullName("Test Customer").email("test@example.com").build();
        mockUser.setId(userId);
        when(authenticatedUserResolver.getCurrentUser()).thenReturn(mockUser);
    }

    private Map<String, Object> sampleCoverageAreaMap() {
        return Map.of(
                "type", "Polygon",
                "coordinates", List.of(List.of(
                        List.of(106.7000, 10.7760),
                        List.of(106.7010, 10.7760),
                        List.of(106.7010, 10.7770),
                        List.of(106.7000, 10.7770),
                        List.of(106.7000, 10.7760)
                ))
        );
    }

    @Test
    void orderGetAndCustomerListsNeverCompleteOrSaveOrders() {
        Order order = Order.builder().orderStatus(OrderStatus.APPROVED).build();
        order.setId("order-read");
        String customer = authenticatedUserResolver.getCurrentUser().getId();
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(orderRepository.findByCustomer_IdOrderByCreatedAtDesc(customer)).thenReturn(List.of(order));
        when(orderRepository.findByCustomer_IdAndOrderStatusOrderByCreatedAtDesc(customer, OrderStatus.APPROVED))
                .thenReturn(List.of(order));
        assertEquals(OrderStatus.APPROVED, orderService.getOrderById(order.getId()).getOrderStatus());
        assertEquals(OrderStatus.APPROVED, orderService.getMyOrders(null).getFirst().getOrderStatus());
        assertEquals(OrderStatus.APPROVED, orderService.getMyOrders(OrderStatus.APPROVED).getFirst().getOrderStatus());
        assertEquals(OrderStatus.APPROVED, order.getOrderStatus());
        verify(orderRepository, never()).save(any());
        verify(orderRepository, never()).saveAndFlush(any());
    }

    @Test
    void queryTransactionsAreExplicitlyReadOnly() throws Exception {
        assertEquals(true, OrderService.class.getMethod("getOrderById", String.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class).readOnly());
        assertEquals(true, OrderService.class.getMethod("getMyOrders", OrderStatus.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class).readOnly());
    }

    @Test
    void customerOrderUsesPublishedQuoteGuidanceInsteadOfManagerDraft() {
        Order order = Order.builder().orderStatus(OrderStatus.APPROVED).build();
        order.setId("guidance-order"); order.setChecklistRevision(2);
        var item = new com.ondemandmonitoring.order.dto.response.CustomerOrderChecklistItemResponse();
        item.setId("custom"); item.setChecklistRevision(2);
        item.setSourceType(com.ondemandmonitoring.order.enums.OrderChecklistSourceType.CUSTOMER_CUSTOM);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(checklistSnapshots.getByOrders(List.of(order.getId())))
                .thenReturn(Map.of(order.getId(), List.of(item)));
        var quote = new com.ondemandmonitoring.finance.domain.Quote();
        quote.setOrder(order); quote.setChecklistRevision(2);
        quote.setStatus(com.ondemandmonitoring.finance.enums.QuoteStatus.APPROVED);
        quote.setChecklistGuidanceSnapshot(new com.ondemandmonitoring.finance.dto.ChecklistGuidanceSnapshot(1,
                List.of(new com.ondemandmonitoring.finance.dto.ChecklistGuidanceSnapshot.Item(
                        "custom", 2, 3, "Published scope", List.of("Step"), List.of("Input"), List.of("Output")))));
        when(quotes.findGuidanceQuotesForOrders(any(), any())).thenReturn(List.of(quote));

        var result = orderService.getOrderById(order.getId()).getChecklistItems().getFirst();
        assertEquals("Published scope", result.getDescription());
        assertEquals(List.of("Step"), result.getExecutionSteps());
        assertEquals(3, result.getGuidanceVersion());
        assertEquals(true, result.isGuidanceReady());
    }

    @Test
    void customerOrderDoesNotPublishGuidanceOfStaleChecklistRevision() {
        Order order = Order.builder().orderStatus(OrderStatus.APPROVED).build();
        order.setId("changed-order"); order.setChecklistRevision(3);
        var item = new com.ondemandmonitoring.order.dto.response.CustomerOrderChecklistItemResponse();
        item.setId("custom"); item.setChecklistRevision(3);
        item.setSourceType(com.ondemandmonitoring.order.enums.OrderChecklistSourceType.CUSTOMER_CUSTOM);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(checklistSnapshots.getByOrders(List.of(order.getId())))
                .thenReturn(Map.of(order.getId(), List.of(item)));
        var quote = new com.ondemandmonitoring.finance.domain.Quote();
        quote.setOrder(order); quote.setChecklistRevision(2);
        quote.setStatus(com.ondemandmonitoring.finance.enums.QuoteStatus.APPROVED);
        quote.setChecklistGuidanceSnapshot(new com.ondemandmonitoring.finance.dto.ChecklistGuidanceSnapshot(1,
                List.of(new com.ondemandmonitoring.finance.dto.ChecklistGuidanceSnapshot.Item(
                        "custom", 2, 3, "Old scope", List.of("Step"), List.of("Input"), List.of("Output")))));
        when(quotes.findGuidanceQuotesForOrders(any(), any())).thenReturn(List.of(quote));

        var result = orderService.getOrderById(order.getId()).getChecklistItems().getFirst();
        assertEquals(null, result.getDescription());
        assertEquals(false, result.isGuidanceReady());
    }

    @Test
    void createOrder_Success() {
        // Arrange
        Service service = Service.builder().name("Land Monitoring")
                .basePrice(new java.math.BigDecimal("2800000")).build();
        service.setId("srv-1");
        PreferredTime preferredTime = PreferredTime.builder().name("Morning").build();
        preferredTime.setId("pt-1");
        DeliverableType delType = DeliverableType.builder().name("Photo Map").defaultFormat("JPEG").build();
        delType.setId("dt-1");

        when(serviceRepository.findByIdForUpdate("srv-1")).thenReturn(Optional.of(service));
        when(preferredTimeRepository.findById("pt-1")).thenReturn(Optional.of(preferredTime));
        when(deliverableTypeRepository.findById("dt-1")).thenReturn(Optional.of(delType));
        when(serviceDeliverableRepository.existsByServiceIdAndDeliverableTypeId("srv-1", "dt-1")).thenReturn(true);
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order o = invocation.getArgument(0);
            o.setId("ord-123");
            return o;
        });

        OrderDeliverableRequest delReq = OrderDeliverableRequest.builder()
                .deliverableTypeId("dt-1")
                .requirement(Map.of("resolution", "4K", "radiusM", 300))
                .build();

        OrderCreateRequest request = OrderCreateRequest.builder()
                .title("Survey Forest")
                .termsAccepted(true)
                .dataRetentionDays(90)
                .monitoringRelationship(MonitoringRelationship.OWNER)
                .monitoringAuthorizationConfirmed(true)
                .serviceId("srv-1")
                .preferredTimeId("pt-1")
                .preferredDateFrom(LocalDate.now().plusDays(7))
                .preferredDateTo(LocalDate.now().plusDays(9))
                .longitude(106.7005)
                .latitude(10.7765)
                .coverageArea(sampleCoverageAreaMap())
                .deliverables(List.of(delReq))
                .build();

        // Act
        OrderCreateResponse response = orderService.createOrder(request);

        // Assert
        assertNotNull(response);
        assertEquals("ord-123", response.getId());
        assertEquals("Survey Forest", response.getTitle());
        assertEquals(OrderStatus.PENDING, response.getOrderStatus());
        org.junit.jupiter.api.Assertions.assertNull(response.getServiceBasePriceSnapshot());
        assertEquals(106.7005, response.getLongitude());
        assertEquals(10.7765, response.getLatitude());
        assertEquals(300.0, response.getRadiusM());
        assertNotNull(response.getDeliverables());
        assertEquals(1, response.getDeliverables().size());
        assertEquals("dt-1", response.getDeliverables().get(0).getDeliverableTypeId());
    }

    @Test
    void approveOrder_ReturnsApprovedOrderWhenAlreadyApproved() {
        Order order = Order.builder()
                .title("Already approved")
                .orderStatus(OrderStatus.APPROVED)
                .build();
        order.setId("ord-approved");

        when(orderRepository.findById("ord-approved")).thenReturn(Optional.of(order));

        OrderCreateResponse response = orderService.approveOrder("ord-approved");

        assertNotNull(response);
        assertEquals("ord-approved", response.getId());
        assertEquals(OrderStatus.APPROVED, response.getOrderStatus());
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void createOrder_ThrowsWhenGpsCoordinatesInvalid() {
        Service service = Service.builder().name("Land Monitoring").build();
        service.setId("srv-1");
        PreferredTime preferredTime = PreferredTime.builder().name("Morning").build();
        preferredTime.setId("pt-1");
        DeliverableType delType = DeliverableType.builder().name("Photo Map").build();
        delType.setId("dt-1");

        when(serviceRepository.findByIdForUpdate("srv-1")).thenReturn(Optional.of(service));
        when(preferredTimeRepository.findById("pt-1")).thenReturn(Optional.of(preferredTime));
        when(deliverableTypeRepository.findById("dt-1")).thenReturn(Optional.of(delType));
        when(serviceDeliverableRepository.existsByServiceIdAndDeliverableTypeId("srv-1", "dt-1")).thenReturn(true);

        OrderCreateRequest request = OrderCreateRequest.builder()
                .title("Outside Point")
                .termsAccepted(true)
                .dataRetentionDays(90)
                .monitoringRelationship(MonitoringRelationship.OWNER)
                .monitoringAuthorizationConfirmed(true)
                .serviceId("srv-1")
                .preferredTimeId("pt-1")
                .preferredDateFrom(LocalDate.now().plusDays(7))
                .preferredDateTo(LocalDate.now().plusDays(9))
                .longitude(106.7)
                .latitude(121.0)
                .coverageArea(sampleCoverageAreaMap())
                .deliverables(List.of(OrderDeliverableRequest.builder().deliverableTypeId("dt-1").requirement(Map.of()).build()))
                .build();

        assertThrows(ApiException.class, () -> orderService.createOrder(request));
    }

    private OrderCreateRequest airportRequest(com.ondemandmonitoring.order.enums.OrderPermitStatus status, String number) {
        return airportRequest(status, number, null, null);
    }

    private static com.ondemandmonitoring.order.dto.OrderPermitDocument permitFile() {
        return new com.ondemandmonitoring.order.dto.OrderPermitDocument(
                "p1", "permit.pdf", "application/pdf", 1024, "data:application/pdf;base64,AAAA");
    }

    private OrderCreateRequest airportRequest(com.ondemandmonitoring.order.enums.OrderPermitStatus status, String number,
            List<com.ondemandmonitoring.order.dto.OrderPermitDocument> documents, LocalDate expiresOn) {
        Service service = Service.builder().name("Airport Check").build();
        service.setId("srv-1");
        PreferredTime preferredTime = PreferredTime.builder().name("Morning").build();
        preferredTime.setId("pt-1");
        DeliverableType delType = DeliverableType.builder().name("Photo Map").build();
        delType.setId("dt-1");
        when(serviceRepository.findByIdForUpdate("srv-1")).thenReturn(Optional.of(service));
        when(preferredTimeRepository.findById("pt-1")).thenReturn(Optional.of(preferredTime));
        when(deliverableTypeRepository.findById("dt-1")).thenReturn(Optional.of(delType));
        when(serviceDeliverableRepository.existsByServiceIdAndDeliverableTypeId("srv-1", "dt-1")).thenReturn(true);
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order o = invocation.getArgument(0);
            o.setId("ord-airport");
            return o;
        });
        Map<String, Object> area = Map.of(
                "type", "Polygon",
                "coordinates", List.of(List.of(
                        List.of(106.6600, 10.8150),
                        List.of(106.6610, 10.8150),
                        List.of(106.6610, 10.8160),
                        List.of(106.6600, 10.8160),
                        List.of(106.6600, 10.8150))));
        return OrderCreateRequest.builder()
                .title("Runway check")
                .termsAccepted(true)
                .dataRetentionDays(90)
                .monitoringRelationship(MonitoringRelationship.OWNER)
                .monitoringAuthorizationConfirmed(true)
                .serviceId("srv-1")
                .preferredTimeId("pt-1")
                .preferredDateFrom(LocalDate.now().plusDays(7))
                .preferredDateTo(LocalDate.now().plusDays(8))
                .longitude(106.6605)
                .latitude(10.8155)
                .coverageArea(area)
                .permitStatus(status)
                .permitNumber(number)
                .permitDocuments(documents)
                .permitExpiresOn(expiresOn)
                .deliverables(List.of(OrderDeliverableRequest.builder().deliverableTypeId("dt-1").requirement(Map.of()).build()))
                .build();
    }

    @Test
    void createOrder_HavePermitNeedsTheFile() {
        var request = airportRequest(com.ondemandmonitoring.order.enums.OrderPermitStatus.HAVE_PERMIT, "GP-1",
                null, LocalDate.now().plusDays(30));
        assertThrows(ApiException.class, () -> orderService.createOrder(request));
    }

    @Test
    void createOrder_HavePermitNeedsAnExpiryDate() {
        var request = airportRequest(com.ondemandmonitoring.order.enums.OrderPermitStatus.HAVE_PERMIT, "GP-1",
                List.of(permitFile()), null);
        assertThrows(ApiException.class, () -> orderService.createOrder(request));
    }

    @Test
    void createOrder_PermitExpiringBeforeTheFlightIsRejected() {
        // The request flies on today+8; the permit ends today.
        var request = airportRequest(com.ondemandmonitoring.order.enums.OrderPermitStatus.HAVE_PERMIT, "GP-1",
                List.of(permitFile()), LocalDate.now());
        assertThrows(ApiException.class, () -> orderService.createOrder(request));
    }

    @Test
    void createOrder_PermitFileMustBePdfOrImage() {
        var bad = new com.ondemandmonitoring.order.dto.OrderPermitDocument(
                "p1", "permit.exe", "application/x-msdownload", 1024, "data:application/x-msdownload;base64,AAAA");
        var request = airportRequest(com.ondemandmonitoring.order.enums.OrderPermitStatus.HAVE_PERMIT, "GP-1",
                List.of(bad), LocalDate.now().plusDays(30));
        assertThrows(ApiException.class, () -> orderService.createOrder(request));
    }

    @Test
    void createOrder_HavePermitWithFileAndValidityIsAcceptedAndStored() {
        OrderCreateResponse response = orderService.createOrder(airportRequest(
                com.ondemandmonitoring.order.enums.OrderPermitStatus.HAVE_PERMIT, "GP-1",
                List.of(permitFile()), LocalDate.now().plusDays(30)));
        assertEquals(1, response.getPermitDocuments().size());
        assertEquals(LocalDate.now().plusDays(30), response.getPermitExpiresOn());
    }

    @Test
    void createOrder_DoesNotInferPermitRequirementFromCoordinates() {
        OrderCreateRequest request = airportRequest(null, null);
        OrderCreateResponse response = orderService.createOrder(request);
        assertEquals(Boolean.FALSE, response.getPermitRequired());
        assertEquals(null, response.getPermitZoneName());
    }

    @Test
    void createOrder_NearAirportWithPermitNeedsNumber() {
        OrderCreateRequest request = airportRequest(com.ondemandmonitoring.order.enums.OrderPermitStatus.HAVE_PERMIT, " ");
        assertThrows(ApiException.class, () -> orderService.createOrder(request));
    }

    @Test
    void createOrder_NearAirportWithSupportRequestIsAcceptedAndFlagged() {
        OrderCreateResponse response = orderService.createOrder(
                airportRequest(com.ondemandmonitoring.order.enums.OrderPermitStatus.NEED_SUPPORT, null));
        assertEquals(Boolean.TRUE, response.getPermitRequired());
        assertEquals(null, response.getPermitZoneName());
        assertEquals(com.ondemandmonitoring.order.enums.OrderPermitStatus.NEED_SUPPORT, response.getPermitStatus());
    }

    /** A valid request away from the airport zone, with every mock the create flow needs. */
    private OrderCreateRequest.OrderCreateRequestBuilder deliveryRequest() {
        Service service = Service.builder().name("Land Monitoring").build();
        service.setId("srv-1");
        PreferredTime preferredTime = PreferredTime.builder().name("Morning").build();
        preferredTime.setId("pt-1");
        DeliverableType delType = DeliverableType.builder().name("Photo Map").defaultFormat("JPEG").build();
        delType.setId("dt-1");
        when(serviceRepository.findByIdForUpdate("srv-1")).thenReturn(Optional.of(service));
        when(preferredTimeRepository.findById("pt-1")).thenReturn(Optional.of(preferredTime));
        when(deliverableTypeRepository.findById("dt-1")).thenReturn(Optional.of(delType));
        when(serviceDeliverableRepository.existsByServiceIdAndDeliverableTypeId("srv-1", "dt-1")).thenReturn(true);
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order o = invocation.getArgument(0);
            o.setId("ord-delivery");
            return o;
        });
        return OrderCreateRequest.builder()
                .title("Delivery options")
                .termsAccepted(true)
                .dataRetentionDays(90)
                .monitoringRelationship(MonitoringRelationship.OWNER)
                .monitoringAuthorizationConfirmed(true)
                .serviceId("srv-1")
                .preferredTimeId("pt-1")
                .preferredDateFrom(LocalDate.now().plusDays(7))
                .preferredDateTo(LocalDate.now().plusDays(9))
                .longitude(106.7005)
                .latitude(10.7765)
                .coverageArea(sampleCoverageAreaMap())
                .deliverables(List.of(OrderDeliverableRequest.builder().deliverableTypeId("dt-1").requirement(Map.of()).build()));
    }

    @Test
    void createOrder_RejectsWhenTermsAreNotAccepted() {
        OrderCreateRequest missing = deliveryRequest().termsAccepted(null).build();
        OrderCreateRequest declined = deliveryRequest().termsAccepted(false).build();
        assertThrows(ApiException.class, () -> orderService.createOrder(missing));
        assertThrows(ApiException.class, () -> orderService.createOrder(declined));
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void createOrder_RejectsFlightScheduledWithLessThanSevenDaysNotice() {
        OrderCreateRequest request = deliveryRequest()
                .preferredDateFrom(LocalDate.now().plusDays(6))
                .preferredDateTo(LocalDate.now().plusDays(8))
                .build();

        assertThrows(ApiException.class, () -> orderService.createOrder(request));
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void createOrder_RejectsUnsupportedRetentionPeriod() {
        OrderCreateRequest request = deliveryRequest().dataRetentionDays(45).build();
        assertThrows(ApiException.class, () -> orderService.createOrder(request));
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void createOrder_FlightPurposeDefaultsToSurveyAndMonitoring() {
        OrderCreateResponse response = orderService.createOrder(deliveryRequest().build());
        assertEquals(com.ondemandmonitoring.order.enums.OrderFlightPurpose.SURVEY_MONITORING, response.getFlightPurpose());
    }

    @Test
    void createOrder_KeepsTheFlightPurposeTheCustomerChose() {
        OrderCreateResponse response = orderService.createOrder(deliveryRequest()
                .flightPurpose(com.ondemandmonitoring.order.enums.OrderFlightPurpose.AGRICULTURE).build());
        assertEquals(com.ondemandmonitoring.order.enums.OrderFlightPurpose.AGRICULTURE, response.getFlightPurpose());
    }

    @Test
    void createOrder_DefaultsDeliveryOptionsAndRecordsAcceptance() {
        OrderCreateResponse response = orderService.createOrder(deliveryRequest().build());
        assertEquals(List.of(com.ondemandmonitoring.order.enums.OrderResultFormat.PHOTO), response.getResultFormats());
        assertEquals(List.of(com.ondemandmonitoring.order.enums.OrderDeliveryMethod.DOWNLOAD), response.getDeliveryMethods());
        assertEquals(90, response.getDataRetentionDays());
        assertNotNull(response.getTermsAcceptedAt());
        assertEquals("2026-10", response.getTermsVersion());
    }

    @Test
    void createOrder_KeepsChosenDeliveryOptionsWithoutDuplicates() {
        var pdf = com.ondemandmonitoring.order.enums.OrderResultFormat.PDF_REPORT;
        var photo = com.ondemandmonitoring.order.enums.OrderResultFormat.PHOTO;
        var email = com.ondemandmonitoring.order.enums.OrderDeliveryMethod.EMAIL;
        var api = com.ondemandmonitoring.order.enums.OrderDeliveryMethod.API;
        OrderCreateResponse response = orderService.createOrder(deliveryRequest()
                .resultFormats(List.of(pdf, photo, pdf))
                .deliveryMethods(List.of(email, api))
                .dataRetentionDays(180)
                .build());
        assertEquals(List.of(pdf, photo), response.getResultFormats());
        assertEquals(List.of(email, api), response.getDeliveryMethods());
        assertEquals(180, response.getDataRetentionDays());
    }
}
