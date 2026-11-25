package com.ondemandmonitoring.inspectionreport.dto;

import com.ondemandmonitoring.inspectionreport.ReportDocument;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ReportEditRequest(
        @Min(0) long revision,
        @NotNull @Valid ReportDocument document
) {}
