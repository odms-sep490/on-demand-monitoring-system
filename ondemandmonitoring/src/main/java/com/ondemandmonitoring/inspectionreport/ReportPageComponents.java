package com.ondemandmonitoring.inspectionreport;

import com.ondemandmonitoring.media.domain.MediaAsset;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Reusable page component system for OnDemand Monitor AI Inspection Reports.
 *
 * Components implemented:
 * COVER_PAGE, MISSION_INFORMATION, KPI_OVERVIEW, EXECUTIVE_SUMMARY, INSPECTION_SCOPE,
 * DATA_VISUALIZATION, PHOTO_OVERVIEW, FINDING_DETAIL, BEFORE_AFTER_COMPARISON,
 * EVIDENCE_GALLERY, LIMITATIONS, CONCLUSION, APPROVAL_METADATA, APPENDIX.
 *
 * Rules:
 * - Never renders empty components or fabricates filler data.
 * - Never stretches or crops away evidence photographs.
 * - Never equates NOT_ANALYZED with PASS or labels uncategorized findings as defects.
 */
final class ReportPageComponents {
    private static final DateTimeFormatter VN_TIME =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    record LoadedPhoto(
            String mediaId,
            String role,
            String caption,
            String capturedAtLabel,
            String qualityStatus,
            BufferedImage bitmap) {}

    record AppendixRow(
            int figureNo,
            String mediaId,
            String role,
            String capturedAt,
            String qualityStatus,
            String sectionTitle) {}

    private final PdfCanvas c;
    private final ReportRenderContext ctx;
    private final ReportEvidenceService evidenceService;
    private final Map<String, ReportEvidenceService.Evidence> evidenceById = new LinkedHashMap<>();
    private final Map<String, LoadedPhoto> photoCache = new LinkedHashMap<>();
    private final List<AppendixRow> appendixRows = new ArrayList<>();
    private int figureNumber = 0;

    ReportPageComponents(PdfCanvas canvas, ReportRenderContext ctx, ReportEvidenceService evidenceService) {
        this.c = canvas;
        this.ctx = ctx;
        this.evidenceService = evidenceService;
        if (ctx.evidenceCollection() != null && ctx.evidenceCollection().images() != null) {
            for (var item : ctx.evidenceCollection().images()) {
                evidenceById.put(item.mediaId(), item);
            }
        }
    }

    void renderAll() throws IOException {
        renderCoverPage();
        c.newPage(false);
        renderMissionInformation();
        renderKpiOverview();
        renderExecutiveSummary();
        renderInspectionScope();
        renderDataVisualization();
        renderSections();
        renderBeforeAfterComparison();
        renderLimitations();
        renderConclusion();
        renderApprovalMetadata();
        renderAppendix();
        c.stampHeadersAndFooters(ctx);
    }

    // =========================================================================
    // 1. COVER_PAGE (3 distinct concepts via ReportDesignTheme.CoverStyle)
    // =========================================================================

    void renderCoverPage() throws IOException {
        c.newPage(true);
        var doc = ctx.document();
        var theme = c.theme;
        LoadedPhoto hero = selectCoverHeroPhoto();
        String statusBadge = (ctx.approvedBy() != null && !ctx.approvedBy().isBlank())
                ? "ĐÃ PHÊ DUYỆT · PHIÊN BẢN " + ctx.revision()
                : "BẢN XEM TRƯỚC · PHIÊN BẢN " + ctx.revision();

        switch (theme.coverStyle) {
            case ENTERPRISE_BAND -> renderEnterpriseCover(doc, hero, statusBadge);
            case EDITORIAL_HERO -> renderEditorialCover(doc, hero, statusBadge);
            case TECHNICAL_SPEC -> renderTechnicalCover(doc, hero, statusBadge);
        }
    }

    private void renderEnterpriseCover(ReportDocument doc, LoadedPhoto hero, String statusBadge) throws IOException {
        var t = c.theme;
        // Top clean accent bar (no dark ink flood — pure white page with restrained top stripe)
        c.fillRect(0, PdfCanvas.PAGE_H - 8f, PdfCanvas.PAGE_W, 8f, t.accentBlue);

        // Brand & badge row
        c.drawTextRaw("ONDEMAND MONITOR", PdfCanvas.MARGIN_X, c.y - 11f, 10.5f, c.bold, t.primaryNavy);
        float bw = c.textWidth(statusBadge, 8f, c.bold) + 18f;
        float bx = PdfCanvas.PAGE_W - PdfCanvas.MARGIN_X - bw;
        c.fillAndStrokeRect(bx, c.y - 16f, bw, 18f, t.accentSoftBg, t.accentBlue, 0.75f);
        c.drawTextRaw(statusBadge, bx + 9f, c.y - 10f, 8f, c.bold, t.accentBlue);
        c.space(26f);
        c.hLine(PdfCanvas.MARGIN_X, PdfCanvas.MARGIN_X + PdfCanvas.CONTENT_W, c.y, t.borderHairline, 0.8f);
        c.space(22f);

        // Eyebrow & Title
        c.drawTextRaw("BÁO CÁO GIÁM SÁT BẰNG DRONE · " + ctx.serviceName().toUpperCase(Locale.ROOT),
                PdfCanvas.MARGIN_X, c.y - 8.5f, 8.5f, c.bold, t.accentBlue);
        c.space(16f);
        c.drawParagraph(doc.reportTitle(), 23f, 29f, PdfCanvas.MARGIN_X, PdfCanvas.CONTENT_W, c.bold, t.primaryNavy);
        c.space(12f);

        // Framed Hero Photo (aspect-ratio preserved, zero crop)
        if (hero != null) {
            drawCoverHeroFrame(hero, PdfCanvas.CONTENT_W, 275f, true);
            c.space(18f);
        }

        // Enterprise 2-column Metadata Card
        drawCoverMetadataBox();
        drawCoverDisclaimer();
    }

