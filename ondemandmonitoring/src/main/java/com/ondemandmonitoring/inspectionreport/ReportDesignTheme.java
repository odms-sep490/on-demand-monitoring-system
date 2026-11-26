package com.ondemandmonitoring.inspectionreport;

import java.awt.Color;

/**
 * Controlled design themes for the PDF Inspection Report engine.
 * LLM content never supplies styling; only these vetted theme tokens drive layout.
 */
public enum ReportDesignTheme {
    MODERN_ENTERPRISE(
            "modern-enterprise",
            "Modern Enterprise",
            new Color(15, 23, 42),      // primaryNavy
            new Color(29, 78, 216),     // accentBlue
            new Color(239, 246, 255),   // accentSoftBg
            new Color(71, 85, 105),     // bodyMuted
            new Color(226, 232, 240),   // borderHairline
            new Color(248, 250, 252),   // surfaceTint
            new Color(219, 234, 254),   // tableHeaderBg
            CoverStyle.ENTERPRISE_BAND,
            false),
    CINEMATIC_AERIAL_EDITORIAL(
            "cinematic-aerial-editorial",
            "Cinematic Aerial Editorial",
            new Color(11, 25, 44),      // primaryNavy
            new Color(2, 132, 199),     // accentBlue (aerial sky)
            new Color(240, 249, 255),   // accentSoftBg
            new Color(71, 85, 105),     // bodyMuted
            new Color(203, 213, 225),   // borderHairline
            new Color(248, 250, 252),   // surfaceTint
            new Color(224, 242, 254),   // tableHeaderBg
            CoverStyle.EDITORIAL_HERO,
            false),
    TECHNICAL_INSPECTION(
            "technical-inspection",
            "Technical Inspection",
            new Color(17, 24, 39),      // primaryNavy
            new Color(30, 64, 175),     // accentBlue (engineering cobalt)
            new Color(238, 242, 255),   // accentSoftBg
            new Color(75, 85, 99),      // bodyMuted
            new Color(209, 213, 219),   // borderHairline
            new Color(249, 250, 251),   // surfaceTint
            new Color(229, 231, 235),   // tableHeaderBg
            CoverStyle.TECHNICAL_SPEC,
            true);

    public enum CoverStyle {
        ENTERPRISE_BAND,
        EDITORIAL_HERO,
        TECHNICAL_SPEC
    }

    public final String code;
    public final String displayName;
    public final Color primaryNavy;
    public final Color accentBlue;
    public final Color accentSoftBg;
    public final Color bodyMuted;
    public final Color borderHairline;
    public final Color surfaceTint;
    public final Color tableHeaderBg;
    public final CoverStyle coverStyle;
    public final boolean numberedHeadings;

    // Semantic chart & status palette (print-distinguishable luminance)
    public static final Color INK_BODY = new Color(30, 41, 59);
    public static final Color STATUS_PASS = new Color(21, 128, 61);
    public static final Color STATUS_WARNING = new Color(180, 83, 9);
    public static final Color STATUS_FAIL = new Color(185, 28, 28);
    public static final Color STATUS_NEUTRAL = new Color(100, 116, 139);
    public static final Color LIMITATION_BG = new Color(255, 251, 235);
    public static final Color LIMITATION_BORDER = new Color(253, 230, 138);

    ReportDesignTheme(
            String code,
            String displayName,
            Color primaryNavy,
            Color accentBlue,
            Color accentSoftBg,
            Color bodyMuted,
            Color borderHairline,
            Color surfaceTint,
            Color tableHeaderBg,
            CoverStyle coverStyle,
            boolean numberedHeadings) {
        this.code = code;
        this.displayName = displayName;
        this.primaryNavy = primaryNavy;
        this.accentBlue = accentBlue;
        this.accentSoftBg = accentSoftBg;
        this.bodyMuted = bodyMuted;
        this.borderHairline = borderHairline;
        this.surfaceTint = surfaceTint;
        this.tableHeaderBg = tableHeaderBg;
        this.coverStyle = coverStyle;
        this.numberedHeadings = numberedHeadings;
    }
}
