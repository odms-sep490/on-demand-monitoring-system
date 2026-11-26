package com.ondemandmonitoring.inspectionreport.dto;

import com.ondemandmonitoring.inspectionreport.ReportDocument;
import java.util.List;

public record ReportRewriteResponse(
        ReportDocument document,
        String model,
        List<String> warnings,
        boolean changed,
        String skill,
        long latencyMs
) {}
