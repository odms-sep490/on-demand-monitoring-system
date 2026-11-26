package com.ondemandmonitoring.inspectionreport;

import java.util.List;

/**
 * Immutable render context for generating an inspection report PDF from a frozen snapshot.
 */
public record ReportRenderContext(
        String missionId,
        String missionCode,
        String serviceName,
        long revision,
        String approvedBy,
        String approvedAt,
        String orderCode,
        String scopeLabel,
        String locationText,
        ReportDocument document,
        ReportEvidenceService.Collection evidenceCollection,
        List<VerifiedBeforeAfterPair> beforeAfterPairs,
        ReportDesignTheme theme,
        String rendererVersion) {

    public static final String CURRENT_RENDERER_VERSION = "pdfbox-design-v2";

    /**
     * Explicit verified before/after pair contract.
     * Production leaves this list empty because no verified Mission before/after pairing
     * contract exists yet; QA fixtures may populate it with explicitly labeled data.
     */
    public record VerifiedBeforeAfterPair(
            String title,
            String beforeMediaId,
            String beforeLabel,
            String beforeCapturedAt,
            String afterMediaId,
            String afterLabel,
            String afterCapturedAt,
            String verifiedNote) {}

    public ReportRenderContext {
        beforeAfterPairs = beforeAfterPairs == null ? List.of() : List.copyOf(beforeAfterPairs);
        theme = theme == null ? ReportDesignTheme.CINEMATIC_AERIAL_EDITORIAL : theme;
        rendererVersion = (rendererVersion == null || rendererVersion.isBlank())
                ? CURRENT_RENDERER_VERSION
                : rendererVersion;
    }

    public static ReportRenderContext basic(
            String missionId,
            String missionCode,
            String serviceName,
            long revision,
            String approvedBy,
            String approvedAt,
            ReportDocument document) {
        return new ReportRenderContext(
                missionId,
                missionCode,
                serviceName,
                revision,
                approvedBy,
                approvedAt,
                null,
                null,
                null,
                document,
                null,
                List.of(),
                ReportDesignTheme.CINEMATIC_AERIAL_EDITORIAL,
                CURRENT_RENDERER_VERSION);
    }
}
