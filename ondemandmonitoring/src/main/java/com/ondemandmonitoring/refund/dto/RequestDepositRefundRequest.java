package com.ondemandmonitoring.refund.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RequestDepositRefundRequest(
        @NotBlank @Size(max = 2000) String reason) {
}
