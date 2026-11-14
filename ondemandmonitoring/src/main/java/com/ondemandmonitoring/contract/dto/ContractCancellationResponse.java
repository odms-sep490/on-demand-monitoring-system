package com.ondemandmonitoring.contract.dto;

import com.ondemandmonitoring.contract.enums.*;
import java.math.BigDecimal;
import java.time.Instant;

public record ContractCancellationResponse(
    String id,
    long version,
    ContractCancellationStatus status,
    CancellationResponsibility responsibility,
    String reason,
    String basis,
    BigDecimal depositPaid,
    String refundId,
    Instant requestedAt,
    String requestedBy,
    Instant decidedAt,
    String decidedBy) {}
