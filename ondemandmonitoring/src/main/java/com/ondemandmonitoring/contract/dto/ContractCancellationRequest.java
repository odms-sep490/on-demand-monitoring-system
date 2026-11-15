package com.ondemandmonitoring.contract.dto;

import com.ondemandmonitoring.contract.enums.CancellationResponsibility;
import jakarta.validation.constraints.*;
import java.util.UUID;

public record ContractCancellationRequest(
    @NotNull UUID clientRequestId,
    @PositiveOrZero long expectedVersion,
    @NotNull CancellationResponsibility responsibility,
    @NotBlank @Size(min = 10, max = 2000) String reason,
    @Size(max = 2000) String basis,
    boolean customerLiabilityAcknowledged) {}
