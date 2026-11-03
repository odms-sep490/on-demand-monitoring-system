package com.ondemandmonitoring.delivery.dto.request;

import com.ondemandmonitoring.delivery.enums.ResultRevisionScope;
import com.ondemandmonitoring.delivery.enums.ResultRevisionStrategy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ResultRevisionDecisionRequest(
        @NotNull Long expectedVersion,
        @NotNull ResultRevisionScope scopeDecision,
        @NotNull ResultRevisionStrategy strategy,
        @NotBlank @Size(max = 2000) String reason,
        @Size(max = 2000) String managerInstructions) {}
