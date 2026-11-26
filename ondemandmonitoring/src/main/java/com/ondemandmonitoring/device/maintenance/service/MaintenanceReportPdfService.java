package com.ondemandmonitoring.device.maintenance.service;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.device.maintenance.dto.MaintenanceWorkflowDtos.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
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
 * Generates deterministic, revision-accurate PDF maintenance reports using Apache PDFBox
 * and bundled NotoSans fonts directly from verified workflow records (no AI required).
 */
@Service
@RequiredArgsConstructor
public class MaintenanceReportPdfService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    private final MaintenanceWorkflowService workflowService;

    public byte[] exportReportPdf(String ticketId, Integer requestedRevision) {
        MaintenanceWorkflowDetailResponse detail = workflowService.getWorkflowDetail(ticketId);
        ReportVersionResponse report = resolveReport(detail, requestedRevision);

        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDFont regular = loadFont(document, "fonts/NotoSans-Regular.ttf", Standard14Fonts.FontName.HELVETICA);
            PDFont bold = loadFont(document, "fonts/NotoSans-Bold.ttf", Standard14Fonts.FontName.HELVETICA_BOLD);

            List<String> lines = buildLines(detail, report);
            writePaginatedLines(document, regular, bold, lines);
            document.save(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to generate maintenance report PDF.");
        }
    }

    private ReportVersionResponse resolveReport(MaintenanceWorkflowDetailResponse detail, Integer revision) {
        if (detail.reportVersions() == null || detail.reportVersions().isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "No maintenance report version exists for this ticket.");
        }
        if (revision == null) {
            return detail.reportVersions().getFirst();
        }
        return detail.reportVersions().stream()
                .filter(r -> revision.equals(r.revision()))
                .findFirst()
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "Maintenance report revision not found: " + revision));
    }

    private List<String> buildLines(MaintenanceWorkflowDetailResponse detail, ReportVersionResponse report) {
        List<String> lines = new ArrayList<>();
        lines.add("## BAO CAO BAO TRI DRONE / DRONE MAINTENANCE REPORT");
        lines.add("Ma phieu (Ticket): " + detail.ticketCode() + " | Phien ban bao cao: Rev " + report.revision()
                + " (Round " + report.roundNumber() + ") | Trang thai: " + report.status());
        if (detail.device() != null) {
            lines.add("Thiet bi (Drone): " + safe(detail.device().name())
                    + " | Serial: " + safe(detail.device().serialNumber())
                    + " | Model: " + safe(detail.device().modelName()));
        }
        lines.add("Technical: " + (report.authoredBy() != null ? report.authoredBy().fullName() : "-")
                + " | Manager nhan lai: "
                + (detail.receivingManager() != null ? detail.receivingManager().fullName() : "-"));
        lines.add("Thoi gian gui bao cao: "
                + (report.submittedAt() != null ? FMT.format(report.submittedAt()) : "Ban nhap"));
        lines.add("");
        lines.add("## 1. Tinh trang tiep nhan");
        lines.add(safe(report.receivedConditionSummary()));
        lines.add("## 2. Loi phat hien & chan doan");
        lines.add(safe(report.faultDiagnosisSummary()));
        lines.add("## 3. Cong viec da thuc hien");
        lines.add(safe(report.workSummary()));
        for (WorkItemResponse item : detail.workItems()) {
            lines.add("- [R" + item.roundNumber() + "] " + item.componentArea()
                    + " | " + item.actionType() + " | " + item.status()
                    + " : " + safe(item.workPerformed()));
        }
        lines.add("## 4. Linh kien thay the");
        if (detail.partReplacements().isEmpty()) {
            lines.add("Khong co linh kien thay the.");
        } else {
            for (PartReplacementResponse part : detail.partReplacements()) {
                lines.add("- " + part.partName()
                        + (part.partNumber() != null ? " (PN: " + part.partNumber() + ")" : "")
                        + " x" + part.quantity() + " " + part.unit()
                        + " | Ly do: " + part.replacementReason());
            }
        }
        lines.add("## 5. Kiem tra sau bao tri");
        for (PostCheckResponse check : report.postChecks()) {
            String suffix = check.result().name();
            if (check.notApplicableReason() != null && !check.notApplicableReason().isBlank()) {
                suffix += " (" + check.notApplicableReason() + ")";
            }
            lines.add("- " + check.checkName() + " [" + check.checkMethod() + "] => " + suffix);
        }
        lines.add("## 6. Loi ton tai, gioi han su dung & khuyen nghi");
        lines.add("Loi ton tai: " + safe(report.remainingIssues()));
        lines.add("Gioi han su dung: " + safe(report.usageLimitations()));
        lines.add("Khuyen nghi theo doi: " + safe(report.followUpRecommendations()));
        return lines;
    }

    private void writePaginatedLines(PDDocument document, PDFont regular, PDFont bold, List<String> lines)
            throws IOException {
        float margin = 48f;
        float y = PDRectangle.A4.getHeight() - margin;
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        PDPageContentStream stream = new PDPageContentStream(document, page);
        try {
            for (String rawLine : lines) {
                boolean isHeader = rawLine.startsWith("## ");
                String text = isHeader ? rawLine.substring(3) : rawLine;
                PDFont font = isHeader ? bold : regular;
                float size = isHeader ? 12f : 10f;
                for (String wrapped : wrap(text, 88)) {
                    if (y < margin + 20f) {
                        stream.close();
                        page = new PDPage(PDRectangle.A4);
                        document.addPage(page);
                        stream = new PDPageContentStream(document, page);
                        y = PDRectangle.A4.getHeight() - margin;
                    }
                    stream.beginText();
                    stream.setFont(font, size);
                    stream.newLineAtOffset(margin, y);
                    stream.showText(sanitize(wrapped));
                    stream.endText();
                    y -= (isHeader ? 18f : 14f);
                }
            }
        } finally {
            stream.close();
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

    private List<String> wrap(String text, int maxChars) {
        if (text == null || text.isEmpty()) {
            return List.of("");
        }
        List<String> result = new ArrayList<>();
        String remaining = text;
        while (remaining.length() > maxChars) {
            int split = remaining.lastIndexOf(' ', maxChars);
            if (split <= 0) {
                split = maxChars;
            }
            result.add(remaining.substring(0, split));
            remaining = remaining.substring(split).trim();
        }
        result.add(remaining);
        return result;
    }

    private String sanitize(String input) {
        return input.replace('\t', ' ').replaceAll("[\\p{Cntrl}&&[^\r\n]]", "");
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
