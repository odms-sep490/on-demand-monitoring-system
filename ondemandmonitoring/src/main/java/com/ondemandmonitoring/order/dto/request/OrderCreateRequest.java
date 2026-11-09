package com.ondemandmonitoring.order.dto.request;

import com.ondemandmonitoring.order.enums.OrderDeliveryMethod;
import com.ondemandmonitoring.order.enums.OrderPermitStatus;
import com.ondemandmonitoring.order.enums.OrderRecurrenceType;
import com.ondemandmonitoring.order.enums.OrderResultFormat;
import com.ondemandmonitoring.order.enums.OrderUsagePurpose;
import com.ondemandmonitoring.order.enums.OrderWeatherFallback;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import com.ondemandmonitoring.order.validation.AllowedRetentionDays;
import java.time.LocalDate;
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
@Schema(description = "Request object for creating a monitoring order")
public class OrderCreateRequest {

    @NotBlank(message = "Title is required")
    @Size(max = 255, message = "Title must not exceed 255 characters")
    @Schema(description = "Title of the order", example = "Forest Area Monitoring")
    String title;

    @Schema(description = "Intended use of the result", example = "INTERNAL")
    OrderUsagePurpose usagePurpose;

    @Schema(description = "Purpose of the flight (Decree 288/2025/ND-CP, Article 4.3); defaults to SURVEY_MONITORING")
    com.ondemandmonitoring.order.enums.OrderFlightPurpose flightPurpose;

    @DecimalMin(value = "10", message = "Flight altitude must be at least 10 m")
    @DecimalMax(value = "120", message = "Flight altitude must not exceed 120 m")
    @Schema(description = "Desired flight altitude in metres (10-120)", example = "60")
    Double altitudeM;

    @DecimalMin(value = "0", inclusive = false, message = "Estimated length must be positive")
    @DecimalMax(value = "100000", message = "Estimated length must not exceed 100000 m")
    @Schema(description = "Estimated length in metres for linear targets (roads, runways, pipelines)")
    Double estimatedLengthM;

    @Size(max = 120, message = "Contact name must not exceed 120 characters")
    @Schema(description = "On-site contact person")
    String siteContactName;

    @Pattern(regexp = "^$|^[+0-9 ().-]{8,20}$", message = "Invalid contact phone number")
    @Schema(description = "On-site contact phone", example = "0901234567")
    String siteContactPhone;

    @Size(max = 1000, message = "Access notes must not exceed 1000 characters")
    @Schema(description = "Site access conditions (gate, escort, working hours...)")
    String accessNotes;

    @Schema(description = "Airspace permit answer; required when the area needs a permit")
    OrderPermitStatus permitStatus;

    @Size(max = 80, message = "Permit number must not exceed 80 characters")
    String permitNumber;

    @Schema(description = "Last day the permit is valid; required with permitStatus HAVE_PERMIT")
    LocalDate permitExpiresOn;

    @Size(max = 3, message = "At most 3 permit files")
    @Schema(description = "Permit files (PDF or image); required with permitStatus HAVE_PERMIT")
    List<com.ondemandmonitoring.order.dto.OrderPermitDocument> permitDocuments;

    @NotNull(message = "Monitoring relationship is required")
    com.ondemandmonitoring.order.enums.MonitoringRelationship monitoringRelationship;

    @NotNull(message = "Monitoring authorization confirmation is required")
    Boolean monitoringAuthorizationConfirmed;

    @Size(max = 10, message = "At most 10 authorization documents")
    List<com.ondemandmonitoring.order.dto.OrderAuthorizationDocument> monitoringAuthorizationDocuments;

    @NotBlank(message = "Service ID is required")
    @Schema(description = "Service ID", example = "550e8400-e29b-41d4-a716-446655440000")
    String serviceId;

    @Schema(description = "Detailed description of the monitoring request")
    String description;

    @Schema(description = "Address of the monitoring target location")
    String address;

    @NotNull(message = "Longitude is required")
    @Schema(description = "Longitude coordinate", example = "106.660172")
    Double longitude;

    @NotNull(message = "Latitude is required")
    @Schema(description = "Latitude coordinate", example = "10.762622")
    Double latitude;

    @NotNull(message = "Coverage area GeoJSON map is required")
    @Schema(description = "Coverage area GeoJSON object map")
    Map<String, Object> coverageArea;

    @NotNull(message = "Preferred date from is required")
    @Schema(description = "Preferred monitoring start date", example = "2026-10-01")
    LocalDate preferredDateFrom;

    @NotNull(message = "Preferred date to is required")
    @Schema(description = "Preferred monitoring end date", example = "2026-10-05")
    LocalDate preferredDateTo;

    @NotBlank(message = "Preferred time ID is required")
    @Schema(description = "Preferred time frame ID")
    String preferredTimeId;

    @Schema(description = "Repeat the flight weekly or monthly; defaults to NONE", example = "NONE")
    OrderRecurrenceType recurrenceType;

    @Min(value = 2, message = "Recurrence must have at least 2 flights")
    @Max(value = 52, message = "Recurrence must not exceed 52 flights")
    @Schema(description = "Total number of flights when recurrenceType is not NONE (2-52)", example = "4")
    Integer recurrenceOccurrences;

    @Schema(description = "What to do when the flight cannot go ahead (weather, no permit, blocked access, equipment failure, safety); defaults to CONTACT_CUSTOMER")
    OrderWeatherFallback weatherFallback;

    @Schema(description = "Latest date the customer needs the result; must not be before preferredDateTo", example = "2026-10-12")
    LocalDate resultDeadline;

    @Size(max = 5, message = "At most 5 result formats")
    @Schema(description = "Result formats wanted; defaults to [PHOTO]", example = "[\"PHOTO\",\"PDF_REPORT\"]")
    List<@NotNull OrderResultFormat> resultFormats;

    @Size(max = 3, message = "At most 3 delivery methods")
    @Schema(description = "How the result is received; defaults to [DOWNLOAD]", example = "[\"DOWNLOAD\",\"EMAIL\"]")
    List<@NotNull OrderDeliveryMethod> deliveryMethods;

    @NotNull(message = "Vui lòng chọn thời hạn lưu trữ kết quả.")
    @AllowedRetentionDays
    @Schema(description = "Days the result data is kept: 30, 90, 180 or 365", example = "90")
    Integer dataRetentionDays;

    @Schema(description = "Customer accepts the terms and the data-security commitment; must be true")
    Boolean termsAccepted;

    @NotEmpty(message = "At least one deliverable is required")
    @Schema(description = "List of deliverable types with requirements for the service")
    List<@Valid OrderDeliverableRequest> deliverables;

    @Size(min = 1, max = 100, message = "Checklist must contain between 1 and 100 items")
    List<@NotNull @Valid OrderChecklistItemRequest> checklistItems;
}
