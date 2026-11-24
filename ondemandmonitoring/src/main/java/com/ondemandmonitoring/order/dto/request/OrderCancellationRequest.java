package com.ondemandmonitoring.order.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record OrderCancellationRequest(
        @NotBlank
        @Size(max = 1000)
        String reason) {
}
