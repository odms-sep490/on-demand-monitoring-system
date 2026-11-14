package com.ondemandmonitoring.contract.dto;

import com.ondemandmonitoring.contract.enums.CancellationResponsibility;
import jakarta.validation.constraints.*;

public record ContractCancellationDecision(
    @PositiveOrZero long expectedVersion,
    @NotNull CancellationResponsibility responsibility,
    @NotBlank @Size(min = 10, max = 2000) String basis,
    boolean customerLiabilityAcknowledged) {}