    private void renderEditorialCover(ReportDocument doc, LoadedPhoto hero, String statusBadge) throws IOException {
        var t = c.theme;
        // Minimal editorial top strip
        c.drawTextRaw("ONDEMAND MONITOR", PdfCanvas.MARGIN_X, c.y - 10f, 9.5f, c.bold, t.primaryNavy);
        c.drawTextRaw("AERIAL INSPECTION EDITION", PdfCanvas.MARGIN_X + 118f, c.y - 10f, 8f, c.regular, t.bodyMuted);
        float sw = c.textWidth(statusBadge, 7.8f, c.bold);
        c.drawTextRaw(statusBadge, PdfCanvas.PAGE_W - PdfCanvas.MARGIN_X - sw, c.y - 10f, 7.8f, c.bold, t.accentBlue);
        c.space(18f);
        c.hLine(PdfCanvas.MARGIN_X, PdfCanvas.MARGIN_X + PdfCanvas.CONTENT_W, c.y, t.primaryNavy, 1.4f);
        c.space(22f);

        // Editorial display title with generous breathing room
        c.drawTextRaw(ctx.serviceName().toUpperCase(Locale.ROOT), PdfCanvas.MARGIN_X, c.y - 8.5f, 8.5f, c.bold, t.accentBlue);
        c.space(14f);
        c.drawParagraph(doc.reportTitle(), 25f, 31f, PdfCanvas.MARGIN_X, PdfCanvas.CONTENT_W, c.bold, t.primaryNavy);
        c.space(6f);
        c.fillRect(PdfCanvas.MARGIN_X, c.y - 2.5f, 56f, 2.5f, t.accentBlue);
        c.space(18f);

        // Large Cinematic Editorial Hero Photograph
        if (hero != null) {
            drawCoverHeroFrame(hero, PdfCanvas.CONTENT_W, 305f, false);
            c.space(18f);
        }

        // Crisp 4-column editorial metadata strip
        drawEditorialMetadataStrip();
        drawCoverDisclaimer();
    }

    private void renderTechnicalCover(ReportDocument doc, LoadedPhoto hero, String statusBadge) throws IOException {
        var t = c.theme;
        // Engineering header registration block
        float headerH = 34f;
        c.fillAndStrokeRect(PdfCanvas.MARGIN_X, c.y - headerH, PdfCanvas.CONTENT_W, headerH, t.surfaceTint, t.primaryNavy, 1.0f);
        c.fillRect(PdfCanvas.MARGIN_X, c.y - headerH, 5f, headerH, t.accentBlue);
        c.drawTextRaw("ONDEMAND MONITOR // HỒ SƠ KỸ THUẬT GIÁM SÁT DRONE",
                PdfCanvas.MARGIN_X + 14f, c.y - 14f, 8.8f, c.bold, t.primaryNavy);
        c.drawTextRaw("DOC-ID: " + ctx.missionCode() + "-R" + ctx.revision() + "  |  " + statusBadge,
                PdfCanvas.MARGIN_X + 14f, c.y - 26f, 7.8f, c.regular, t.bodyMuted);
        c.space(headerH + 18f);

        // Technical Title
        c.drawParagraph(doc.reportTitle(), 21f, 27f, PdfCanvas.MARGIN_X, PdfCanvas.CONTENT_W, c.bold, t.primaryNavy);
        c.space(12f);

        // Engineering Spec Table right below title
        drawCoverMetadataBox();
        c.space(14f);

        // Framed Technical Reference Plate
        if (hero != null) {
            drawCoverHeroFrame(hero, PdfCanvas.CONTENT_W, 255f, true);
        }
        drawCoverDisclaimer();
    }

    private void drawCoverHeroFrame(LoadedPhoto hero, float maxW, float maxH, boolean bordered) throws IOException {
        float[] fit = c.fitInside(hero.bitmap().getWidth(), hero.bitmap().getHeight(), maxW - 12f, maxH - 26f);
        float drawW = fit[0];
        float drawH = fit[1];
        float frameH = drawH + 28f;
        float frameBottom = c.y - frameH;

        if (bordered) {
            c.fillAndStrokeRect(PdfCanvas.MARGIN_X, frameBottom, maxW, frameH, c.theme.surfaceTint, c.theme.borderHairline, 0.8f);
        } else {
            c.fillRect(PdfCanvas.MARGIN_X, frameBottom, maxW, frameH, c.theme.surfaceTint);
            c.hLine(PdfCanvas.MARGIN_X, PdfCanvas.MARGIN_X + maxW, frameBottom, c.theme.borderHairline, 0.7f);
        }
        float imgX = PdfCanvas.MARGIN_X + (maxW - drawW) / 2f;
        float imgBottom = frameBottom + 22f;
        c.drawImageAt(hero.bitmap(), imgX, imgBottom, drawW, drawH);
        c.strokeRect(imgX, imgBottom, drawW, drawH, c.theme.borderHairline, 0.5f);

        String metaLine = "Ảnh đại diện nhiệm vụ · " + hero.caption();
        if (metaLine.length() > 96) metaLine = metaLine.substring(0, 93) + "…";
        c.drawTextRaw(metaLine, PdfCanvas.MARGIN_X + 8f, frameBottom + 7.5f, 8f, c.italic, c.theme.bodyMuted);
        c.y = frameBottom;
    }

    private void drawCoverMetadataBox() throws IOException {
        var t = c.theme;
        String[][] pairs = new String[][]{
                {"Mã nhiệm vụ", ctx.missionCode(), "Dịch vụ", ctx.serviceName()},
                {"Phiên bản snapshot", "v" + ctx.revision(), "Trạng thái duyệt", Objects.toString(ctx.approvedBy(), "Chưa phê duyệt")},
                {"Thời điểm duyệt", formatTimeString(ctx.approvedAt(), "Bản nháp xem trước"), "Bộ dựng PDF", ctx.rendererVersion()}
        };
        float rowH = 24f;
        float boxH = pairs.length * rowH + 8f;
        float bottom = c.y - boxH;
        c.fillAndStrokeRect(PdfCanvas.MARGIN_X, bottom, PdfCanvas.CONTENT_W, boxH, t.surfaceTint, t.borderHairline, 0.75f);

        float halfW = PdfCanvas.CONTENT_W / 2f;
        float ry = c.y - 4f;
        for (int i = 0; i < pairs.length; i++) {
            var p = pairs[i];
            if (i > 0) c.hLine(PdfCanvas.MARGIN_X + 10f, PdfCanvas.MARGIN_X + PdfCanvas.CONTENT_W - 10f, ry, t.borderHairline, 0.5f);
            c.drawTextRaw(p[0], PdfCanvas.MARGIN_X + 12f, ry - 15f, 8.2f, c.bold, t.bodyMuted);
            c.drawTextRaw(truncate(p[1], 32), PdfCanvas.MARGIN_X + 102f, ry - 15f, 8.8f, c.bold, t.primaryNavy);

            c.drawTextRaw(p[2], PdfCanvas.MARGIN_X + halfW + 10f, ry - 15f, 8.2f, c.bold, t.bodyMuted);
            c.drawTextRaw(truncate(p[3], 32), PdfCanvas.MARGIN_X + halfW + 96f, ry - 15f, 8.8f, c.regular, ReportDesignTheme.INK_BODY);
            ry -= rowH;
        }
        c.y = bottom;
    }

