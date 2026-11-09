package com.ondemandmonitoring.order.dto.request;

import com.ondemandmonitoring.order.enums.*;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Customer-editable fields while an Order is still under review. */
public record OrderCustomerUpdateRequest(
        @Size(max = 255) String title,
        @Size(max = 4000) String description,
        @Size(max = 2000) String address,
        @DecimalMin("-180") @DecimalMax("180") Double longitude,
        @DecimalMin("-90") @DecimalMax("90") Double latitude,
        @DecimalMin("50") @DecimalMax("5000") Double radiusM,
        Map<String, Object> coverageArea,
        OrderUsagePurpose usagePurpose,
        OrderFlightPurpose flightPurpose,
        @DecimalMin("10") @DecimalMax("120") Double altitudeM,
        @DecimalMin(value = "0", inclusive = false) @DecimalMax("100000") Double estimatedLengthM,
        @Size(max = 120) String siteContactName,
        @Pattern(regexp = "^$|^[+0-9 ().-]{8,20}$") String siteContactPhone,
        @Size(max = 1000) String accessNotes,
        MonitoringRelationship monitoringRelationship,
        Boolean monitoringAuthorizationConfirmed,
        OrderPermitStatus permitStatus,
        @Size(max = 80) String permitNumber,
        LocalDate permitExpiresOn,
        LocalDate preferredDateFrom,
        LocalDate preferredDateTo,
        OrderRecurrenceType recurrenceType,
        @Min(2) @Max(52) Integer recurrenceOccurrences,
        OrderWeatherFallback weatherFallback,
        LocalDate resultDeadline,
        @Size(max = 5) List<@NotNull OrderResultFormat> resultFormats,
        @Size(max = 3) List<@NotNull OrderDeliveryMethod> deliveryMethods,
        Integer dataRetentionDays) {}
