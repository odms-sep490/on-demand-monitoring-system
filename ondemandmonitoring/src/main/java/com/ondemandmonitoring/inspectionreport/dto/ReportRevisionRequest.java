package com.ondemandmonitoring.inspectionreport.dto;

import jakarta.validation.constraints.Min;

public record ReportRevisionRequest(
        @Min(0) long revision
) {}
