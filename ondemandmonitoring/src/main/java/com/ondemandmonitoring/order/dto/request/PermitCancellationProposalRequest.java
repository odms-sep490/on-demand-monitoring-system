package com.ondemandmonitoring.order.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PermitCancellationProposalRequest(
        @NotBlank @Size(max = 1000) String reason) {
}