    private void drawEditorialMetadataStrip() throws IOException {
        var t = c.theme;
        float stripH = 52f;
        float bottom = c.y - stripH;
        c.hLine(PdfCanvas.MARGIN_X, PdfCanvas.MARGIN_X + PdfCanvas.CONTENT_W, c.y, t.borderHairline, 0.8f);
        c.hLine(PdfCanvas.MARGIN_X, PdfCanvas.MARGIN_X + PdfCanvas.CONTENT_W, bottom, t.borderHairline, 0.8f);

        String[][] cols = new String[][]{
                {"NHIỆM VỤ", ctx.missionCode()},
                {"PHIÊN BẢN", "Snapshot v" + ctx.revision()},
                {"PHÊ DUYỆT", Objects.toString(ctx.approvedBy(), "Chưa phê duyệt")},
                {"THỜI ĐIỂM", formatTimeString(ctx.approvedAt(), "Bản nháp")}
        };
        float colW = PdfCanvas.CONTENT_W / cols.length;
        for (int i = 0; i < cols.length; i++) {
            float cx = PdfCanvas.MARGIN_X + i * colW;
            c.drawTextRaw(cols[i][0], cx + 4f, c.y - 16f, 7.4f, c.bold, t.accentBlue);
            c.drawTextRaw(truncate(cols[i][1], 22), cx + 4f, c.y - 34f, 9.5f, c.bold, t.primaryNavy);
        }
        c.y = bottom;
    }

    private void drawCoverDisclaimer() throws IOException {
        String note = "Báo cáo dựa trên bằng chứng ảnh đã được duyệt và nội dung do con người rà soát. Không thay thế kiểm định kỹ thuật hoặc chứng nhận chuyên môn độc lập.";
        float bottomY = 56f;
        float boxH = 38f;
        c.fillAndStrokeRect(PdfCanvas.MARGIN_X, bottomY, PdfCanvas.CONTENT_W, boxH, c.theme.accentSoftBg, c.theme.borderHairline, 0.6f);
        c.fillRect(PdfCanvas.MARGIN_X, bottomY, 3f, boxH, c.theme.accentBlue);
        var lines = c.wrap(note, 8.2f, PdfCanvas.CONTENT_W - 22f, c.regular);
        float ty = bottomY + boxH - 12f;
        for (String line : lines) {
            c.drawTextRaw(line, PdfCanvas.MARGIN_X + 11f, ty, 8.2f, c.regular, c.theme.bodyMuted);
            ty -= 12f;
        }
    }

    // =========================================================================
    // 2. MISSION_INFORMATION
    // =========================================================================

