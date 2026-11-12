package com.ondemandmonitoring.refund.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RefundDecisionRequest(@NotBlank @Size(max = 2000) String reason) {
}
