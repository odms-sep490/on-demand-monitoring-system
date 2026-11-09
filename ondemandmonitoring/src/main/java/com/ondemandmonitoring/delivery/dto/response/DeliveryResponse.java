package com.ondemandmonitoring.delivery.dto.response;

import com.ondemandmonitoring.delivery.enums.DeliveryStatus;
import com.ondemandmonitoring.finance.enums.InvoiceStatus;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.order.enums.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Read model combining delivery progress, payment state, and visible assets. */
public record DeliveryResponse(
        String orderId, DeliveryStatus deliveryStatus, MissionStatus missionStatus, OrderStatus orderStatus,
        InvoiceStatus invoiceStatus, BigDecimal totalAmount, BigDecimal paidAmount, BigDecimal remainingAmount,
        String deliveryNotes, Instant previewReleasedAt, Instant customerAcceptedAt,
        Instant revisionRequestedAt, String revisionReason, int revisionCount,
        Instant finalPaymentConfirmedAt, Instant originalsReleasedAt, List<DeliveryAssetResponse> assets) {}
