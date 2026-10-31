package com.ondemandmonitoring.order.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.finance.dto.ChecklistGuidanceSnapshot;
import com.ondemandmonitoring.finance.enums.QuoteStatus;
import com.ondemandmonitoring.order.enums.OrderChecklistSourceType;
import java.util.Map;
import java.util.HashMap;
import java.util.stream.Collectors;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.domain.OrderDeliverable;
import com.ondemandmonitoring.order.dto.request.OrderCreateRequest;
import com.ondemandmonitoring.order.dto.request.OrderDeliverableRequest;
import com.ondemandmonitoring.order.dto.request.FlightAreaReviewRequest;
import com.ondemandmonitoring.order.dto.request.LegalReviewResolutionRequest;
import com.ondemandmonitoring.order.dto.request.PermitCancellationProposalRequest;
import com.ondemandmonitoring.order.dto.OrderAuthorizationDocument;
import com.ondemandmonitoring.order.dto.response.OrderCreateResponse;
import com.ondemandmonitoring.order.enums.OrderDeliveryMethod;
import com.ondemandmonitoring.order.enums.OrderPermitStatus;
import com.ondemandmonitoring.order.enums.OrderRecurrenceType;
import com.ondemandmonitoring.order.enums.OrderResultFormat;
import com.ondemandmonitoring.order.enums.OrderWeatherFallback;
import com.ondemandmonitoring.order.enums.OrderStatus;
import com.ondemandmonitoring.order.enums.DocumentVerificationStatus;
import com.ondemandmonitoring.order.enums.LegalReviewStatus;
import com.ondemandmonitoring.order.mapper.OrderMapper;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.order.service.IOrderService;
import com.ondemandmonitoring.order.service.IOrderChecklistSnapshotService;
import com.ondemandmonitoring.order.util.GeoReader;
import com.ondemandmonitoring.order.util.ServiceAreaPolicy;
import com.ondemandmonitoring.review.service.OrderReviewRecordInitializer;
import com.ondemandmonitoring.service.domain.DeliverableType;
import com.ondemandmonitoring.service.domain.Service;
import com.ondemandmonitoring.service.repository.DeliverableTypeRepository;
import com.ondemandmonitoring.service.repository.ServiceDeliverableRepository;
import com.ondemandmonitoring.service.repository.ServiceRepository;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.repository.CustomerProfileRepository;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import com.ondemandmonitoring.warehouse.domain.PreferredTime;
import com.ondemandmonitoring.warehouse.repository.PreferredTimeRepository;
import java.util.ArrayList;
import java.util.List;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.prepost.PreAuthorize;