    void renderMissionInformation() throws IOException {
        c.drawSectionHeading("Thông tin nhiệm vụ & hồ sơ", "MISSION INFORMATION");
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Mã nhiệm vụ", ctx.missionCode(), "Dịch vụ giám sát", ctx.serviceName()});
        rows.add(new String[]{
                "Phiên bản báo cáo", "v" + ctx.revision() + " (" + ctx.rendererVersion() + ")",
                "Trạng thái", (ctx.approvedBy() != null && !ctx.approvedBy().isBlank()) ? "Đã phê duyệt" : "Bản xem trước"
        });
        if ((ctx.orderCode() != null && !ctx.orderCode().isBlank()) || (ctx.scopeLabel() != null && !ctx.scopeLabel().isBlank())) {
            rows.add(new String[]{
                    "Mã đơn hàng", Objects.toString(ctx.orderCode(), "—"),
                    "Phạm vi công việc", Objects.toString(ctx.scopeLabel(), "Theo phạm vi nhiệm vụ")
            });
        }
        if (ctx.locationText() != null && !ctx.locationText().isBlank()) {
            rows.add(new String[]{"Địa điểm", ctx.locationText(), "Thời điểm duyệt", formatTimeString(ctx.approvedAt(), "Chưa chốt")});
        }
        float rowH = 22f;
        float boxH = rows.size() * rowH + 6f;
        c.ensure(boxH + 10f);
        float bottom = c.y - boxH;
        c.fillAndStrokeRect(PdfCanvas.MARGIN_X, bottom, PdfCanvas.CONTENT_W, boxH, c.theme.surfaceTint, c.theme.borderHairline, 0.7f);
        float halfW = PdfCanvas.CONTENT_W / 2f;
        float ry = c.y - 3f;
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) c.hLine(PdfCanvas.MARGIN_X + 8f, PdfCanvas.MARGIN_X + PdfCanvas.CONTENT_W - 8f, ry, c.theme.borderHairline, 0.5f);
            var r = rows.get(i);
            c.drawTextRaw(r[0], PdfCanvas.MARGIN_X + 10f, ry - 14f, 8f, c.bold, c.theme.bodyMuted);
            c.drawTextRaw(truncate(r[1], 36), PdfCanvas.MARGIN_X + 92f, ry - 14f, 8.2f, c.bold, c.theme.primaryNavy);
            c.drawTextRaw(r[2], PdfCanvas.MARGIN_X + halfW + 8f, ry - 14f, 8f, c.bold, c.theme.bodyMuted);
            c.drawTextRaw(truncate(r[3], 36), PdfCanvas.MARGIN_X + halfW + 90f, ry - 14f, 8.2f, c.regular, ReportDesignTheme.INK_BODY);
            ry -= rowH;
        }
        c.y = bottom - 12f;
    }

    // =========================================================================
    // 3. KPI_OVERVIEW (strictly derived from real snapshot data)
    // =========================================================================

    void renderKpiOverview() throws IOException {
        var doc = ctx.document();
        Set<String> selectedMediaIds = new LinkedHashSet<>();
        Set<String> citedFindings = new LinkedHashSet<>();
        for (var s : doc.sections()) {
            for (var img : s.images()) selectedMediaIds.add(img.mediaId());
            citedFindings.addAll(s.sourceFindingIds());
        }
        if (selectedMediaIds.isEmpty()) return;

        Map<String, Integer> qualityCounts = countSelectedQuality(selectedMediaIds);
        int passCount = qualityCounts.getOrDefault("PASS", 0);
        int totalWithSignal = qualityCounts.values().stream().mapToInt(Integer::intValue).sum();
        String qualityKpiValue = totalWithSignal > 0
                ? passCount + " / " + totalWithSignal
                : "Chưa phân tích";
        String qualityKpiSub = totalWithSignal > 0
                ? "Đạt chuẩn chất lượng (PASS)"
                : "Chưa có tín hiệu phân tích";

        String[][] cards = new String[][]{
                {String.valueOf(selectedMediaIds.size()), "ẢNH ĐƯỢC CHỌN", "Ảnh bằng chứng đã duyệt"},
                {String.valueOf(citedFindings.size()), "ĐÁNH GIÁ NGUỒN", "Hồ sơ đã xác minh"},
                {String.valueOf(doc.sections().size()), "MỤC NỘI DUNG", "Mục ghi nhận hiện trạng"},
                {qualityKpiValue, "CHẤT LƯỢNG ẢNH", qualityKpiSub}
        };

        float cardH = 62f;
        c.ensure(cardH + 14f);
        float gap = 10f;
        float cardW = (PdfCanvas.CONTENT_W - gap * (cards.length - 1)) / cards.length;
        float bottom = c.y - cardH;

        for (int i = 0; i < cards.length; i++) {
            float x = PdfCanvas.MARGIN_X + i * (cardW + gap);
            c.fillAndStrokeRect(x, bottom, cardW, cardH, Color.WHITE, c.theme.borderHairline, 0.75f);
            c.fillRect(x, c.y - 3f, cardW, 3f, i == 0 ? c.theme.accentBlue : c.theme.primaryNavy);
            c.drawTextRaw(cards[i][1], x + 9f, c.y - 16f, 7.2f, c.bold, c.theme.bodyMuted);
            c.drawTextRaw(cards[i][0], x + 9f, c.y - 38f, 16f, c.bold, c.theme.primaryNavy);
            c.drawTextRaw(cards[i][2], x + 9f, bottom + 8f, 7.1f, c.regular, c.theme.bodyMuted);
        }
        c.y = bottom - 14f;
    }

    // =========================================================================
    // 4. EXECUTIVE_SUMMARY
    // =========================================================================

    void renderExecutiveSummary() throws IOException {
        String text = ctx.document().executiveSummary();
        if (text == null || text.isBlank()) return;
        c.drawSectionHeading("Tóm tắt điều hành", "EXECUTIVE SUMMARY");
        var lines = c.wrap(text, 10.2f, PdfCanvas.CONTENT_W - 24f, c.regular);
        float boxH = lines.size() * 15.5f + 18f;
        if (boxH <= c.remainingHeight()) {
            float bottom = c.y - boxH;
            c.fillAndStrokeRect(PdfCanvas.MARGIN_X, bottom, PdfCanvas.CONTENT_W, boxH, c.theme.accentSoftBg, c.theme.borderHairline, 0.7f);
            c.fillRect(PdfCanvas.MARGIN_X, bottom, 3.5f, boxH, c.theme.accentBlue);
            float ty = c.y - 9f;
            for (String line : lines) {
                c.drawTextRaw(line, PdfCanvas.MARGIN_X + 13f, ty - 10.2f, 10.2f, c.regular, c.theme.primaryNavy);
                ty -= 15.5f;
            }
            c.y = bottom - 12f;
        } else {
            c.drawBody(text);
            c.space(8f);
        }
    }

    // =========================================================================
    // 5. INSPECTION_SCOPE
    // =========================================================================

    void renderInspectionScope() throws IOException {
        String scope = ctx.document().inspectionScope();
        String method = ctx.document().methodology();
        if ((scope == null || scope.isBlank()) && (method == null || method.isBlank())) return;
        c.drawSectionHeading("Phạm vi & phương pháp giám sát", "INSPECTION SCOPE");
        if (scope != null && !scope.isBlank()) {
            c.ensure(30f);
            c.drawTextRaw("Phạm vi thực hiện", PdfCanvas.MARGIN_X, c.y - 10f, 10f, c.bold, c.theme.primaryNavy);
            c.space(15f);
            c.drawBody(scope);
            c.space(6f);
        }
        if (method != null && !method.isBlank()) {
            c.ensure(30f);
            c.drawTextRaw("Phương pháp ghi nhận", PdfCanvas.MARGIN_X, c.y - 10f, 10f, c.bold, c.theme.primaryNavy);
            c.space(15f);
            c.drawBody(method);
            c.space(8f);
        }
    }

    // =========================================================================
    // 6. DATA_VISUALIZATION (Vector quality distribution chart — real data only)
    // =========================================================================

    void renderDataVisualization() throws IOException {
        Set<String> selectedIds = new LinkedHashSet<>();
        for (var s : ctx.document().sections()) {
            for (var img : s.images()) selectedIds.add(img.mediaId());
        }
        Map<String, Integer> counts = countSelectedQuality(selectedIds);
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        if (total == 0) return; // Omit chart cleanly when no quality records exist

        c.drawSectionHeading("Phân bố chất lượng ảnh nguồn", "DATA VISUALIZATION");
        record Bucket(String key, String label, int count, Color color) {}
        List<Bucket> buckets = List.of(
                new Bucket("PASS", "Đạt chuẩn (PASS)", counts.getOrDefault("PASS", 0), ReportDesignTheme.STATUS_PASS),
                new Bucket("WARNING", "Cần lưu ý (WARNING)", counts.getOrDefault("WARNING", 0), ReportDesignTheme.STATUS_WARNING),
                new Bucket("FAIL", "Không đạt (FAIL)", counts.getOrDefault("FAIL", 0), ReportDesignTheme.STATUS_FAIL),
                new Bucket("NOT_ANALYZED", "Chưa phân tích (NOT_ANALYZED)", counts.getOrDefault("NOT_ANALYZED", 0), ReportDesignTheme.STATUS_NEUTRAL)
        );
        List<Bucket> active = buckets.stream().filter(b -> b.count() > 0).toList();
        if (active.isEmpty()) return;

        float chartBoxH = 38f + active.size() * 20f + 24f;
        c.ensure(chartBoxH + 10f);
        float bottom = c.y - chartBoxH;
        c.fillAndStrokeRect(PdfCanvas.MARGIN_X, bottom, PdfCanvas.CONTENT_W, chartBoxH, Color.WHITE, c.theme.borderHairline, 0.75f);

        // Segmented horizontal stacked bar (100% vector, print-clear)
        float barX = PdfCanvas.MARGIN_X + 14f;
        float barW = PdfCanvas.CONTENT_W - 28f;
        float barY = c.y - 28f;
        float barH = 14f;
        c.fillRect(barX, barY, barW, barH, c.theme.surfaceTint);
        float curX = barX;
        for (var b : active) {
            float segW = barW * ((float) b.count() / total);
            if (segW > 0.5f) {
                c.fillRect(curX, barY, segW, barH, b.color());
                c.strokeRect(curX, barY, segW, barH, Color.WHITE, 0.8f);
                curX += segW;
            }
        }
        c.strokeRect(barX, barY, barW, barH, c.theme.borderHairline, 0.6f);

        // Individual metric bars & counts
        float rowY = barY - 16f;
        for (var b : active) {
            c.fillRect(barX, rowY - 7f, 8f, 8f, b.color());
            c.drawTextRaw(b.label(), barX + 14f, rowY - 6.5f, 8.6f, c.bold, c.theme.primaryNavy);
            String valText = b.count() + " / " + total + " ảnh";
            float vw = c.textWidth(valText, 8.6f, c.bold);
            c.drawTextRaw(valText, barX + barW - vw, rowY - 6.5f, 8.6f, c.bold, ReportDesignTheme.INK_BODY);

            // Mini vector bar in middle
            float miniX = barX + 205f;
            float miniW = Math.max(40f, barW - 280f);
            c.fillRect(miniX, rowY - 6f, miniW, 6f, c.theme.surfaceTint);
            c.fillRect(miniX, rowY - 6f, miniW * ((float) b.count() / total), 6f, b.color());
            rowY -= 20f;
        }
        c.drawTextRaw("Lưu ý: Trạng thái NOT_ANALYZED nghĩa là chưa có tín hiệu phân tích chất lượng, không tương đương PASS.",
                barX, bottom + 8f, 7.6f, c.italic, c.theme.bodyMuted);
        c.y = bottom - 14f;
    }

    // =========================================================================
    // 7, 8, 10. PHOTO_OVERVIEW, FINDING_DETAIL, EVIDENCE_GALLERY
    // =========================================================================

    void renderSections() throws IOException {
        for (var section : ctx.document().sections()) {
            boolean hasFindings = section.sourceFindingIds() != null && !section.sourceFindingIds().isEmpty();
            String eyebrow = hasFindings ? "FINDING DETAIL" : "FIELD OBSERVATION";
            c.drawSectionHeading(section.title(), eyebrow);

            if (hasFindings) {
                drawFindingReferenceStrip(section.sourceFindingIds());
            }
            if (section.richText() == null) {
                c.drawBody(section.content());
                c.space(6f);
            } else {
                c.drawRichText(section.richText());
                c.space(4f);
            }

            if (section.images() != null && !section.images().isEmpty()) {
                renderSectionImages(section.title(), section.images());
            }
        }
    }

    private void drawFindingReferenceStrip(List<String> findingIds) throws IOException {
        String label = "Tham chiếu đánh giá đã xác minh: " + String.join(", ", findingIds);
        var lines = c.wrap(label, 8.3f, PdfCanvas.CONTENT_W - 20f, c.bold);
        float boxH = lines.size() * 12.5f + 10f;
        c.ensure(boxH + 6f);
        float bottom = c.y - boxH;
        c.fillAndStrokeRect(PdfCanvas.MARGIN_X, bottom, PdfCanvas.CONTENT_W, boxH, c.theme.accentSoftBg, c.theme.borderHairline, 0.6f);
        c.fillRect(PdfCanvas.MARGIN_X, bottom, 3f, boxH, c.theme.accentBlue);
        float ty = c.y - 6f;
        for (String line : lines) {
            c.drawTextRaw(line, PdfCanvas.MARGIN_X + 10f, ty - 8.3f, 8.3f, c.bold, c.theme.accentBlue);
            ty -= 12.5f;
        }
        c.y = bottom - 8f;
    }

    private void renderSectionImages(String sectionTitle, List<ReportDocument.Image> images) throws IOException {
        List<LoadedPhoto> loaded = new ArrayList<>();
        for (var img : images) {
            loaded.add(loadPhoto(img));
        }

        // First OVERVIEW photo (or single photo in a section) renders as large PHOTO_OVERVIEW feature plate
        int idx = 0;
        if (loaded.size() == 1 || "OVERVIEW".equals(loaded.getFirst().role())) {
            drawFeaturePhoto(sectionTitle, loaded.getFirst());
            idx = 1;
        }

        // Remaining images: if 2+ render in balanced 2-column EVIDENCE_GALLERY; if 1 render as feature plate
        if (loaded.size() - idx == 1) {
            drawFeaturePhoto(sectionTitle, loaded.get(idx));
        } else if (loaded.size() - idx >= 2) {
            drawEvidenceGallery(sectionTitle, loaded.subList(idx, loaded.size()));
        }
    }

    /** PHOTO_OVERVIEW — Large single editorial photo plate with atomic caption + metadata. */
    void drawFeaturePhoto(String sectionTitle, LoadedPhoto photo) throws IOException {
        int figNo = ++figureNumber;
        recordAppendix(figNo, photo, sectionTitle);

        float maxImgW = PdfCanvas.CONTENT_W - 16f;
        float maxImgH = 265f;
        float[] fit = c.fitInside(photo.bitmap().getWidth(), photo.bitmap().getHeight(), maxImgW, maxImgH);
        float drawW = fit[0], drawH = fit[1];

        String captionLine = "Hình " + figNo + ". " + photo.caption();
        var capLines = c.wrap(captionLine, 9.2f, PdfCanvas.CONTENT_W - 22f, c.bold);
        String metaLine = "Media ID: " + photo.mediaId() + "  ·  Vai trò: " + roleLabel(photo.role())
                + "  ·  Chụp lúc: " + photo.capturedAtLabel()
                + (photo.qualityStatus() != null ? "  ·  Chất lượng: " + photo.qualityStatus() : "");

        float totalH = drawH + 14f + capLines.size() * 13.5f + 16f + 10f;
        c.ensure(totalH + 6f);
        float bottom = c.y - totalH;

        c.fillAndStrokeRect(PdfCanvas.MARGIN_X, bottom, PdfCanvas.CONTENT_W, totalH, Color.WHITE, c.theme.borderHairline, 0.75f);
        float imgX = PdfCanvas.MARGIN_X + (PdfCanvas.CONTENT_W - drawW) / 2f;
        float imgBottom = c.y - 8f - drawH;
        c.drawImageAt(photo.bitmap(), imgX, imgBottom, drawW, drawH);
        c.strokeRect(imgX, imgBottom, drawW, drawH, c.theme.borderHairline, 0.5f);

        // Editorial left-accent caption block directly beneath photo (never splits across pages)
        float capTop = imgBottom - 8f;
        float capBlockH = capLines.size() * 13.5f + 13f;
        c.fillRect(PdfCanvas.MARGIN_X + 8f, capTop - capBlockH, 2.5f, capBlockH, c.theme.accentBlue);
        float ty = capTop;
        for (String line : capLines) {
            c.drawTextRaw(line, PdfCanvas.MARGIN_X + 16f, ty - 9.2f, 9.2f, c.bold, c.theme.primaryNavy);
            ty -= 13.5f;
        }
        c.drawTextRaw(truncate(metaLine, 105), PdfCanvas.MARGIN_X + 16f, ty - 8f, 7.6f, c.regular, c.theme.bodyMuted);
        c.y = bottom - 12f;
    }

    /** EVIDENCE_GALLERY — Balanced 2-column gallery keeping every photo+caption pair atomic. */
    void drawEvidenceGallery(String sectionTitle, List<LoadedPhoto> photos) throws IOException {
        float gap = 12f;
        float colW = (PdfCanvas.CONTENT_W - gap) / 2f;
        float maxImgW = colW - 12f;
        float maxImgH = 155f;

        for (int i = 0; i < photos.size(); i += 2) {
            LoadedPhoto left = photos.get(i);
            LoadedPhoto right = (i + 1 < photos.size()) ? photos.get(i + 1) : null;

            int leftFig = ++figureNumber;
            recordAppendix(leftFig, left, sectionTitle);
            int rightFig = right != null ? ++figureNumber : 0;
            if (right != null) recordAppendix(rightFig, right, sectionTitle);

            float[] lFit = c.fitInside(left.bitmap().getWidth(), left.bitmap().getHeight(), maxImgW, maxImgH);
            var lCap = c.wrap("Hình " + leftFig + ". " + left.caption(), 8.4f, colW - 16f, c.bold);
            float lH = lFit[1] + 12f + lCap.size() * 12f + 16f;

            float rH = 0f;
            float[] rFit = null;
            List<String> rCap = List.of();
            if (right != null) {
                rFit = c.fitInside(right.bitmap().getWidth(), right.bitmap().getHeight(), maxImgW, maxImgH);
                rCap = c.wrap("Hình " + rightFig + ". " + right.caption(), 8.4f, colW - 16f, c.bold);
                rH = rFit[1] + 12f + rCap.size() * 12f + 16f;
            }

            float rowH = Math.max(lH, rH) + 8f;
            c.ensure(rowH + 8f);
            float bottom = c.y - rowH;

            drawGalleryCard(PdfCanvas.MARGIN_X, bottom, colW, rowH, left, lFit, lCap);
            if (right != null) {
                drawGalleryCard(PdfCanvas.MARGIN_X + colW + gap, bottom, colW, rowH, right, rFit, rCap);
            }
            c.y = bottom - 12f;
        }
    }

    private void drawGalleryCard(
            float x, float bottom, float w, float h,
            LoadedPhoto photo, float[] fit, List<String> capLines) throws IOException {
        c.fillAndStrokeRect(x, bottom, w, h, Color.WHITE, c.theme.borderHairline, 0.7f);
        float topY = bottom + h;
        float imgX = x + (w - fit[0]) / 2f;
        float imgBottom = topY - 8f - fit[1];
        c.drawImageAt(photo.bitmap(), imgX, imgBottom, fit[0], fit[1]);
        c.strokeRect(imgX, imgBottom, fit[0], fit[1], c.theme.borderHairline, 0.45f);

        float ty = imgBottom - 6f;
        for (String line : capLines) {
            c.drawTextRaw(line, x + 8f, ty - 8.4f, 8.4f, c.bold, c.theme.primaryNavy);
            ty -= 12f;
        }
        String meta = truncate(photo.mediaId() + " · " + photo.capturedAtLabel(), 46);
        c.drawTextRaw(meta, x + 8f, bottom + 6f, 7.2f, c.regular, c.theme.bodyMuted);
    }

    // =========================================================================
    // 9. BEFORE_AFTER_COMPARISON (hidden unless verified pairs are supplied)
    // =========================================================================

    void renderBeforeAfterComparison() throws IOException {
        if (ctx.beforeAfterPairs().isEmpty()) return;
        // Keep heading together with at least the first comparison plate
        c.ensure(295f);
        c.drawSectionHeading("So sánh Trước / Sau đã xác minh", "BEFORE / AFTER COMPARISON");

        float gap = 12f;
        float colW = (PdfCanvas.CONTENT_W - gap) / 2f;
        float maxImgW = colW - 12f;
        float maxImgH = 165f;

        for (var pair : ctx.beforeAfterPairs()) {
            var beforeAsset = evidenceService.requireMedia(ctx.missionId(), pair.beforeMediaId());
            var afterAsset = evidenceService.requireMedia(ctx.missionId(), pair.afterMediaId());
            var beforeBmp = evidenceService.readImage(beforeAsset);
            var afterBmp = evidenceService.readImage(afterAsset);

            float[] bFit = c.fitInside(beforeBmp.getWidth(), beforeBmp.getHeight(), maxImgW, maxImgH);
            float[] aFit = c.fitInside(afterBmp.getWidth(), afterBmp.getHeight(), maxImgW, maxImgH);
            float imgH = Math.max(bFit[1], aFit[1]);
            var noteLines = c.wrap(pair.verifiedNote(), 8.6f, PdfCanvas.CONTENT_W - 20f, c.regular);
            float blockH = 26f + imgH + 34f + noteLines.size() * 13f + 12f;

            c.ensure(blockH + 10f);
            float bottom = c.y - blockH;
            c.fillAndStrokeRect(PdfCanvas.MARGIN_X, bottom, PdfCanvas.CONTENT_W, blockH, c.theme.surfaceTint, c.theme.borderHairline, 0.75f);

            c.drawTextRaw(pair.title(), PdfCanvas.MARGIN_X + 10f, c.y - 16f, 10f, c.bold, c.theme.primaryNavy);

            float imgTop = c.y - 26f;
            float leftX = PdfCanvas.MARGIN_X + 6f;
            float rightX = PdfCanvas.MARGIN_X + colW + gap + 6f;
            drawComparisonSlot(leftX, imgTop, colW - 12f, imgH, beforeBmp, bFit, "TRƯỚC · " + pair.beforeLabel(), pair.beforeMediaId() + " · " + pair.beforeCapturedAt());
            drawComparisonSlot(rightX, imgTop, colW - 12f, imgH, afterBmp, aFit, "SAU · " + pair.afterLabel(), pair.afterMediaId() + " · " + pair.afterCapturedAt());

            float ny = imgTop - imgH - 30f;
            for (String line : noteLines) {
                c.drawTextRaw(line, PdfCanvas.MARGIN_X + 10f, ny - 8.6f, 8.6f, c.regular, ReportDesignTheme.INK_BODY);
                ny -= 13f;
            }
            c.y = bottom - 12f;
        }
    }

    private void drawComparisonSlot(float x, float topY, float w, float slotH, BufferedImage bmp, float[] fit, String badge, String meta) throws IOException {
        float imgX = x + (w - fit[0]) / 2f;
        float imgBottom = topY - slotH + (slotH - fit[1]) / 2f;
        c.fillRect(x, topY - slotH, w, slotH, Color.WHITE);
        c.drawImageAt(bmp, imgX, imgBottom, fit[0], fit[1]);
        c.strokeRect(x, topY - slotH, w, slotH, c.theme.borderHairline, 0.6f);
        c.drawTextRaw(truncate(badge, 40), x + 2f, topY - slotH - 12f, 8f, c.bold, c.theme.accentBlue);
        c.drawTextRaw(truncate(meta, 46), x + 2f, topY - slotH - 23f, 7.2f, c.regular, c.theme.bodyMuted);
    }

    // =========================================================================
    // 11. LIMITATIONS
    // =========================================================================

    void renderLimitations() throws IOException {
        var items = ctx.document().limitations();
        if (items == null || items.isEmpty()) return;
        c.drawSectionHeading("Giới hạn & phạm vi miễn trừ", "LIMITATIONS");

        // Measure to decide boxed vs flowing list
        float estH = 16f;
        for (String item : items) {
            estH += c.wrap(item, 9.4f, PdfCanvas.CONTENT_W - 28f, c.regular).size() * 14f + 4f;
        }
        if (estH <= c.remainingHeight() && estH < 320f) {
            float bottom = c.y - estH;
            c.fillAndStrokeRect(PdfCanvas.MARGIN_X, bottom, PdfCanvas.CONTENT_W, estH,
                    ReportDesignTheme.LIMITATION_BG, ReportDesignTheme.LIMITATION_BORDER, 0.8f);
            float ty = c.y - 10f;
            for (String item : items) {
                var lines = c.wrap(item, 9.4f, PdfCanvas.CONTENT_W - 28f, c.regular);
                for (int i = 0; i < lines.size(); i++) {
                    if (i == 0) c.drawTextRaw("•", PdfCanvas.MARGIN_X + 10f, ty - 9.4f, 9.4f, c.bold, ReportDesignTheme.STATUS_WARNING);
                    c.drawTextRaw(lines.get(i), PdfCanvas.MARGIN_X + 20f, ty - 9.4f, 9.4f, c.regular, ReportDesignTheme.INK_BODY);
                    ty -= 14f;
                }
                ty -= 4f;
            }
            c.y = bottom - 12f;
        } else {
            c.drawBulletList(items);
            c.space(6f);
        }
    }

    // =========================================================================
    // 12. CONCLUSION (Recommendations + Conclusion)
    // =========================================================================

    void renderConclusion() throws IOException {
        var recs = ctx.document().recommendations();
        if (recs != null && !recs.isEmpty()) {
            c.drawSectionHeading("Khuyến nghị tiếp theo", "RECOMMENDATIONS");
            c.drawBulletList(recs);
            c.space(6f);
        }
        String conclusion = ctx.document().conclusion();
        if (conclusion != null && !conclusion.isBlank()) {
            c.drawSectionHeading("Kết luận", "CONCLUSION");
            c.drawBody(conclusion);
            c.space(10f);
        }
    }

    // =========================================================================
    // 13. APPROVAL_METADATA
    // =========================================================================

    void renderApprovalMetadata() throws IOException {
        float boxH = 74f;
        c.ensure(boxH + 24f);
        c.drawSectionHeading("Xác nhận & siêu dữ liệu phê duyệt", "APPROVAL METADATA");
        float bottom = c.y - boxH;
        c.fillAndStrokeRect(PdfCanvas.MARGIN_X, bottom, PdfCanvas.CONTENT_W, boxH, c.theme.surfaceTint, c.theme.borderHairline, 0.8f);
        c.fillRect(PdfCanvas.MARGIN_X, bottom, 3.5f, boxH, c.theme.primaryNavy);

        float halfW = PdfCanvas.CONTENT_W / 2f;
        c.drawTextRaw("Người phê duyệt", PdfCanvas.MARGIN_X + 12f, c.y - 16f, 8f, c.bold, c.theme.bodyMuted);
        c.drawTextRaw(Objects.toString(ctx.approvedBy(), "Chưa phê duyệt (Bản xem trước)"),
                PdfCanvas.MARGIN_X + 12f, c.y - 30f, 9.5f, c.bold, c.theme.primaryNavy);

        c.drawTextRaw("Thời điểm phê duyệt", PdfCanvas.MARGIN_X + halfW + 10f, c.y - 16f, 8f, c.bold, c.theme.bodyMuted);
        c.drawTextRaw(formatTimeString(ctx.approvedAt(), "Chưa phê duyệt"),
                PdfCanvas.MARGIN_X + halfW + 10f, c.y - 30f, 9.5f, c.bold, c.theme.primaryNavy);

        c.hLine(PdfCanvas.MARGIN_X + 12f, PdfCanvas.MARGIN_X + PdfCanvas.CONTENT_W - 12f, c.y - 42f, c.theme.borderHairline, 0.5f);
        c.drawTextRaw("Snapshot: v" + ctx.revision() + "  ·  Giao diện: " + c.theme.displayName + "  ·  Bộ dựng: " + ctx.rendererVersion(),
                PdfCanvas.MARGIN_X + 12f, c.y - 58f, 8f, c.regular, c.theme.bodyMuted);
        c.y = bottom - 14f;
    }

    // =========================================================================
    // 14. APPENDIX (Evidence manifest table with repeating header)
    // =========================================================================

    void renderAppendix() throws IOException {
        if (appendixRows.isEmpty()) return;
        c.drawSectionHeading("Phụ lục: Danh mục bằng chứng ảnh", "APPENDIX · EVIDENCE MANIFEST");

        List<List<PdfCanvas.TableCellSpec>> rows = new ArrayList<>();
        rows.add(List.of(
                new PdfCanvas.TableCellSpec("Hình", true, "center"),
                new PdfCanvas.TableCellSpec("Media ID", true, "left"),
                new PdfCanvas.TableCellSpec("Vai trò", true, "left"),
                new PdfCanvas.TableCellSpec("Thời điểm chụp", true, "left"),
                new PdfCanvas.TableCellSpec("Chất lượng", true, "center")
        ));
        for (var r : appendixRows) {
            rows.add(List.of(
                    new PdfCanvas.TableCellSpec("Hình " + r.figureNo(), false, "center"),
                    new PdfCanvas.TableCellSpec(r.mediaId(), false, "left"),
                    new PdfCanvas.TableCellSpec(roleLabel(r.role()), false, "left"),
                    new PdfCanvas.TableCellSpec(r.capturedAt(), false, "left"),
                    new PdfCanvas.TableCellSpec(Objects.toString(r.qualityStatus(), "NOT_ANALYZED"), false, "center")
            ));
        }
        float[] widths = new float[]{56f, 155f, 92f, 112f, PdfCanvas.CONTENT_W - 415f};
        c.drawStyledTable(rows, widths);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private LoadedPhoto selectCoverHeroPhoto() throws IOException {
        ReportDocument.Image fallback = null;
        for (var section : ctx.document().sections()) {
            for (var img : section.images()) {
                if (fallback == null) fallback = img;
                if ("OVERVIEW".equals(img.role())) {
                    return loadPhoto(img);
                }
            }
        }
        return fallback != null ? loadPhoto(fallback) : null;
    }

    private LoadedPhoto loadPhoto(ReportDocument.Image img) throws IOException {
        LoadedPhoto cached = photoCache.get(img.mediaId());
        if (cached != null) {
            return new LoadedPhoto(img.mediaId(), img.role(), img.caption(),
                    cached.capturedAtLabel(), cached.qualityStatus(), cached.bitmap());
        }
        MediaAsset asset = evidenceService.requireMedia(ctx.missionId(), img.mediaId());
        BufferedImage bmp;
        if ("VIDEO".equals(asset.getType())) {
            bmp = new BufferedImage(960, 540, BufferedImage.TYPE_INT_RGB);
            var g = bmp.createGraphics();
            g.setColor(new java.awt.Color(18, 35, 55)); g.fillRect(0, 0, 960, 540);
            g.setColor(java.awt.Color.WHITE); g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 48));
            g.drawString("VIDEO", 390, 245); g.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 20));
            g.drawString("Open the report studio to play this evidence", 260, 300); g.dispose();
        } else bmp = evidenceService.readImage(asset);
        var ev = evidenceById.get(img.mediaId());
        String quality = ev != null ? ev.qualityStatus() : null;
        String captured = asset.getCapturedAt() != null ? VN_TIME.format(asset.getCapturedAt()) : "Không rõ thời điểm";
        LoadedPhoto loaded = new LoadedPhoto(img.mediaId(), img.role(), ("VIDEO".equals(asset.getType()) ? "Video: " : "") + img.caption(), captured, quality, bmp);
        photoCache.put(img.mediaId(), loaded);
        return loaded;
    }

    private Map<String, Integer> countSelectedQuality(Set<String> selectedIds) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (evidenceById.isEmpty()) return counts;
        for (String id : selectedIds) {
            var ev = evidenceById.get(id);
            if (ev != null && ev.qualityStatus() != null && !ev.qualityStatus().isBlank()) {
                counts.merge(ev.qualityStatus().toUpperCase(Locale.ROOT), 1, Integer::sum);
            }
        }
        return counts;
    }

    private void recordAppendix(int figNo, LoadedPhoto photo, String sectionTitle) {
        appendixRows.add(new AppendixRow(figNo, photo.mediaId(), photo.role(),
                photo.capturedAtLabel(), photo.qualityStatus(), sectionTitle));
    }

    private static String roleLabel(String role) {
        return switch (Objects.toString(role, "")) {
            case "OVERVIEW" -> "Tổng quan";
            case "EVIDENCE" -> "Bằng chứng";
            case "SUPPORTING" -> "Bổ trợ";
            default -> "Ảnh nguồn";
        };
    }

    private static String formatTimeString(String raw, String fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return VN_TIME.format(Instant.parse(raw));
        } catch (Exception ignored) {
            return raw.length() > 19 ? raw.substring(0, 19).replace('T', ' ') : raw;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, Math.max(1, max - 1)) + "…";
    }
}
