package com.ondemandmonitoring.inspectionreport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReportPdf {
    private final ReportEvidenceService evidence;

    /**
     * Backward-compatible render entry point (uses the production theme).
     */
    public byte[] render(
            String missionId,
            String missionCode,
            String service,
            long revision,
            String approvedBy,
            String approvedAt,
            ReportDocument content) throws IOException {
        return render(ReportRenderContext.basic(
                missionId, missionCode, service, revision, approvedBy, approvedAt, content));
    }

    /**
     * Full design-engine render entry point with frozen evidence snapshot, mission metadata,
     * and explicit design theme.
     */
    public byte[] render(ReportRenderContext context) throws IOException {
        try (var pdf = new PDDocument();
             var regularIn = new ClassPathResource("fonts/NotoSans-Regular.ttf").getInputStream();
             var boldIn = new ClassPathResource("fonts/NotoSans-Bold.ttf").getInputStream();
             var italicIn = new ClassPathResource("fonts/NotoSans-Italic.ttf").getInputStream()) {

            var regular = PDType0Font.load(pdf, regularIn);
            var bold = PDType0Font.load(pdf, boldIn);
            var italic = PDType0Font.load(pdf, italicIn);

            PDDocumentInformation info = pdf.getDocumentInformation();
            info.setTitle(context.document().reportTitle());
            info.setAuthor("OnDemand Monitor");
            info.setSubject("Aerial Drone Inspection Report · " + context.missionCode());
            info.setCreator("OnDemand Monitor " + context.rendererVersion() + " (" + context.theme().code + ")");

            var canvas = new PdfCanvas(pdf, regular, bold, italic, context.theme());
            var components = new ReportPageComponents(canvas, context, evidence);
            components.renderAll();

            var output = new ByteArrayOutputStream();
            pdf.save(output);
            return output.toByteArray();
        }
    }
}
