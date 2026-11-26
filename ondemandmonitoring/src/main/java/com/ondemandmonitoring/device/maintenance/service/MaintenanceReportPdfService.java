package com.ondemandmonitoring.device.maintenance.service;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.device.maintenance.dto.MaintenanceWorkflowDtos.*;
import com.ondemandmonitoring.device.maintenance.enums.AcceptanceDecisionType;
import com.ondemandmonitoring.device.maintenance.enums.HandoverDirection;
import com.ondemandmonitoring.device.maintenance.enums.HandoverStatus;
import com.ondemandmonitoring.device.maintenance.enums.MaintenanceReportStatus;
import com.ondemandmonitoring.device.maintenance.enums.MaintenanceWorkActionType;
import com.ondemandmonitoring.device.maintenance.enums.MaintenanceWorkStatus;
import com.ondemandmonitoring.device.maintenance.enums.PostCheckResult;
import com.ondemandmonitoring.inspectionreport.ReportDesignTheme;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Generates publication-grade, deterministic Vietnamese PDF maintenance reports
 * using Apache PDFBox 3 and bundled NotoSans UTF-8 fonts directly from verified workflow records.
 */
@Service
@RequiredArgsConstructor
public class MaintenanceReportPdfService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
            .withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    private static final float PAGE_W = PDRectangle.A4.getWidth();   // 595.28
    private static final float PAGE_H = PDRectangle.A4.getHeight();  // 841.89
    private static final float MARGIN_X = 42f;
    private static final float CONTENT_W = PAGE_W - MARGIN_X * 2;    // 511.28
    private static final float TOP_Y = PAGE_H - 56f;
    private static final float BOTTOM_Y = 48f;

    private static final ReportDesignTheme THEME = ReportDesignTheme.MODERN_ENTERPRISE;
    private static final Color WHITE = Color.WHITE;
    private static final Color PASS_BG = new Color(220, 252, 231);
    private static final Color WARN_BG = new Color(254, 243, 199);
    private static final Color FAIL_BG = new Color(254, 226, 226);
    private static final Color NEUTRAL_BG = new Color(241, 245, 249);
    private static final Color ZEBRA_BG = new Color(248, 250, 252);

    private final MaintenanceWorkflowService workflowService;

    public byte[] exportReportPdf(String ticketId, Integer requestedRevision) {
        MaintenanceWorkflowDetailResponse detail = workflowService.getWorkflowDetail(ticketId);
        ReportVersionResponse report = resolveReport(detail, requestedRevision);

        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDFont regular = loadFont(document, "fonts/NotoSans-Regular.ttf", Standard14Fonts.FontName.HELVETICA);
            PDFont bold = loadFont(document, "fonts/NotoSans-Bold.ttf", Standard14Fonts.FontName.HELVETICA_BOLD);
            PDFont italic = loadFont(document, "fonts/NotoSans-Italic.ttf", Standard14Fonts.FontName.HELVETICA_OBLIQUE);

            PDDocumentInformation info = document.getDocumentInformation();
            info.setTitle("Báo cáo bảo trì drone - " + detail.ticketCode());
            info.setAuthor("OnDemand Monitor");
            info.setSubject("Hồ sơ bảo trì & nghiệm thu drone " + detail.ticketCode() + " (Bản " + report.revision() + ")");
            info.setCreator("OnDemand Monitor Maintenance PDF Engine");

            PdfWriter w = new PdfWriter(document, regular, bold, italic);
            renderReport(w, detail, report);
            w.closeCurrentStream();
            renderRunningHeadersAndFooters(document, regular, bold, detail, report);

            document.save(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Không thể tạo file PDF báo cáo bảo trì.");
        }
    }

    private ReportVersionResponse resolveReport(MaintenanceWorkflowDetailResponse detail, Integer revision) {
        if (detail.reportVersions() == null || detail.reportVersions().isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Chưa có phiên bản báo cáo bảo trì nào cho phiếu này.");
        }
        if (revision == null) {
            return detail.reportVersions().getFirst();
        }
        return detail.reportVersions().stream()
                .filter(r -> revision.equals(r.revision()))
                .findFirst()
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "Không tìm thấy phiên bản báo cáo bảo trì: " + revision));
    }

    private void renderReport(
            PdfWriter w,
            MaintenanceWorkflowDetailResponse detail,
            ReportVersionResponse report) throws IOException {
        w.newPage(true);

        // 1. Enterprise Navy Masthead Banner
        renderMasthead(w, detail, report);

        // 2. Metadata 2-column Dossier Card
        renderMetadataCard(w, detail, report);

        // 3. KPI Summary Strip
        renderKpiStrip(w, detail, report);

        // 4. Section 1: Condition & Diagnosis (2-col callouts)
        w.sectionHeading("1", "Tình trạng tiếp nhận & Chẩn đoán lỗi");
        renderTwoColBoxes(
                w,
                "Tình trạng khi tiếp nhận",
                safe(report.receivedConditionSummary()),
                THEME.accentBlue,
                "Lỗi phát hiện & Chẩn đoán",
                safe(report.faultDiagnosisSummary()),
                ReportDesignTheme.STATUS_WARNING);

        // 5. Section 2: Work Summary & Work Items Table
        w.sectionHeading("2", "Công việc bảo trì đã thực hiện");
        renderCalloutBox(w, "Tóm tắt công việc", safe(report.workSummary()), THEME.accentSoftBg, THEME.accentBlue);
        renderWorkItemsTable(w, detail.workItems());

        // 6. Section 3: Replaced Parts Table
        w.sectionHeading("3", "Linh kiện thay thế");
        if (report.partsSummary() != null && !report.partsSummary().isBlank()) {
            renderCalloutBox(w, "Tóm tắt linh kiện", report.partsSummary(), NEUTRAL_BG, THEME.bodyMuted);
        }
        renderPartsTable(w, detail.partReplacements());

        // 7. Section 4: Post-Maintenance Checks Table
        w.sectionHeading("4", "Danh sách kiểm tra sau bảo trì");
        renderPostChecksTable(w, report.postChecks());

        // 8. Section 5: Remaining Issues, Limitations & Recommendations
        w.sectionHeading("5", "Lỗi tồn tại, giới hạn sử dụng & khuyến nghị");
        renderTwoColBoxes(
                w,
                "Lỗi còn tồn tại",
                safe(report.remainingIssues()),
                ReportDesignTheme.STATUS_WARNING,
                "Giới hạn sử dụng",
                safe(report.usageLimitations()),
                ReportDesignTheme.STATUS_WARNING);
        renderCalloutBox(
                w,
                "Khuyến nghị theo dõi sau bảo trì",
                safe(report.followUpRecommendations()),
                THEME.accentSoftBg,
                THEME.accentBlue);

        // 9. Section 6: Handover & Acceptance Summary
        w.sectionHeading("6", "Lịch sử bàn giao & Quyết định nghiệm thu");
        renderHandoverTable(w, detail.handovers());
        if (detail.acceptanceDecisions() != null && !detail.acceptanceDecisions().isEmpty()) {
            renderAcceptanceDecisions(w, detail.acceptanceDecisions());
        }

        // 10. Sign-off Block
        renderSignatureBlock(w, detail, report);
    }

    private void renderMasthead(
            PdfWriter w,
            MaintenanceWorkflowDetailResponse detail,
            ReportVersionResponse report) throws IOException {
        float bannerH = 96f;
        float bannerBottom = PAGE_H - bannerH;
        w.fillRect(0f, bannerBottom, PAGE_W, bannerH, THEME.primaryNavy);
        w.fillRect(0f, bannerBottom, PAGE_W, 4f, THEME.accentBlue);

        w.drawText("HỆ THỐNG GIÁM SÁT DRONE · HỒ SƠ KỸ THUẬT BẢO TRÌ",
                MARGIN_X, PAGE_H - 26f, 8.5f, w.bold, new Color(147, 197, 253));
        w.drawText("BÁO CÁO BẢO TRÌ & NGHIỆM THU THIẾT BỊ",
                MARGIN_X, PAGE_H - 48f, 17f, w.bold, WHITE);

        String sub = "Mã phiếu: " + detail.ticketCode()
                + "   |   Phiên bản: Bản " + report.revision() + " (Lượt " + report.roundNumber() + ")"
                + "   |   Mức độ: " + viSeverity(detail.severity());
        w.drawText(sub, MARGIN_X, PAGE_H - 70f, 9.5f, w.regular, new Color(203, 213, 225));

        // Status pill on top-right
        String statusText = viReportStatus(report.status());
        Color pillFg = statusColor(report.status());
        Color pillBg = statusBg(report.status());
        float pillW = w.textWidth(statusText, 8.5f, w.bold) + 22f;
        float pillX = PAGE_W - MARGIN_X - pillW;
        float pillY = PAGE_H - 49f;
        w.fillRect(pillX, pillY, pillW, 20f, pillBg);
        w.drawText(statusText, pillX + 11f, pillY + 6.5f, 8.5f, w.bold, pillFg);

        w.y = bannerBottom - 16f;
    }

    private void renderMetadataCard(
            PdfWriter w,
            MaintenanceWorkflowDetailResponse detail,
            ReportVersionResponse report) throws IOException {
        float cardH = 88f;
        w.ensure(cardH + 10f);
        float bottom = w.y - cardH;
        w.fillAndStrokeRect(MARGIN_X, bottom, CONTENT_W, cardH, THEME.surfaceTint, THEME.borderHairline, 0.8f);

        float colW = CONTENT_W / 2f;
        float leftX = MARGIN_X + 12f;
        float rightX = MARGIN_X + colW + 12f;
        w.vLine(MARGIN_X + colW, bottom + 10f, bottom + cardH - 10f, THEME.borderHairline, 0.6f);

        String deviceName = detail.device() != null ? safe(detail.device().name()) : "—";
        String serial = detail.device() != null ? safe(detail.device().serialNumber()) : "—";
        String model = detail.device() != null ? safe(detail.device().modelName()) : "—";
        String deviceStatus = detail.device() != null ? viDeviceStatus(detail.device().status()) : "—";

        String techName = report.authoredBy() != null
                ? report.authoredBy().fullName()
                : (detail.assignedTechnical() != null ? detail.assignedTechnical().fullName() : "—");
        String handedBy = detail.handedOverBy() != null ? detail.handedOverBy().fullName() : "—";
        String managerName = detail.receivingManager() != null ? detail.receivingManager().fullName() : "—";
        String submittedAt = report.submittedAt() != null ? FMT.format(report.submittedAt()) : "Bản nháp";

        float rowY = w.y - 18f;
        drawKeyVal(w, leftX, rowY, 82f, "Thiết bị / Drone:", deviceName, colW - 24f);
        drawKeyVal(w, rightX, rowY, 96f, "Kỹ thuật viên:", techName, colW - 24f);
        rowY -= 19f;
        drawKeyVal(w, leftX, rowY, 82f, "Số Serial:", serial, colW - 24f);
        drawKeyVal(w, rightX, rowY, 96f, "Người bàn giao:", handedBy, colW - 24f);
        rowY -= 19f;
        drawKeyVal(w, leftX, rowY, 82f, "Dòng máy:", model, colW - 24f);
        drawKeyVal(w, rightX, rowY, 96f, "Quản lý nghiệm thu:", managerName, colW - 24f);
        rowY -= 19f;
        drawKeyVal(w, leftX, rowY, 82f, "Trạng thái drone:", deviceStatus, colW - 24f);
        drawKeyVal(w, rightX, rowY, 96f, "Thời gian gửi:", submittedAt, colW - 24f);

        w.y = bottom - 12f;
    }

    private void renderKpiStrip(
            PdfWriter w,
            MaintenanceWorkflowDetailResponse detail,
            ReportVersionResponse report) throws IOException {
        float boxH = 44f;
        w.ensure(boxH + 10f);
        float gap = 8f;
        float boxW = (CONTENT_W - gap * 3f) / 4f;
        float bottom = w.y - boxH;

        int totalWork = detail.workItems() != null ? detail.workItems().size() : 0;
        long doneWork = detail.workItems() != null
                ? detail.workItems().stream().filter(i -> i.status() == MaintenanceWorkStatus.COMPLETED).count()
                : 0;
        int totalParts = detail.partReplacements() != null ? detail.partReplacements().size() : 0;
        int totalChecks = report.postChecks() != null ? report.postChecks().size() : 0;
        long passChecks = report.postChecks() != null
                ? report.postChecks().stream().filter(c -> c.result() == PostCheckResult.PASS).count()
                : 0;
        int totalHandovers = detail.handovers() != null ? detail.handovers().size() : 0;

        drawKpiBox(w, MARGIN_X, bottom, boxW, boxH, "Hạng mục công việc",
                doneWork + "/" + totalWork + " hoàn tất", THEME.accentBlue);
        drawKpiBox(w, MARGIN_X + (boxW + gap), bottom, boxW, boxH, "Linh kiện thay thế",
                totalParts + " linh kiện", THEME.primaryNavy);
        drawKpiBox(w, MARGIN_X + (boxW + gap) * 2f, bottom, boxW, boxH, "Kiểm tra sau bảo trì",
                passChecks + "/" + totalChecks + " đạt", ReportDesignTheme.STATUS_PASS);
        drawKpiBox(w, MARGIN_X + (boxW + gap) * 3f, bottom, boxW, boxH, "Phiếu bàn giao",
                totalHandovers + " biên bản", THEME.bodyMuted);

        w.y = bottom - 12f;
    }

    private void drawKpiBox(
            PdfWriter w, float x, float bottom, float width, float height,
            String label, String value, Color accent) throws IOException {
        w.fillAndStrokeRect(x, bottom, width, height, WHITE, THEME.borderHairline, 0.7f);
        w.fillRect(x, bottom + height - 3f, width, 3f, accent);
        w.drawText(label, x + 9f, bottom + height - 16f, 7.5f, w.regular, THEME.bodyMuted);
        w.drawText(value, x + 9f, bottom + 10f, 11f, w.bold, accent);
    }

    private void renderTwoColBoxes(
            PdfWriter w,
            String leftTitle, String leftBody, Color leftAccent,
            String rightTitle, String rightBody, Color rightAccent) throws IOException {
        float gap = 10f;
        float colW = (CONTENT_W - gap) / 2f;
        List<String> leftLines = w.wrap(leftBody, 9f, colW - 22f, w.regular);
        List<String> rightLines = w.wrap(rightBody, 9f, colW - 22f, w.regular);
        int maxLines = Math.max(1, Math.max(leftLines.size(), rightLines.size()));
        float boxH = 26f + maxLines * 13f + 8f;

        w.ensure(boxH + 8f);
        float bottom = w.y - boxH;
        drawAccentBox(w, MARGIN_X, bottom, colW, boxH, leftTitle, leftLines, leftAccent);
        drawAccentBox(w, MARGIN_X + colW + gap, bottom, colW, boxH, rightTitle, rightLines, rightAccent);
        w.y = bottom - 10f;
    }

    private void drawAccentBox(
            PdfWriter w, float x, float bottom, float width, float height,
            String title, List<String> lines, Color accent) throws IOException {
        w.fillAndStrokeRect(x, bottom, width, height, THEME.surfaceTint, THEME.borderHairline, 0.7f);
        w.fillRect(x, bottom, 3f, height, accent);
        w.drawText(title, x + 11f, bottom + height - 15f, 8.5f, w.bold, THEME.primaryNavy);
        float ty = bottom + height - 30f;
        for (String line : lines) {
            w.drawText(line, x + 11f, ty, 9f, w.regular, ReportDesignTheme.INK_BODY);
            ty -= 13f;
        }
    }

    private void renderCalloutBox(
            PdfWriter w, String title, String body, Color bg, Color accent) throws IOException {
        List<String> lines = w.wrap(body, 9f, CONTENT_W - 24f, w.regular);
        float boxH = 24f + Math.max(1, lines.size()) * 13f + 8f;
        w.ensure(boxH + 8f);
        float bottom = w.y - boxH;
        w.fillAndStrokeRect(MARGIN_X, bottom, CONTENT_W, boxH, bg, THEME.borderHairline, 0.7f);
        w.fillRect(MARGIN_X, bottom, 3.5f, boxH, accent);
        w.drawText(title, MARGIN_X + 12f, bottom + boxH - 15f, 8.5f, w.bold, THEME.primaryNavy);
        float ty = bottom + boxH - 29f;
        for (String line : lines) {
            w.drawText(line, MARGIN_X + 12f, ty, 9f, w.regular, ReportDesignTheme.INK_BODY);
            ty -= 13f;
        }
        w.y = bottom - 10f;
    }

    private void renderWorkItemsTable(PdfWriter w, List<WorkItemResponse> items) throws IOException {
        if (items == null || items.isEmpty()) {
            renderEmptyNote(w, "Chưa ghi nhận hạng mục công việc bảo trì.");
            return;
        }
        float[] cols = {36f, 118f, 78f, 72f, CONTENT_W - 304f};
        String[] headers = {"Lượt", "Bộ phận / Khu vực", "Hành động", "Trạng thái", "Chẩn đoán & Nội dung thực hiện"};
        drawTableHeader(w, cols, headers);

        for (int idx = 0; idx < items.size(); idx++) {
            WorkItemResponse item = items.get(idx);
            StringBuilder desc = new StringBuilder();
            if (item.diagnosis() != null && !item.diagnosis().isBlank()) {
                desc.append("Chẩn đoán: ").append(item.diagnosis());
            }
            if (item.workPerformed() != null && !item.workPerformed().isBlank()) {
                if (!desc.isEmpty()) desc.append("\n");
                desc.append(item.workPerformed());
            }
            String[] row = {
                    "L" + item.roundNumber(),
                    safe(item.componentArea()),
                    viWorkAction(item.actionType()),
                    viWorkStatus(item.status()),
                    desc.isEmpty() ? "—" : desc.toString()
            };
            drawTableRow(w, cols, headers, row, idx % 2 == 1);
        }
        w.y -= 8f;
    }

    private void renderPartsTable(PdfWriter w, List<PartReplacementResponse> parts) throws IOException {
        if (parts == null || parts.isEmpty()) {
            renderEmptyNote(w, "Không có linh kiện thay thế trong đợt bảo trì này.");
            return;
        }
        float[] cols = {115f, 44f, 108f, 166f, CONTENT_W - 433f};
        String[] headers = {"Tên linh kiện / PN", "SL", "Serial cũ → mới", "Lý do thay thế", "Chi phí"};
        drawTableHeader(w, cols, headers);

        NumberFormat nf = NumberFormat.getNumberInstance(Locale.forLanguageTag("vi-VN"));
        for (int idx = 0; idx < parts.size(); idx++) {
            PartReplacementResponse p = parts.get(idx);
            String name = p.partName() + (p.partNumber() != null && !p.partNumber().isBlank()
                    ? "\nPN: " + p.partNumber() : "");
            String qty = trimQty(p.quantity()) + " " + safe(p.unit());
            String serials = (p.oldSerial() != null || p.newSerial() != null)
                    ? safe(p.oldSerial()) + " → " + safe(p.newSerial())
                    : "—";
            String cost = p.costAmount() != null
                    ? nf.format(p.costAmount()) + (p.currency() != null ? " " + p.currency() : " đ")
                    : "—";
            String[] row = {name, qty, serials, safe(p.replacementReason()), cost};
            drawTableRow(w, cols, headers, row, idx % 2 == 1);
        }
        w.y -= 8f;
    }

    private void renderPostChecksTable(PdfWriter w, List<PostCheckResponse> checks) throws IOException {
        if (checks == null || checks.isEmpty()) {
            renderEmptyNote(w, "Chưa có mục kiểm tra sau bảo trì.");
            return;
        }
        float[] cols = {175f, 175f, 76f, CONTENT_W - 426f};
        String[] headers = {"Hạng mục kiểm tra", "Phương pháp kiểm tra", "Kết quả", "Ghi chú"};
        drawTableHeader(w, cols, headers);

        for (int idx = 0; idx < checks.size(); idx++) {
            PostCheckResponse c = checks.get(idx);
            String note = c.notApplicableReason() != null && !c.notApplicableReason().isBlank()
                    ? c.notApplicableReason()
                    : safe(c.notes());
            String[] row = {
                    safe(c.checkName()),
                    safe(c.checkMethod()),
                    viPostCheck(c.result()),
                    note
            };
            drawTableRow(w, cols, headers, row, idx % 2 == 1);
        }
        w.y -= 8f;
    }

    private void renderHandoverTable(PdfWriter w, List<HandoverResponse> handovers) throws IOException {
        if (handovers == null || handovers.isEmpty()) {
            renderEmptyNote(w, "Chưa có biên bản bàn giao.");
            return;
        }
        float[] cols = {84f, 68f, 132f, 84f, CONTENT_W - 368f};
        String[] headers = {"Loại phiếu", "Lượt / Bản", "Từ → Đến", "Trạng thái", "Tình trạng / Ghi chú"};
        drawTableHeader(w, cols, headers);

        for (int idx = 0; idx < handovers.size(); idx++) {
            HandoverResponse h = handovers.get(idx);
            String dir = h.direction() == HandoverDirection.INBOUND ? "Bàn giao vào" : "Bàn giao trả lại";
            String rev = "L" + h.roundNumber() + " · Bản " + h.revision();
            String route = (h.fromUser() != null ? h.fromUser().fullName() : "—")
                    + " → " + (h.toUser() != null ? h.toUser().fullName() : "—");
            String note = h.overallCondition() != null && !h.overallCondition().isBlank()
                    ? h.overallCondition()
                    : safe(h.maintenanceReason());
            String[] row = {dir, rev, route, viHandoverStatus(h.status()), note};
            drawTableRow(w, cols, headers, row, idx % 2 == 1);
        }
        w.y -= 8f;
    }

    private void renderAcceptanceDecisions(PdfWriter w, List<AcceptanceDecisionResponse> decisions) throws IOException {
        for (AcceptanceDecisionResponse d : decisions) {
            boolean accepted = d.decision() == AcceptanceDecisionType.ACCEPTED;
            String title = (accepted ? "Đã nghiệm thu & nhận lại" : "Yêu cầu xử lý thêm")
                    + " — Lượt " + d.roundNumber() + " · Bản " + d.reportRevision()
                    + " (" + (d.decidedBy() != null ? d.decidedBy().fullName() : "—")
                    + (d.decidedAt() != null ? ", " + FMT.format(d.decidedAt()) : "") + ")";
            StringBuilder body = new StringBuilder();
            body.append("Đối chiếu drone & serial: ").append(d.deviceMatchConfirmed() ? "Đã xác nhận" : "Chưa")
                    .append("   |   Kiểm tra phụ kiện: ").append(d.accessoriesMatchConfirmed() ? "Đủ" : "Chưa");
            if (d.verificationNotes() != null && !d.verificationNotes().isBlank()) {
                body.append("\nGhi chú: ").append(d.verificationNotes());
            }
            if (d.reworkReason() != null && !d.reworkReason().isBlank()) {
                body.append("\nLý do xử lý thêm: ").append(d.reworkReason());
            }
            renderCalloutBox(
                    w,
                    title,
                    body.toString(),
                    accepted ? PASS_BG : FAIL_BG,
                    accepted ? ReportDesignTheme.STATUS_PASS : ReportDesignTheme.STATUS_FAIL);
        }
    }

    private void renderSignatureBlock(
            PdfWriter w,
            MaintenanceWorkflowDetailResponse detail,
            ReportVersionResponse report) throws IOException {
        float blockH = 74f;
        w.ensure(blockH + 14f);
        w.y -= 6f;
        float bottom = w.y - blockH;
        float gap = 10f;
        float colW = (CONTENT_W - gap * 2f) / 3f;

        String handedName = detail.handedOverBy() != null ? detail.handedOverBy().fullName() : "—";
        String techName = report.authoredBy() != null
                ? report.authoredBy().fullName()
                : (detail.assignedTechnical() != null ? detail.assignedTechnical().fullName() : "—");
        String mgrName = detail.receivingManager() != null ? detail.receivingManager().fullName() : "—";

        drawSignBox(w, MARGIN_X, bottom, colW, blockH, "NGƯỜI BÀN GIAO", handedName,
                detail.openedAt() != null ? FMT.format(detail.openedAt()) : "");
        drawSignBox(w, MARGIN_X + colW + gap, bottom, colW, blockH, "KỸ THUẬT VIÊN BẢO TRÌ", techName,
                report.submittedAt() != null ? FMT.format(report.submittedAt()) : "");
        drawSignBox(w, MARGIN_X + (colW + gap) * 2f, bottom, colW, blockH, "QUẢN LÝ NGHIỆM THU", mgrName,
                detail.closedAt() != null ? FMT.format(detail.closedAt()) : "");

        w.y = bottom - 8f;
    }

    private void drawSignBox(
            PdfWriter w, float x, float bottom, float width, float height,
            String roleTitle, String personName, String timestamp) throws IOException {
        w.fillAndStrokeRect(x, bottom, width, height, WHITE, THEME.borderHairline, 0.7f);
        w.drawText(roleTitle, x + 10f, bottom + height - 15f, 7.5f, w.bold, THEME.bodyMuted);
        w.hLine(x + 10f, x + width - 10f, bottom + 28f, THEME.borderHairline, 0.6f);
        w.drawText(personName, x + 10f, bottom + 16f, 9f, w.bold, THEME.primaryNavy);
        if (timestamp != null && !timestamp.isBlank()) {
            w.drawText(timestamp, x + 10f, bottom + 5f, 7.5f, w.regular, THEME.bodyMuted);
        }
    }

    private void drawTableHeader(PdfWriter w, float[] cols, String[] headers) throws IOException {
        float h = 22f;
        w.ensure(h + 26f);
        float bottom = w.y - h;
        w.fillAndStrokeRect(MARGIN_X, bottom, CONTENT_W, h, THEME.tableHeaderBg, THEME.borderHairline, 0.7f);
        float x = MARGIN_X;
        for (int i = 0; i < cols.length; i++) {
            w.drawText(headers[i], x + 6f, bottom + 7.5f, 8f, w.bold, THEME.primaryNavy);
            x += cols[i];
        }
        w.y = bottom;
    }

    private void drawTableRow(
            PdfWriter w, float[] cols, String[] headers, String[] cells, boolean zebra) throws IOException {
        List<List<String>> wrapped = new ArrayList<>();
        int maxLines = 1;
        for (int i = 0; i < cols.length; i++) {
            List<String> lines = w.wrap(cells[i], 8.5f, cols[i] - 12f, w.regular);
            if (lines.isEmpty()) lines = List.of("—");
            wrapped.add(lines);
            maxLines = Math.max(maxLines, lines.size());
        }
        float rowH = maxLines * 12f + 10f;
        if (w.y - rowH < BOTTOM_Y) {
            w.newPage(false);
            drawTableHeader(w, cols, headers);
        }
        float bottom = w.y - rowH;
        w.fillAndStrokeRect(MARGIN_X, bottom, CONTENT_W, rowH, zebra ? ZEBRA_BG : WHITE, THEME.borderHairline, 0.5f);
        float x = MARGIN_X;
        for (int i = 0; i < cols.length; i++) {
            float ty = w.y - 13f;
            for (String line : wrapped.get(i)) {
                w.drawText(line, x + 6f, ty, 8.5f, w.regular, ReportDesignTheme.INK_BODY);
                ty -= 12f;
            }
            x += cols[i];
        }
        w.y = bottom;
    }

    private void renderEmptyNote(PdfWriter w, String message) throws IOException {
        float h = 26f;
        w.ensure(h + 8f);
        float bottom = w.y - h;
        w.fillAndStrokeRect(MARGIN_X, bottom, CONTENT_W, h, THEME.surfaceTint, THEME.borderHairline, 0.6f);
        w.drawText(message, MARGIN_X + 12f, bottom + 9f, 8.5f, w.italic, THEME.bodyMuted);
        w.y = bottom - 8f;
    }

    private void drawKeyVal(
            PdfWriter w, float x, float baselineY, float labelW,
            String label, String val, float maxTotalW) throws IOException {
        w.drawText(label, x, baselineY, 8.5f, w.regular, THEME.bodyMuted);
        String clipped = w.clip(val, 8.5f, maxTotalW - labelW, w.bold);
        w.drawText(clipped, x + labelW, baselineY, 8.5f, w.bold, THEME.primaryNavy);
    }

    private void renderRunningHeadersAndFooters(
            PDDocument document,
            PDFont regular,
            PDFont bold,
            MaintenanceWorkflowDetailResponse detail,
            ReportVersionResponse report) throws IOException {
        int totalPages = document.getNumberOfPages();
        String exportedAt = FMT.format(Instant.now());
        String headerLeft = "BÁO CÁO BẢO TRÌ DRONE · " + detail.ticketCode();
        String headerRight = "Phiên bản " + report.revision() + " · Lượt " + report.roundNumber();
        String footerLeft = "OnDemand Monitor · Xuất báo cáo lúc " + exportedAt;

        for (int i = 0; i < totalPages; i++) {
            PDPage page = document.getPage(i);
            try (PDPageContentStream cs = new PDPageContentStream(
                    document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                // Running header on pages 2+
                if (i > 0) {
                    cs.setStrokingColor(THEME.borderHairline);
                    cs.setLineWidth(0.6f);
                    cs.moveTo(MARGIN_X, PAGE_H - 38f);
                    cs.lineTo(PAGE_W - MARGIN_X, PAGE_H - 38f);
                    cs.stroke();

                    drawStaticText(cs, headerLeft, MARGIN_X, PAGE_H - 30f, 8f, bold, THEME.primaryNavy);
                    float rw = textWidthStatic(headerRight, 8f, regular);
                    drawStaticText(cs, headerRight, PAGE_W - MARGIN_X - rw, PAGE_H - 30f, 8f, regular, THEME.bodyMuted);
                }

                // Running footer on every page
                cs.setStrokingColor(THEME.borderHairline);
                cs.setLineWidth(0.6f);
                cs.moveTo(MARGIN_X, 34f);
                cs.lineTo(PAGE_W - MARGIN_X, 34f);
                cs.stroke();

                drawStaticText(cs, footerLeft, MARGIN_X, 20f, 7.5f, regular, THEME.bodyMuted);
                String pageNum = "Trang " + (i + 1) + " / " + totalPages;
                float pw = textWidthStatic(pageNum, 7.5f, bold);
                drawStaticText(cs, pageNum, PAGE_W - MARGIN_X - pw, 20f, 7.5f, bold, THEME.primaryNavy);
            }
        }
    }

    private PDFont loadFont(PDDocument doc, String classpath, Standard14Fonts.FontName fallback) throws IOException {
        ClassPathResource resource = new ClassPathResource(classpath);
        if (resource.exists()) {
            try (InputStream in = resource.getInputStream()) {
                return PDType0Font.load(doc, in);
            }
        }
        return new PDType1Font(fallback);
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static String trimQty(BigDecimal qty) {
        if (qty == null) return "1";
        return qty.stripTrailingZeros().toPlainString();
    }

    private static String viSeverity(String s) {
        if (s == null) return "—";
        return switch (s) {
            case "CRITICAL" -> "Khẩn cấp";
            case "HIGH" -> "Cao";
            case "MEDIUM" -> "Trung bình";
            case "LOW" -> "Thấp";
            default -> s;
        };
    }

    private static String viDeviceStatus(String s) {
        if (s == null) return "—";
        return switch (s) {
            case "MAINTENANCE" -> "Đang bảo trì";
            case "AVAILABLE" -> "Sẵn sàng";
            case "IN_USE" -> "Đang hoạt động";
            case "UNAVAILABLE" -> "Không khả dụng";
            case "RETIRED" -> "Ngừng sử dụng";
            default -> s;
        };
    }

    private static String viReportStatus(MaintenanceReportStatus st) {
        if (st == null) return "—";
        return switch (st) {
            case DRAFT -> "BẢN NHÁP";
            case SUBMITTED -> "CHỜ NGHIỆM THU";
            case ACCEPTED -> "ĐÃ NGHIỆM THU";
            case REWORK_REQUIRED -> "YÊU CẦU LÀM LẠI";
        };
    }

    private static Color statusColor(MaintenanceReportStatus st) {
        if (st == null) return THEME.bodyMuted;
        return switch (st) {
            case ACCEPTED -> ReportDesignTheme.STATUS_PASS;
            case REWORK_REQUIRED -> ReportDesignTheme.STATUS_FAIL;
            case SUBMITTED -> ReportDesignTheme.STATUS_WARNING;
            case DRAFT -> THEME.accentBlue;
        };
    }

    private static Color statusBg(MaintenanceReportStatus st) {
        if (st == null) return NEUTRAL_BG;
        return switch (st) {
            case ACCEPTED -> PASS_BG;
            case REWORK_REQUIRED -> FAIL_BG;
            case SUBMITTED -> WARN_BG;
            case DRAFT -> THEME.accentSoftBg;
        };
    }

    private static String viWorkAction(MaintenanceWorkActionType a) {
        if (a == null) return "—";
        return switch (a) {
            case INSPECT -> "Kiểm tra";
            case CLEAN -> "Vệ sinh";
            case CALIBRATE -> "Hiệu chỉnh";
            case REPAIR -> "Sửa chữa";
            case REPLACE -> "Thay thế";
            case FIRMWARE_UPDATE -> "Cập nhật FW";
        };
    }

    private static String viWorkStatus(MaintenanceWorkStatus s) {
        if (s == null) return "—";
        return switch (s) {
            case PENDING -> "Chờ xử lý";
            case IN_PROGRESS -> "Đang làm";
            case COMPLETED -> "Hoàn tất";
            case BLOCKED -> "Bị chặn";
        };
    }

    private static String viPostCheck(PostCheckResult r) {
        if (r == null) return "—";
        return switch (r) {
            case PASS -> "ĐẠT";
            case FAIL -> "KHÔNG ĐẠT";
            case NOT_APPLICABLE -> "KHÔNG ÁP DỤNG";
        };
    }

    private static String viHandoverStatus(HandoverStatus s) {
        if (s == null) return "—";
        return switch (s) {
            case DRAFT -> "Bản nháp";
            case SUBMITTED -> "Đã gửi";
            case RECEIVED -> "Đã tiếp nhận";
            case RECEIVED_WITH_DISCREPANCY -> "Có chênh lệch";
            case REJECTED -> "Từ chối nhận";
            case ACCEPTED -> "Đã nghiệm thu";
            case REWORK_REQUESTED -> "Yêu cầu làm lại";
        };
    }

    private static void drawStaticText(
            PDPageContentStream cs, String text, float x, float y,
            float size, PDFont font, Color color) throws IOException {
        String safe = sanitizeStatic(text, font);
        if (safe.isEmpty()) return;
        cs.setNonStrokingColor(color);
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(safe);
        cs.endText();
    }

    private static float textWidthStatic(String text, float size, PDFont font) throws IOException {
        String safe = sanitizeStatic(text, font);
        return safe.isEmpty() ? 0f : font.getStringWidth(safe) / 1000f * size;
    }

    private static String sanitizeStatic(String raw, PDFont font) {
        if (raw == null || raw.isEmpty()) return "";
        String cleaned = raw.replace('\t', ' ').replaceAll("[\\p{Cntrl}&&[^\\n]]", " ");
        StringBuilder out = new StringBuilder(cleaned.length());
        for (int cp : cleaned.codePoints().toArray()) {
            if (cp == '\n') {
                out.append(' ');
                continue;
            }
            String ch = new String(Character.toChars(cp));
            try {
                font.getStringWidth(ch);
                out.append(ch);
            } catch (Exception ignored) {
                out.append(' ');
            }
        }
        return out.toString();
    }

    /** Lightweight stateful A4 PDF drawing helper. */
    private static final class PdfWriter {
        final PDDocument document;
        final PDFont regular;
        final PDFont bold;
        final PDFont italic;
        PDPageContentStream stream;
        float y;

        PdfWriter(PDDocument document, PDFont regular, PDFont bold, PDFont italic) {
            this.document = document;
            this.regular = regular;
            this.bold = bold;
            this.italic = italic;
        }

        void newPage(boolean firstPage) throws IOException {
            closeCurrentStream();
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            y = firstPage ? PAGE_H : TOP_Y;
        }

        void ensure(float neededHeight) throws IOException {
            if (stream == null) {
                newPage(false);
                return;
            }
            if (y - neededHeight < BOTTOM_Y) {
                newPage(false);
            }
        }

        void closeCurrentStream() throws IOException {
            if (stream != null) {
                stream.close();
                stream = null;
            }
        }

        void sectionHeading(String number, String title) throws IOException {
            ensure(36f);
            y -= 4f;
            float badgeSize = 16f;
            float badgeBottom = y - badgeSize;
            fillRect(MARGIN_X, badgeBottom, badgeSize, badgeSize, THEME.accentBlue);
            float nw = textWidth(number, 9f, bold);
            drawText(number, MARGIN_X + (badgeSize - nw) / 2f, badgeBottom + 4.5f, 9f, bold, WHITE);
            drawText(title, MARGIN_X + badgeSize + 8f, badgeBottom + 4f, 11f, bold, THEME.primaryNavy);
            hLine(MARGIN_X, PAGE_W - MARGIN_X, badgeBottom - 4f, THEME.borderHairline, 0.8f);
            y = badgeBottom - 12f;
        }

        void fillRect(float x, float bottomY, float w, float h, Color color) throws IOException {
            stream.setNonStrokingColor(color);
            stream.addRect(x, bottomY, w, h);
            stream.fill();
        }

        void strokeRect(float x, float bottomY, float w, float h, Color color, float lw) throws IOException {
            stream.setStrokingColor(color);
            stream.setLineWidth(lw);
            stream.addRect(x, bottomY, w, h);
            stream.stroke();
        }

        void fillAndStrokeRect(float x, float bottomY, float w, float h, Color fill, Color stroke, float lw)
                throws IOException {
            fillRect(x, bottomY, w, h, fill);
            strokeRect(x, bottomY, w, h, stroke, lw);
        }

        void hLine(float x1, float x2, float lineY, Color color, float lw) throws IOException {
            stream.setStrokingColor(color);
            stream.setLineWidth(lw);
            stream.moveTo(x1, lineY);
            stream.lineTo(x2, lineY);
            stream.stroke();
        }

        void vLine(float x, float y1, float y2, Color color, float lw) throws IOException {
            stream.setStrokingColor(color);
            stream.setLineWidth(lw);
            stream.moveTo(x, y1);
            stream.lineTo(x, y2);
            stream.stroke();
        }

        void drawText(String text, float x, float baselineY, float size, PDFont font, Color color) throws IOException {
            drawStaticText(stream, text, x, baselineY, size, font, color);
        }

        float textWidth(String text, float size, PDFont font) throws IOException {
            return textWidthStatic(text, size, font);
        }

        String clip(String raw, float size, float maxWidth, PDFont font) throws IOException {
            String safe = sanitizeStatic(raw, font);
            if (textWidth(safe, size, font) <= maxWidth) return safe;
            String ellipsis = "…";
            float ew = textWidth(ellipsis, size, font);
            int end = safe.length();
            while (end > 0 && textWidth(safe.substring(0, end), size, font) + ew > maxWidth) {
                end--;
            }
            return safe.substring(0, end) + ellipsis;
        }

        List<String> wrap(String raw, float size, float maxWidth, PDFont font) throws IOException {
            List<String> result = new ArrayList<>();
            if (raw == null || raw.isBlank()) {
                return List.of("—");
            }
            for (String para : raw.split("\\r?\\n", -1)) {
                if (para.isBlank()) {
                    result.add("");
                    continue;
                }
                String[] words = para.trim().split("\\s+");
                StringBuilder cur = new StringBuilder();
                for (String word : words) {
                    String candidate = cur.isEmpty() ? word : cur + " " + word;
                    if (textWidth(candidate, size, font) <= maxWidth) {
                        cur.setLength(0);
                        cur.append(candidate);
                    } else {
                        if (!cur.isEmpty()) {
                            result.add(cur.toString());
                            cur.setLength(0);
                        }
                        if (textWidth(word, size, font) <= maxWidth) {
                            cur.append(word);
                        } else {
                            // Break very long token cleanly
                            String rem = word;
                            while (!rem.isEmpty()) {
                                int cut = rem.length();
                                while (cut > 1 && textWidth(rem.substring(0, cut), size, font) > maxWidth) {
                                    cut--;
                                }
                                result.add(rem.substring(0, cut));
                                rem = rem.substring(cut);
                            }
                        }
                    }
                }
                if (!cur.isEmpty()) {
                    result.add(cur.toString());
                }
            }
            return result.isEmpty() ? List.of("—") : result;
        }
    }
}
