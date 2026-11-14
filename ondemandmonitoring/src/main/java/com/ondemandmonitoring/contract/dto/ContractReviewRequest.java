package com.ondemandmonitoring.contract.dto;

import jakarta.validation.constraints.*;

public record ContractReviewRequest(
    @NotNull @PositiveOrZero Long expectedVersion,
    @NotBlank String submissionId,
    @Size(max = 2000) String reason) {}
