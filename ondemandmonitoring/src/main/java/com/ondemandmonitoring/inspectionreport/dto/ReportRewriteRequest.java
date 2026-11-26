package com.ondemandmonitoring.inspectionreport.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ReportRewriteRequest(
        @Min(0) long revision,
        @NotNull String instruction
) {}
