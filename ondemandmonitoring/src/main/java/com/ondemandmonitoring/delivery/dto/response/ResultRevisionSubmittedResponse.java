package com.ondemandmonitoring.delivery.dto.response;

import com.ondemandmonitoring.delivery.enums.DeliveryStatus;
import java.time.Instant;

public record ResultRevisionSubmittedResponse(String revisionRequestId, String ticketId, String ticketCode,
        String reviewRoundId, Instant submittedAt, DeliveryStatus deliveryStatus) {}