@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class OrderService implements IOrderService {

    private static final DateTimeFormatter ORDER_CODE_DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;
    private static final int MINIMUM_FLIGHT_NOTICE_DAYS = 7;

    OrderRepository orderRepository;
    ServiceRepository serviceRepository;
    DeliverableTypeRepository deliverableTypeRepository;
    ServiceDeliverableRepository serviceDeliverableRepository;
    PreferredTimeRepository preferredTimeRepository;
    AuthenticatedUserResolver authenticatedUserResolver;
    CustomerProfileRepository customerProfileRepository;
    OrderMapper orderMapper;
    IOrderChecklistSnapshotService checklistSnapshots;
    OrderReviewRecordInitializer reviewRecords;
    com.ondemandmonitoring.finance.repository.QuoteRepository quoteRepository;
    com.ondemandmonitoring.finance.repository.InvoiceRepository invoiceRepository;

    @Override
    @Transactional
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public OrderCreateResponse approveOrder(String orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId).or(() -> orderRepository.findById(orderId))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found: " + orderId));

        if (order.getOrderStatus() == OrderStatus.APPROVED) {
            return toResponse(order);
        }

        if (order.getOrderStatus() != OrderStatus.PENDING) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Only PENDING orders can be approved");
        }
        if (order.getLegalReviewStatus() != LegalReviewStatus.NOT_REQUIRED
                && order.getLegalReviewStatus() != LegalReviewStatus.APPROVED
                && order.getLegalReviewStatus() != LegalReviewStatus.PENDING_PERMIT) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Order legal review is not ready for approval");
        }

        order.setOrderStatus(OrderStatus.APPROVED);
        order.setReviewBy(authenticatedUserResolver.getCurrentUser());
        order.setReviewAt(Instant.now());
        orderRepository.save(order);

        return toResponse(order);
    }

    @Override
    @Transactional
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public OrderCreateResponse reviewFlightArea(String orderId, FlightAreaReviewRequest request) {
        Order order = orderRepository.findByIdForUpdate(orderId).or(() -> orderRepository.findById(orderId))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found: " + orderId));
        if (order.getOrderStatus() != OrderStatus.PENDING) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Only PENDING orders can be reviewed");
        }
        String reason = trimToNull(request.reason());
        String note = trimToNull(request.note());
        if (request.status() != LegalReviewStatus.NOT_REQUIRED
                && request.status() != LegalReviewStatus.PENDING_PERMIT
                && request.status() != LegalReviewStatus.REJECTED) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Initial legal review must be NOT_REQUIRED, PENDING_PERMIT, or REJECTED");
        }
        if (request.status() == LegalReviewStatus.REJECTED && reason == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "A reason is required for an unsuitable area");
        }
        order.setLegalReviewStatus(request.status());
        order.setFlightAreaReviewReason(reason);
        order.setFlightAreaReviewNote(note);
        if (request.requestedScopeFeasible() != null) {
            order.setRequestedScopeFeasible(request.requestedScopeFeasible());
        }
        order.setFlightAreaReviewedBy(authenticatedUserResolver.getCurrentUser());
        order.setFlightAreaReviewedAt(Instant.now());
        return toResponse(orderRepository.save(order));
    }

    @Override
    @Transactional
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public OrderCreateResponse resolveLegalReview(String orderId, LegalReviewResolutionRequest request) {
        Order order = orderRepository.findByIdForUpdate(orderId).or(() -> orderRepository.findById(orderId))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found: " + orderId));
        // The permit may still be pending after the order was approved and invoiced.
        if ((order.getOrderStatus() != OrderStatus.PENDING && order.getOrderStatus() != OrderStatus.APPROVED)
                || order.getLegalReviewStatus() != LegalReviewStatus.PENDING_PERMIT) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Only an order pending external permit verification can be resolved");
        }
        if (request.status() != LegalReviewStatus.APPROVED
                && request.status() != LegalReviewStatus.REJECTED) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Resolution must be APPROVED or REJECTED");
        }
        order.setLegalReviewStatus(request.status());
        order.setFlightAreaReviewNote(trimToNull(request.note()));
        order.setFlightAreaReviewedBy(authenticatedUserResolver.getCurrentUser());
        order.setFlightAreaReviewedAt(Instant.now());
        if (request.status() == LegalReviewStatus.REJECTED) {
            order.setFlightAreaReviewReason("Permit verification was rejected");
        }
        return toResponse(orderRepository.save(order));
    }

    @Override
    @Transactional
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public OrderCreateResponse cancelAfterPermitRejection(
            String orderId, PermitCancellationProposalRequest request) {
        Order order = orderRepository.findByIdForUpdate(orderId).or(() -> orderRepository.findById(orderId))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found: " + orderId));
        if (order.getOrderStatus() == OrderStatus.COMPLETED
                || order.getOrderStatus() == OrderStatus.CANCELLED
                || order.getOrderStatus() == OrderStatus.REJECTED) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "A cancellation proposal cannot be created for a terminal order");
        }
        if (order.getLegalReviewStatus() != LegalReviewStatus.REJECTED) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "A permit cancellation proposal requires a rejected permit");
        }
        order.setFlightAreaReviewReason("PERMIT_REJECTED_ORDER_CANCELLED");
        order.setFlightAreaReviewNote(request.reason().trim());
        order.setFlightAreaReviewedBy(authenticatedUserResolver.getCurrentUser());
        order.setFlightAreaReviewedAt(Instant.now());
        order.setOrderStatus(OrderStatus.CANCELLED);
        return toResponse(orderRepository.save(order));
    }

    @Override
    @Transactional
    @PreAuthorize("hasRole('CUSTOMER')")
    public OrderCreateResponse createOrder(OrderCreateRequest request) {

        // 1. Resolve Customer from logged-in user session
        User customer = authenticatedUserResolver.getCurrentUser();

        // 2. Validate & fetch Service
        Service service = serviceRepository.findByIdForUpdate(request.getServiceId())
                .orElseThrow(() -> new ApiException(ErrorCode.SERVICE_NOT_FOUND,
                        "Service not found with id: " + request.getServiceId()));
        if (!Boolean.TRUE.equals(service.getIsActive())) {
            throw new ApiException(ErrorCode.SERVICE_INACTIVE);
        }

        // 3. Validate & fetch Preferred Time
        PreferredTime preferredTime = preferredTimeRepository.findById(request.getPreferredTimeId())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Preferred time not found with id: " + request.getPreferredTimeId()));

        // 4. Validate Date Range
        LocalDate earliestFlightDate = LocalDate.now().plusDays(MINIMUM_FLIGHT_NOTICE_DAYS);
        if (request.getPreferredDateFrom().isBefore(earliestFlightDate)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "preferredDateFrom must be at least 7 days after the request date");
        }
        if (request.getPreferredDateFrom().isAfter(request.getPreferredDateTo())) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "preferredDateFrom must be before or equal to preferredDateTo");
        }

        // 4b. Validate result deadline and recurrence
        if (request.getResultDeadline() != null
                && request.getResultDeadline().isBefore(request.getPreferredDateTo())) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "resultDeadline must not be before preferredDateTo");
        }
        OrderRecurrenceType recurrenceType = request.getRecurrenceType() == null
                ? OrderRecurrenceType.NONE
                : request.getRecurrenceType();
        if (recurrenceType != OrderRecurrenceType.NONE && request.getRecurrenceOccurrences() == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "recurrenceOccurrences is required when the order repeats");
        }

        // 4c. Delivery options and terms
        if (!Boolean.TRUE.equals(request.getTermsAccepted())) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Terms and data-security commitment must be accepted");
        }
        if (!Boolean.TRUE.equals(request.getMonitoringAuthorizationConfirmed())) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Monitoring authorization must be confirmed");
        }
        List<OrderAuthorizationDocument> authorizationDocuments =
                validateAuthorizationDocuments(request.getMonitoringAuthorizationDocuments());
        int retentionDays = request.getDataRetentionDays();
        if (!ALLOWED_RETENTION_DAYS.contains(retentionDays)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "dataRetentionDays must be one of " + ALLOWED_RETENTION_DAYS);
        }

        // 5. Convert & validate GeoJSON geometries
        if (!Double.isFinite(request.getLatitude()) || !Double.isFinite(request.getLongitude())
                || Math.abs(request.getLatitude()) > 90 || Math.abs(request.getLongitude()) > 180) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Location must be valid WGS84 GPS coordinates");
        }
        Point location = GeoReader.createPoint(request.getLongitude(), request.getLatitude());
        if (location == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Invalid location coordinates");
        }
        Polygon targetArea = GeoReader.readPolygonFromCoverageArea(request.getCoverageArea());
        if (targetArea == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Invalid coverageArea GeoJSON");
        }
        ServiceAreaPolicy.assertSupported(location, targetArea);

        // 5b. The customer self-checks the official portal and declares the result.
        OrderPermitStatus permitStatus = request.getPermitStatus() == null
                ? OrderPermitStatus.NONE : request.getPermitStatus();
        if (permitStatus == OrderPermitStatus.HAVE_PERMIT
                && (request.getPermitNumber() == null || request.getPermitNumber().isBlank())) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "permitNumber is required when permitStatus is HAVE_PERMIT");
        }
        if (permitStatus == OrderPermitStatus.HAVE_PERMIT) {
            validatePermitEvidence(request);
        }

        // 6. Validate Deliverables requirement
        if (request.getDeliverables() == null || request.getDeliverables().isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "At least one deliverable is required for this service");
        }

        List<OrderDeliverable> orderDeliverables = new ArrayList<>();
        for (OrderDeliverableRequest delReq : request.getDeliverables()) {
            DeliverableType delType = deliverableTypeRepository.findById(delReq.getDeliverableTypeId())
                    .orElseThrow(() -> new ApiException(ErrorCode.DELIVERABLE_TYPE_NOT_FOUND,
                            "Deliverable type not found with id: " + delReq.getDeliverableTypeId()));

            boolean isAssociated = serviceDeliverableRepository.existsByServiceIdAndDeliverableTypeId(
                    service.getId(), delType.getId());
            if (!isAssociated) {
                throw new ApiException(ErrorCode.INVALID_REQUEST,
                        String.format("Deliverable type '%s' is not associated with service '%s'",
                                delType.getName(), service.getName()));
            }

            OrderDeliverable orderDeliverable = OrderDeliverable.builder()
                    .deliverableType(delType)
                    .requirement(delReq.getRequirement())
                    .build();

            orderDeliverables.add(orderDeliverable);
        }

        // 7. Map and persist order
        Order order = orderMapper.toEntity(request);
        order.setCustomer(customer);
        order.setService(service);
        order.setPreferredTime(preferredTime);
        order.setPoint(location);
        order.setTargetArea(targetArea);
        order.setOrderStatus(OrderStatus.PENDING);
        order.setPermitStatus(permitStatus);
        order.setPermitRequired(permitStatus != OrderPermitStatus.NONE);
        order.setPermitZoneName(null);
        order.setMonitoringAuthorizationConfirmed(true);
        order.setMonitoringAuthorizationDocuments(authorizationDocuments);
        if (permitStatus != OrderPermitStatus.HAVE_PERMIT) {
            order.setPermitNumber(null);
        }
        if (permitStatus != OrderPermitStatus.HAVE_PERMIT) {
            order.setPermitExpiresOn(null);
            order.setPermitDocuments(null);
        }
        order.setRecurrenceType(recurrenceType);
        if (recurrenceType == OrderRecurrenceType.NONE) {
            order.setRecurrenceOccurrences(null);
        }
        if (order.getWeatherFallback() == null) {
            order.setWeatherFallback(OrderWeatherFallback.CONTACT_CUSTOMER);
        }
        if (order.getFlightPurpose() == null) {
            order.setFlightPurpose(com.ondemandmonitoring.order.enums.OrderFlightPurpose.DEFAULT);
        }
        order.setResultFormats(distinctOrDefault(request.getResultFormats(), OrderResultFormat.PHOTO));
        order.setDeliveryMethods(distinctOrDefault(request.getDeliveryMethods(), OrderDeliveryMethod.DOWNLOAD));
        order.setDataRetentionDays(retentionDays);
        order.setTermsAcceptedAt(java.time.Instant.now());
        order.setTermsVersion(TERMS_VERSION);
        order.setOrderCode(generateUniqueOrderCode());
        order.setReviewBy(null);
        order.setReviewAt(null);

        for (OrderDeliverable od : orderDeliverables) {
            od.setOrder(order);
        }
        order.setDeliverables(orderDeliverables);

        Order savedOrder = orderRepository.save(order);
        var items = checklistSnapshots.capture(savedOrder, request.getChecklistItems());
        reviewRecords.initialize(savedOrder);
        orderRepository.flush();
        OrderCreateResponse response = orderMapper.toResponse(savedOrder);
        enrichCustomerProfile(response, savedOrder);
        response.setChecklistItems(items);
        checklistSnapshots.enrichOrderResponse(savedOrder, response, false);
        return response;
    }

    private static List<OrderAuthorizationDocument> validateAuthorizationDocuments(
            List<OrderAuthorizationDocument> documents) {
        if (documents == null || documents.isEmpty()) return List.of();
        if (documents.size() > 10) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "At most 10 authorization documents are allowed");
        }
        return documents.stream().map(document -> {
            if (document == null || document.documentType() == null
                    || document.documentName() == null || document.documentName().isBlank()
                    || document.documentName().length() > 160
                    || document.fileName() == null || document.fileName().isBlank()
                    || document.contentType() == null
                    || !(document.contentType().equals("application/pdf")
                        || document.contentType().equals("image/jpeg")
                        || document.contentType().equals("image/png"))
                    || document.sizeBytes() <= 0 || document.sizeBytes() > 5 * 1024 * 1024
                    || document.dataUrl() == null || document.dataUrl().isBlank()) {
                throw new ApiException(ErrorCode.INVALID_REQUEST,
                        "Invalid monitoring authorization document");
            }
            return new OrderAuthorizationDocument(
                    document.id(), document.documentType(), document.documentName().trim(),
                    document.fileName(), document.contentType(), document.sizeBytes(),
                    document.dataUrl(),
                    document.notes() == null || document.notes().isBlank()
                            ? null : document.notes().trim(),
                    DocumentVerificationStatus.PENDING, null, null, null);
        }).toList();
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("@orderAuthorizationService.canViewOrder(#orderId)")
    public OrderCreateResponse getOrderById(String orderId) {
        return toResponse(orderRepository.findById(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found: " + orderId)));
    }
    private OrderCreateResponse unusedGetOrderById(String orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId).or(() -> orderRepository.findById(orderId))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found: " + orderId));
        return toResponse(order);
    }

    @Override
    @Transactional
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public void rejectOrder(String orderId, String reason) {
        Order order = orderRepository.findByIdForUpdate(orderId).or(() -> orderRepository.findById(orderId))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found: " + orderId));

        if (order.getOrderStatus() != OrderStatus.PENDING) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Only PENDING orders can be rejected");
        }

        order.setOrderStatus(OrderStatus.REJECTED);
        order.setRejectReason(reason);
        order.setReviewAt(java.time.Instant.now());
        orderRepository.save(order);
    }

    @Override
    @Transactional
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public void requestMoreInformation(String orderId, String reason, String requestedUpdateScope) {
        Order order = orderRepository.findByIdForUpdate(orderId).or(() -> orderRepository.findById(orderId))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found: " + orderId));

        if (order.getOrderStatus() != OrderStatus.PENDING) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Only PENDING orders can request more information");
        }

        order.setOrderStatus(OrderStatus.NEEDS_INFO);
        order.setRejectReason(reason);
        order.setRequestedUpdateScope(
                "LOCATION".equalsIgnoreCase(requestedUpdateScope) ? "LOCATION" : "ADDITIONAL_INFORMATION");
        order.setReviewAt(java.time.Instant.now());
        orderRepository.save(order);
    }

    @Override
    @Transactional
    @PreAuthorize("hasRole('CUSTOMER')")
    public OrderCreateResponse submitAdditionalInformation(String orderId,
            com.ondemandmonitoring.order.dto.request.OrderAdditionalInformationRequest request) {
        Order order = orderRepository.findByIdForUpdate(orderId).or(() -> orderRepository.findById(orderId))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found: " + orderId));
        User customer = authenticatedUserResolver.getCurrentUser();
        if (order.getCustomer() == null || !order.getCustomer().getId().equals(customer.getId())) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "This order does not belong to the current customer");
        }
        if (order.getOrderStatus() != OrderStatus.NEEDS_INFO) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Order is not waiting for additional information");
        }
        if ("LOCATION".equals(order.getRequestedUpdateScope())) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "This request requires the monitoring location to be updated");
        }
        if (request.siteContactName() != null) order.setSiteContactName(request.siteContactName());
        if (request.siteContactPhone() != null) order.setSiteContactPhone(request.siteContactPhone());
        if (request.accessNotes() != null) order.setAccessNotes(request.accessNotes());
        if (request.monitoringRelationship() != null) {
            order.setMonitoringRelationship(request.monitoringRelationship());
        }
        if (request.monitoringAuthorizationConfirmed() != null) {
            order.setMonitoringAuthorizationConfirmed(request.monitoringAuthorizationConfirmed());
        }
        if (request.monitoringAuthorizationDocuments() != null
                && !request.monitoringAuthorizationDocuments().isEmpty()) {
            List<OrderAuthorizationDocument> uploaded =
                    validateAuthorizationDocuments(request.monitoringAuthorizationDocuments());
            List<OrderAuthorizationDocument> combined = new ArrayList<>(
                    order.getMonitoringAuthorizationDocuments() == null
                            ? List.of() : order.getMonitoringAuthorizationDocuments());
            combined.addAll(uploaded);
            if (combined.size() > 10) {
                throw new ApiException(ErrorCode.INVALID_REQUEST,
                        "At most 10 authorization documents are allowed");
            }
            order.setMonitoringAuthorizationDocuments(combined);
        }
        order.setDescription((order.getDescription() == null ? "" : order.getDescription().strip())
                + "\n\nThông tin khách bổ sung: " + request.information().strip());
        order.setOrderStatus(OrderStatus.PENDING);
        order.setRejectReason(null);
        order.setRequestedUpdateScope(null);
        order.setReviewAt(null);
        reviewRecords.reopenFailed(order, request.information());
        return toResponse(orderRepository.save(order));
    }

    @Override
    @Transactional
    @PreAuthorize("hasRole('CUSTOMER')")
    public OrderCreateResponse updateCustomerOrder(String orderId,
            com.ondemandmonitoring.order.dto.request.OrderCustomerUpdateRequest request) {
        Order order = orderRepository.findByIdForUpdate(orderId).or(() -> orderRepository.findById(orderId))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found: " + orderId));
        User customer = authenticatedUserResolver.getCurrentUser();
        if (order.getCustomer() == null || !order.getCustomer().getId().equals(customer.getId()))
            throw new ApiException(ErrorCode.ACCESS_DENIED, "This order does not belong to the current customer");
        if (order.getOrderStatus() != OrderStatus.PENDING && order.getOrderStatus() != OrderStatus.NEEDS_INFO
                && order.getOrderStatus() != OrderStatus.APPROVED)
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Only unpaid Orders can be edited");
        var acceptedQuote = quoteRepository.findFirstByOrderIdAndStatusInOrderByQuoteVersionDesc(orderId,
                java.util.List.of(com.ondemandmonitoring.finance.enums.QuoteStatus.ACCEPTED_BY_CUSTOMER));
        if (acceptedQuote.isPresent())
            throw new ApiException(ErrorCode.INVALID_REQUEST, "An Order cannot be edited after its quote is accepted");

        boolean pricingInputsChanged = request.coverageArea() != null
                || request.altitudeM() != null || request.estimatedLengthM() != null
                || request.recurrenceType() != null || request.recurrenceOccurrences() != null
                || request.resultFormats() != null || request.permitStatus() != null;

        boolean submittingLocationCorrection = order.getOrderStatus() == OrderStatus.NEEDS_INFO
                && "LOCATION".equals(order.getRequestedUpdateScope());
        if (submittingLocationCorrection) {
            boolean hasLocation = request.address() != null || request.longitude() != null
                    || request.latitude() != null || request.radiusM() != null
                    || request.coverageArea() != null;
            boolean hasUnrequestedChanges = request.title() != null || request.description() != null
                    || request.usagePurpose() != null || request.flightPurpose() != null
                    || request.altitudeM() != null || request.estimatedLengthM() != null
                    || request.siteContactName() != null || request.siteContactPhone() != null
                    || request.accessNotes() != null || request.monitoringRelationship() != null
                    || request.monitoringAuthorizationConfirmed() != null || request.permitStatus() != null
                    || request.permitNumber() != null || request.permitExpiresOn() != null
                    || request.preferredDateFrom() != null || request.preferredDateTo() != null
                    || request.recurrenceType() != null || request.recurrenceOccurrences() != null
                    || request.weatherFallback() != null || request.resultDeadline() != null
                    || request.resultFormats() != null || request.deliveryMethods() != null
                    || request.dataRetentionDays() != null;
            if (!hasLocation || hasUnrequestedChanges) {
                throw new ApiException(ErrorCode.INVALID_REQUEST,
                        "Only the requested monitoring location can be updated");
            }
        }

        if (request.title() != null && !request.title().isBlank()) order.setTitle(request.title().strip());
        if (request.description() != null) order.setDescription(request.description().strip());
        if (request.address() != null) order.setAddress(request.address().strip());
        if (request.longitude() != null || request.latitude() != null || request.coverageArea() != null) {
            if (request.longitude() == null || request.latitude() == null || request.coverageArea() == null) {
                throw new ApiException(ErrorCode.INVALID_REQUEST,
                        "longitude, latitude and coverageArea must be updated together");
            }
            var point = orderMapper.toPoint(request.longitude(), request.latitude());
            var targetArea = orderMapper.toPolygon(request.coverageArea());
            com.ondemandmonitoring.order.util.ServiceAreaPolicy.assertSupported(point, targetArea);
            order.setPoint(point);
            order.setTargetArea(targetArea);
        }
        if (request.radiusM() != null) {
            order.getDeliverables().forEach(deliverable -> {
                var requirement = new java.util.LinkedHashMap<String, Object>();
                if (deliverable.getRequirement() != null) requirement.putAll(deliverable.getRequirement());
                requirement.put("radiusM", request.radiusM());
                deliverable.setRequirement(requirement);
            });
        }
        if (request.usagePurpose() != null) order.setUsagePurpose(request.usagePurpose());
        if (request.flightPurpose() != null) order.setFlightPurpose(request.flightPurpose());
        if (request.altitudeM() != null) order.setAltitudeM(request.altitudeM());
        if (request.estimatedLengthM() != null) order.setEstimatedLengthM(request.estimatedLengthM());
        if (request.siteContactName() != null) order.setSiteContactName(request.siteContactName().strip());
        if (request.siteContactPhone() != null) order.setSiteContactPhone(request.siteContactPhone().strip());
        if (request.accessNotes() != null) order.setAccessNotes(request.accessNotes().strip());
        if (request.monitoringRelationship() != null) order.setMonitoringRelationship(request.monitoringRelationship());
        if (request.monitoringAuthorizationConfirmed() != null) order.setMonitoringAuthorizationConfirmed(request.monitoringAuthorizationConfirmed());
        if (request.permitStatus() != null) order.setPermitStatus(request.permitStatus());
        if (request.permitNumber() != null) order.setPermitNumber(request.permitNumber().strip());
        if (request.permitExpiresOn() != null) order.setPermitExpiresOn(request.permitExpiresOn());
        if (request.preferredDateFrom() != null) order.setPreferredDateFrom(request.preferredDateFrom());
        if (request.preferredDateTo() != null) order.setPreferredDateTo(request.preferredDateTo());
        if (order.getPreferredDateFrom() != null && order.getPreferredDateTo() != null
                && order.getPreferredDateTo().isBefore(order.getPreferredDateFrom()))
            throw new ApiException(ErrorCode.INVALID_REQUEST, "End date cannot be before start date");
        if (request.recurrenceType() != null) order.setRecurrenceType(request.recurrenceType());
        if (request.recurrenceOccurrences() != null) order.setRecurrenceOccurrences(request.recurrenceOccurrences());
        if (request.weatherFallback() != null) order.setWeatherFallback(request.weatherFallback());
        if (request.resultDeadline() != null) order.setResultDeadline(request.resultDeadline());
        if (order.getResultDeadline() != null && order.getPreferredDateTo() != null
                && order.getResultDeadline().isBefore(order.getPreferredDateTo()))
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Result deadline cannot be before the requested end date");
        if (request.resultFormats() != null && !request.resultFormats().isEmpty()) order.setResultFormats(request.resultFormats());
        if (request.deliveryMethods() != null && !request.deliveryMethods().isEmpty()) order.setDeliveryMethods(request.deliveryMethods());
        if (request.dataRetentionDays() != null) {
            if (!java.util.Set.of(30, 90, 180, 365).contains(request.dataRetentionDays()))
                throw new ApiException(ErrorCode.INVALID_REQUEST, "Data retention must be 30, 90, 180 or 365 days");
            order.setDataRetentionDays(request.dataRetentionDays());
        }
        if (order.getOrderStatus() == OrderStatus.APPROVED || pricingInputsChanged) {
            quoteRepository.findFirstByOrderIdAndStatusInOrderByQuoteVersionDesc(orderId,
                    java.util.List.of(com.ondemandmonitoring.finance.enums.QuoteStatus.DRAFT,
                            com.ondemandmonitoring.finance.enums.QuoteStatus.PENDING_REVIEW,
                            com.ondemandmonitoring.finance.enums.QuoteStatus.APPROVED))
                    .ifPresent(quote -> {
                        quote.setStatus(com.ondemandmonitoring.finance.enums.QuoteStatus.SUPERSEDED);
                        quoteRepository.save(quote);
                    });
            order.setOrderStatus(OrderStatus.PENDING);
            order.setReviewBy(null);
            order.setReviewAt(null);
        }
        if (order.getOrderStatus() == OrderStatus.NEEDS_INFO) {
            order.setOrderStatus(OrderStatus.PENDING);
            order.setRejectReason(null);
            order.setRequestedUpdateScope(null);
            order.setReviewAt(null);
        }
        if (submittingLocationCorrection) {
            // The previous legal review belongs to the old coordinates and must
            // never be shown as the verdict for the newly submitted area.
            order.setLegalReviewStatus(LegalReviewStatus.NOT_REVIEWED);
            order.setRequestedScopeFeasible(null);
            order.setFlightAreaReviewReason(null);
            order.setFlightAreaReviewNote(null);
            order.setFlightAreaReviewedBy(null);
            order.setFlightAreaReviewedAt(null);
            order.setPermitRequired(null);
            order.setPermitZoneName(null);
            reviewRecords.reopenFailed(order, "Khách hàng đã cập nhật lại khu vực giám sát");
        }
        Order saved = orderRepository.save(order);
        if (pricingInputsChanged) checklistSnapshots.recalculate(saved);
        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public List<OrderCreateResponse> getPendingOrders() {
        return toResponses(orderRepository.findByOrderStatusOrderByCreatedAtAsc(OrderStatus.PENDING));
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public List<OrderCreateResponse> getApprovedOrders() {
        return toResponses(orderRepository.findByOrderStatusOrderByCreatedAtAsc(OrderStatus.APPROVED));
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public List<OrderCreateResponse> getManagedOrders(OrderStatus status) {
        List<Order> orders = status == null
                ? orderRepository.findAll(org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Direction.DESC, "createdAt"))
                : orderRepository.findByOrderStatusOrderByCreatedAtAsc(status).reversed();
        return toResponses(orders);
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('CUSTOMER')")
    public List<OrderCreateResponse> getMyOrders(OrderStatus status) {
        User customer = authenticatedUserResolver.getCurrentUser();
        List<Order> orders = status == null
                ? orderRepository.findByCustomer_IdOrderByCreatedAtDesc(customer.getId())
                : orderRepository.findByCustomer_IdAndOrderStatusOrderByCreatedAtDesc(customer.getId(), status);

        return toResponses(orders);
    }

    private Order findOrderByIdForWrite(String id) {
        return orderRepository.findByIdForUpdate(id).or(() -> orderRepository.findById(id)).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found: " + id));
    }

    private OrderCreateResponse toResponse(Order order) {
        var response = toResponses(List.of(order)).getFirst();
        checklistSnapshots.enrichOrderResponse(order, response, true);
        return response;
    }

    private OrderCreateResponse toResponseUnused(Order order) {
        return toResponses(List.of(order)).getFirst();
    }

    private List<OrderCreateResponse> toResponses(List<Order> orders) {
        var snapshots = checklistSnapshots.getByOrders(orders.stream().map(Order::getId).toList());
        var publishedGuidance = publishedGuidanceByOrder(orders);
        return orders.stream().map(order -> {
            var response = orderMapper.toResponse(order);
            enrichCustomerProfile(response, order);
            response.setChecklistItems(snapshots.getOrDefault(order.getId(), List.of()));
            applyPublishedGuidance(response, publishedGuidance.get(order.getId()));
            checklistSnapshots.enrichOrderResponse(order, response, false);
            if (order.getOrderStatus() == OrderStatus.APPROVED) {
                response.setDepositSatisfied(invoiceRepository.findByOrderId(order.getId())
                        .map(invoice -> invoice.getPaidAmount().compareTo(invoice.getDepositAmount()) >= 0)
                        .orElse(false));
            }
            return response;
        }).toList();
    }

    /** Customer-facing requirements use published scope, never the Manager's mutable draft. */
    private Map<String, ChecklistGuidanceSnapshot> publishedGuidanceByOrder(
            List<Order> orders) {
        if (orders.isEmpty()) return Map.of();
        var byId = orders.stream().collect(Collectors.toMap(Order::getId, o -> o));
        var result = new HashMap<String, ChecklistGuidanceSnapshot>();
        var candidates = quoteRepository.findGuidanceQuotesForOrders(byId.keySet(), List.of(
                QuoteStatus.APPROVED,
                QuoteStatus.ACCEPTED_BY_CUSTOMER));
        // Accepted scope takes precedence over any later draft/published quote.
        for (var status : List.of(QuoteStatus.ACCEPTED_BY_CUSTOMER,
                QuoteStatus.APPROVED)) {
            for (var quote : candidates) {
                var order = byId.get(quote.getOrder().getId());
                long revision = order.getChecklistRevision() == 0 ? 1 : order.getChecklistRevision();
                if (quote.getStatus() != status || quote.getChecklistRevision() != revision
                        || quote.getChecklistGuidanceSnapshot() == null
                        || (status == QuoteStatus.APPROVED
                            && order.getOrderStatus() != OrderStatus.APPROVED)) continue;
                result.putIfAbsent(order.getId(), quote.getChecklistGuidanceSnapshot());
            }
        }
        return result;
    }

    private void applyPublishedGuidance(OrderCreateResponse response,
            ChecklistGuidanceSnapshot snapshot) {
        if (snapshot == null || response.getChecklistItems() == null) return;
        var guidanceByItem = snapshot.items().stream().collect(Collectors.toMap(
                ChecklistGuidanceSnapshot.Item::itemId, g -> g));
        for (var item : response.getChecklistItems()) {
            if (item.getSourceType() != OrderChecklistSourceType.CUSTOMER_CUSTOM) continue;
            var guidance = guidanceByItem.get(item.getId());
            if (guidance == null || guidance.checklistRevision() != item.getChecklistRevision()) continue;
            item.setDescription(guidance.description());
            item.setExecutionSteps(guidance.executionSteps());
            item.setInputRequirements(guidance.inputRequirements());
            item.setExpectedOutputs(guidance.expectedOutputs());
            item.setGuidanceVersion(guidance.guidanceVersion());
            item.setGuidanceConfirmedVersion(guidance.guidanceVersion());
            item.setGuidanceReady(true);
        }
    }

    private void enrichCustomerProfile(OrderCreateResponse response, Order order) {
        if (order.getCustomer() == null || order.getCustomer().getId() == null) {
            return;
        }
        customerProfileRepository.findById(order.getCustomer().getId()).ifPresent(profile -> {
            response.setCustomerPhoneNumber(profile.getPhoneNumber());
            response.setCustomerAddress(profile.getAddress());
            response.setCustomerCompanyName(profile.getCompanyName());
            response.setCustomerCitizenIdentificationNumber(
                    profile.getCitizenIdentificationNumber());
            response.setCustomerCompanyTaxCode(profile.getCompanyTaxCode());
        });
    }

    static final int MAX_PERMIT_FILES = 3;
    static final long MAX_PERMIT_FILE_BYTES = 5L * 1024 * 1024;
    /** base64 inflates ~4/3; keep the stored string bounded too. */
    static final int MAX_PERMIT_DATA_URL_CHARS = 7_500_000;

    /**
     * A permit number alone is unverifiable: the customer must attach the permit file
     * and state until when it is valid, and that must cover the flight window.
     */
    private static void validatePermitEvidence(OrderCreateRequest request) {
        var documents = request.getPermitDocuments();
        if (documents == null || documents.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Attach the flight permit file when permitStatus is HAVE_PERMIT");
        }
        if (documents.size() > MAX_PERMIT_FILES) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "At most " + MAX_PERMIT_FILES + " permit files");
        }
        for (var document : documents) {
            String type = document.contentType() == null ? "" : document.contentType().toLowerCase();
            boolean allowedType = type.equals("application/pdf") || type.startsWith("image/");
            if (document.fileName() == null || document.fileName().isBlank() || !allowedType) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "Permit files must be PDF or image");
            }
            if (document.sizeBytes() <= 0 || document.sizeBytes() > MAX_PERMIT_FILE_BYTES
                    || document.dataUrl() == null || document.dataUrl().isBlank()
                    || document.dataUrl().length() > MAX_PERMIT_DATA_URL_CHARS) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "Permit file is empty or larger than 5 MB");
            }
        }
        if (request.getPermitExpiresOn() == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "permitExpiresOn is required when permitStatus is HAVE_PERMIT");
        }
        if (request.getPermitExpiresOn().isBefore(request.getPreferredDateTo())) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "The permit expires before the preferred flight date");
        }
    }

    /** Version recorded with the customer's acceptance of the terms and security commitment. */
    static final String TERMS_VERSION = "2026-10";
    static final java.util.List<Integer> ALLOWED_RETENTION_DAYS = java.util.List.of(30, 90, 180, 365);

    /** Keeps the customer's order of choice, drops duplicates, falls back to one default. */
    private static <T> java.util.List<T> distinctOrDefault(java.util.List<T> values, T fallback) {
        if (values == null || values.isEmpty()) return new java.util.ArrayList<>(java.util.List.of(fallback));
        return new java.util.ArrayList<>(new java.util.LinkedHashSet<>(values));
    }
    private String generateUniqueOrderCode() {
        for (int attempt = 0; attempt < 10; attempt++) {
            String orderCode = generateOrderCode();
            if (!orderRepository.existsByOrderCode(orderCode)) {
                return orderCode;
            }
        }
        throw new ApiException(ErrorCode.INVALID_REQUEST, "Unable to generate a unique order code");
    }

    private String generateOrderCode() {
        String datePart = LocalDate.now().format(ORDER_CODE_DATE_FORMATTER);
        String suffix = UUID.randomUUID().toString()
                .replace("-", "")
                .substring(0, 6)
                .toUpperCase();
        return "ORD-" + datePart + "-" + suffix;
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.strip();
    }
}
