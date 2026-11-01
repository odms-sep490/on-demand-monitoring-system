package com.ondemandmonitoring.order.dto.response;

import com.ondemandmonitoring.order.enums.OrderDeliveryMethod;
import com.ondemandmonitoring.order.enums.OrderPermitStatus;
import com.ondemandmonitoring.order.enums.OrderRecurrenceType;
import com.ondemandmonitoring.order.enums.OrderResultFormat;
import com.ondemandmonitoring.order.enums.OrderStatus;
import com.ondemandmonitoring.order.enums.ExecutionPlanStatus;
import com.ondemandmonitoring.order.enums.OrderUsagePurpose;
import com.ondemandmonitoring.order.enums.LegalReviewStatus;
import com.ondemandmonitoring.order.enums.OrderWeatherFallback;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.FieldDefaults;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Schema(description = "Response object for created monitoring order")
public class OrderCreateResponse {

    String id;
    String orderCode;
    String customerId;
    String customerName;
    String customerEmail;
    String customerPhoneNumber;
    String customerAddress;
    String customerCompanyName;
    String customerCitizenIdentificationNumber;
    String customerCompanyTaxCode;

    // General Info
    String title;
    String serviceId;
    String serviceName;
    BigDecimal serviceBasePriceSnapshot;
    String description;
    OrderUsagePurpose usagePurpose;
    com.ondemandmonitoring.order.enums.OrderFlightPurpose flightPurpose;
    Double altitudeM;
    Double estimatedLengthM;
    String siteContactName;
    String siteContactPhone;
    String accessNotes;
    OrderPermitStatus permitStatus;
    String permitNumber;
    LocalDate permitExpiresOn;
    List<com.ondemandmonitoring.order.dto.OrderPermitDocument> permitDocuments;
    com.ondemandmonitoring.order.enums.MonitoringRelationship monitoringRelationship;
    Boolean monitoringAuthorizationConfirmed;
    List<com.ondemandmonitoring.order.dto.OrderAuthorizationDocument> monitoringAuthorizationDocuments;
    Boolean permitRequired;
    String permitZoneName;

    // Location Info
    String address;
    Double longitude;
    Double latitude;
    Double radiusM;
    Map<String, Object> coverageArea;

    // Schedule Info
    LocalDate preferredDateFrom;
    LocalDate preferredDateTo;
    String preferredTimeId;
    String preferredTimeName;
    OrderRecurrenceType recurrenceType;
    Integer recurrenceOccurrences;
    OrderWeatherFallback weatherFallback;
    LocalDate resultDeadline;

    // Delivery & terms
    List<OrderResultFormat> resultFormats;
    List<OrderDeliveryMethod> deliveryMethods;
    Integer dataRetentionDays;
    Instant resultExpiresAt;
    Instant termsAcceptedAt;
    String termsVersion;

    // Status & Review Info
    OrderStatus orderStatus;
    long executionPlanRevision;
    ExecutionPlanStatus executionPlanStatus;
    String rejectReason;
    String requestedUpdateScope;
    String reviewById;
    String reviewByName;
    Instant reviewAt;
    LegalReviewStatus legalReviewStatus;
    /** Customer-facing payment milestone for approved orders; null before approval. */
    Boolean depositSatisfied;
    String flightAreaReviewReason;
    String flightAreaReviewNote;
    Boolean requestedScopeFeasible;
    String flightAreaReviewedById;
    String flightAreaReviewedByName;
    Instant flightAreaReviewedAt;

    // Deliverables
    List<OrderDeliverableResponse> deliverables;

    List<CustomerOrderChecklistItemResponse> checklistItems;
    Instant checklistSnapshotAt;
    long checklistRevision;
    boolean canEditChecklist;
    String checklistEditBlockedReason;

    Instant createdAt;
    Instant updatedAt;
}
