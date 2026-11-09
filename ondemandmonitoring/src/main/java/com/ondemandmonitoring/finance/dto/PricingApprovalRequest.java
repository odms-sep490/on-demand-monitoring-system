package com.ondemandmonitoring.finance.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PricingApprovalRequest(@NotNull @Min(1) Long pricingRevision, boolean approved,
        @Size(max = 2000) String note) {}
