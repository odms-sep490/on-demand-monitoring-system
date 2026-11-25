package com.ondemandmonitoring.inspectionreport;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.ondemandmonitoring.media.domain.MediaAsset;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.*;

/**
 * Generates the 3 mandatory PDF design concepts, renders every page to PNG for Visual QA,
 * and verifies pagination, Vietnamese typography, data integrity, and stress scenarios.
 */
class ReportPdfDesignTest {
    private static final Path OUT_DIR = Path.of("../.ai-runtime/qa-artifacts/inspection-report/pdf-design-v2");
    private ReportEvidenceService evidence;
    private ReportPdf reportPdf;

    @BeforeEach
    void setup() throws IOException {
        Files.createDirectories(OUT_DIR);
        evidence = mock(ReportEvidenceService.class);
        reportPdf = new ReportPdf(evidence);

        when(evidence.requireMedia(anyString(), anyString())).thenAnswer(inv -> {
            String mediaId = inv.getArgument(1, String.class);
            var asset = new MediaAsset();
            asset.setId(mediaId);
            asset.setCapturedAt(Instant.parse("2026-11-24T02:15:00Z"));
            return asset;
        });
        when(evidence.readImage(any(MediaAsset.class))).thenAnswer(inv -> {
            MediaAsset asset = inv.getArgument(0, MediaAsset.class);
            return createLabeledQaFixturePlate(asset.getId());
        });
    }

    @Test
    void generatesAllThreeConceptsAndProductionSampleWithVisualQaPngs() throws Exception {
        var doc = sampleFiveImageDocument();
        var collection = sampleFiveImageCollection();
        var qaPairs = List.of(new ReportRenderContext.VerifiedBeforeAfterPair(
                "Khu vực mái tôn khu B — Fixture đối chiếu QA",
                "qa-img-02", "Đợt bay tham chiếu", "01/11/2026 08:10",
                "qa-img-03", "Đợt bay hiện tại", "24/11/2026 09:15",
                "Ghi chú fixture QA: minh họa bố cục đối chiếu trước/sau khi hệ thống có hợp đồng ghép cặp xác minh. Không phải bằng chứng hiện trường thật."));

        Map<ReportDesignTheme, String> prefixes = new LinkedHashMap<>();
        prefixes.put(ReportDesignTheme.MODERN_ENTERPRISE, "concept-1-modern-enterprise");
        prefixes.put(ReportDesignTheme.CINEMATIC_AERIAL_EDITORIAL, "concept-2-cinematic-aerial-editorial");
        prefixes.put(ReportDesignTheme.TECHNICAL_INSPECTION, "concept-3-technical-inspection");

        for (var entry : prefixes.entrySet()) {
            var theme = entry.getKey();
            var prefix = entry.getValue();
            var ctx = new ReportRenderContext(
                    "mission-qa-01",
                    "MS-2611-0149",
                    "Kiểm tra nhà xưởng công nghiệp",
                    6,
                    "Nguyễn Văn Quản Lý",
                    "2026-11-24T09:30:00Z",
                    "ORD-2611-0149",
                    "Mái tôn, khe thoát nước & hệ thống điện áp mái khu A-B",
                    "KCN Hiệp Phước, Nhà Bè, TP. Hồ Chí Minh",
                    doc,
                    collection,
                    qaPairs,
                    theme,
                    ReportRenderContext.CURRENT_RENDERER_VERSION);

            byte[] bytes = reportPdf.render(ctx);
            assertTrue(bytes.length > 5_000 && bytes.length < 50 * 1024 * 1024);
            Files.write(OUT_DIR.resolve(prefix + ".pdf"), bytes);

            try (PDDocument pdf = Loader.loadPDF(bytes)) {
                assertTrue(pdf.getNumberOfPages() >= 3, theme.code + " should span cover + content pages");
                String text = new PDFTextStripper().getText(pdf);
                assertTrue(text.contains("Báo cáo giám sát mái nhà xưởng"));
                assertTrue(text.contains("NOT_ANALYZED"));
                assertTrue(text.contains("So sánh Trước / Sau đã xác minh"));
                assertTrue(text.contains("Phụ lục: Danh mục bằng chứng ảnh"));
                assertTrue(text.contains("MS-2611-0149"));
                renderAllPagesToPng(pdf, prefix);
            }
        }

        // Production sample (no before/after pairs -> BEFORE_AFTER_COMPARISON must be hidden!)
        var prodCtx = new ReportRenderContext(
                "mission-qa-01",
                "MS-2611-0149",
                "Kiểm tra nhà xưởng công nghiệp",
                6,
                "Nguyễn Văn Quản Lý",
                "2026-11-24T09:30:00Z",
                "ORD-2611-0149",
                "Mái tôn, khe thoát nước & hệ thống điện áp mái khu A-B",
                "KCN Hiệp Phước, Nhà Bè, TP. Hồ Chí Minh",
                doc,
                collection,
                List.of(),
                ReportDesignTheme.CINEMATIC_AERIAL_EDITORIAL,
                ReportRenderContext.CURRENT_RENDERER_VERSION);
        byte[] prodBytes = reportPdf.render(prodCtx);
        Files.write(OUT_DIR.resolve("production-sample.pdf"), prodBytes);
        try (PDDocument pdf = Loader.loadPDF(prodBytes)) {
            String text = new PDFTextStripper().getText(pdf);
            assertFalse(text.contains("So sánh Trước / Sau đã xác minh"),
                    "Production PDF must hide Before/After component when no verified pairs exist");
            assertTrue(text.contains("3 / 5"), "Only PASS images (3/5) may be counted as PASS; NOT_ANALYZED is never PASS");
            renderAllPagesToPng(pdf, "production-sample");
        }
    }

    @Test
    void handlesOneImageAndZeroFindingsWithoutEmptyShells() throws Exception {
        var doc = new ReportDocument(
                "Báo cáo giám sát khu vực — 1 ảnh",
                "Ghi nhận tổng quan khu vực bằng 1 ảnh đã duyệt.",
                "Toàn bộ phạm vi khu vực mục tiêu.",
                "Chụp ảnh tổng quan từ độ cao an toàn.",
                List.of(new ReportDocument.Section(
                        "s-1", "Hiện trạng khu vực",
                        "Chưa có ghi nhận bất thường từ bộ phận nghiệm thu. Ảnh nguồn đã được lưu trữ đầy đủ.",
                        List.of(new ReportDocument.Image("qa-single-01", "OVERVIEW", "Ảnh tổng quan khu vực giám sát (QA fixture).")),
                        List.of(), List.of("qa-single-01"), null)),
                List.of("Chưa có dữ liệu phân tích chất lượng tự động."),
                "Hồ sơ ảnh nguồn đã bàn giao đầy đủ để khách hàng đối chiếu.",
                List.of("Lưu trữ hồ sơ để so sánh ở đợt bay tiếp theo."));
        var ctx = ReportRenderContext.basic("m-1", "MS-SINGLE", "Giám sát khu vực", 1, null, null, doc);
        byte[] bytes = reportPdf.render(ctx);
        try (PDDocument pdf = Loader.loadPDF(bytes)) {
            assertTrue(pdf.getNumberOfPages() >= 2);
            String text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("qa-single-01"));
            assertFalse(text.contains("Phân bố chất lượng ảnh nguồn"), "Chart must be omitted when quality signals are absent");
            assertFalse(text.contains("Tham chiếu đánh giá đã xác minh"), "Finding strip must be omitted when there are no findings");
        }
    }

    @Test
    void handlesTwentyImagesLongVietnameseTextAndLongRepeatingHeaderTable() throws Exception {
        List<ReportDocument.Image> batch1 = new ArrayList<>();
        List<ReportDocument.Image> batch2 = new ArrayList<>();
        List<ReportEvidenceService.Evidence> evs = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            String id = String.format("qa-20-img-%02d", i);
            String role = (i == 1) ? "OVERVIEW" : (i <= 12 ? "EVIDENCE" : "SUPPORTING");
            String quality = (i % 5 == 0) ? "NOT_ANALYZED" : (i % 7 == 0 ? "WARNING" : "PASS");
            var img = new ReportDocument.Image(id, role, "Ảnh hiện trường số " + i + " — kiểm tra độ phủ và chi tiết bề mặt.");
            if (i <= 10) batch1.add(img); else batch2.add(img);
            evs.add(new ReportEvidenceService.Evidence(id, id + ".jpg", Instant.parse("2026-11-24T02:00:00Z"),
                    1200, 800, quality, 50, true, "QA", List.of("f-1"), false, "sha-" + i));
        }

        // Build a 35-row Tiptap table to verify multi-page repeating headers
        List<Map<String, Object>> tableRows = new ArrayList<>();
        tableRows.add(tableRow(true, List.of("STT", "Khu vực kiểm tra", "Mã bằng chứng", "Ghi nhận hiện trạng")));
        for (int r = 1; r <= 35; r++) {
            tableRows.add(tableRow(false, List.of(
                    String.valueOf(r),
                    "Phân khu công trình số " + r + " (hướng Đông-Bắc)",
                    String.format("qa-20-img-%02d", ((r - 1) % 20) + 1),
                    "Bề mặt quan sát rõ nét qua ảnh chụp từ trên cao; dữ liệu lưu trữ đúng quy chuẩn tiếng Việt ă â đ ê ô ơ ư.")));
        }
        Map<String, Object> richTable = Map.of("type", "doc", "content", List.of(
                Map.of("type", "paragraph", "content", List.of(
                        Map.of("type", "text", "text", "Bảng tổng hợp 35 hạng mục kiểm tra chi tiết theo từng phân khu công trình:"))),
                Map.of("type", "table", "content", tableRows)
        ));

        String longViParagraph = "Báo cáo tổng hợp hiện trạng giám sát công trình bằng thiết bị bay không người lái (drone) "
                + "với đầy đủ dấu tiếng Việt: ă, â, đ, ê, ô, ơ, ư và các thanh điệu sắc, huyền, hỏi, ngã, nặng. "
                + "Mỗi ảnh bằng chứng đều gắn chặt với mã Media ID gốc, thời điểm chụp và trạng thái kiểm định rõ ràng. ".repeat(5);

        var doc = new ReportDocument(
                "Báo cáo kiểm tra tổng thể công trình — 20 ảnh & bảng dài",
                longViParagraph,
                "Giám sát toàn bộ 20 phân khu theo danh sách công việc đã duyệt.",
                "Thu thập ảnh từ trên cao, rà soát thủ công và đối chiếu với hồ sơ nghiệm thu.",
                List.of(
                        new ReportDocument.Section("sec-1", "Khu vực A (10 ảnh + bảng tổng hợp 35 dòng)",
                                "Bảng tổng hợp 35 hạng mục kiểm tra chi tiết theo từng phân khu công trình.",
                                batch1, List.of("f-1"), batch1.stream().map(ReportDocument.Image::mediaId).toList(), richTable),
                        new ReportDocument.Section("sec-2", "Khu vực B (10 ảnh bổ sung)",
                                longViParagraph,
                                batch2, List.of(), batch2.stream().map(ReportDocument.Image::mediaId).toList(), null)),
                List.of("Hình ảnh ghi nhận tại thời điểm bay, không thay thế kiểm định kết cấu chịu lực."),
                "Toàn bộ 20 ảnh và 35 dòng bảng tổng hợp đã được trình bày đầy đủ, không cắt chữ và lặp lại tiêu đề bảng qua trang.",
                List.of("Tiếp tục theo dõi định kỳ các phân khu ngoài trời sau mùa mưa."));

        var collection = new ReportEvidenceService.Collection(
                evs,
                List.of(new ReportEvidenceService.Finding("f-1", "Ghi nhận chi tiết phân khu A.", "COMPLIANT", List.of("qa-20-img-01"))),
                List.of());

        var ctx = new ReportRenderContext("m-20", "MS-STRESS-20", "Giám sát công trình", 9,
                "Trần Minh Quản Lý", "2026-11-24T10:00:00Z", "ORD-STRESS-20", "Toàn bộ công trình",
                "Khu đô thị Thủ Thiêm, TP. Thủ Đức", doc, collection, List.of(),
                ReportDesignTheme.CINEMATIC_AERIAL_EDITORIAL, ReportRenderContext.CURRENT_RENDERER_VERSION);

        byte[] bytes = reportPdf.render(ctx);
        assertTrue(bytes.length < 50 * 1024 * 1024);
        Files.write(OUT_DIR.resolve("stress-20-images-long-table.pdf"), bytes);

        try (PDDocument pdf = Loader.loadPDF(bytes)) {
            assertTrue(pdf.getNumberOfPages() >= 6);
            String text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("ă, â, đ, ê, ô, ơ, ư"));
            assertTrue(text.contains("qa-20-img-20"));
            assertTrue(text.contains("Phân khu công trình số 35"));
            // Repeating header check: "Khu vực kiểm tra" should appear >= 2 times across pages
            int headerCount = text.split("Khu vực kiểm tra", -1).length - 1;
            assertTrue(headerCount >= 2, "Long table must repeat its header across page breaks (found " + headerCount + ")");
        }
    }

    @Test
    void rendersAllFourServiceCategoriesCleanly() throws Exception {
        for (String svc : List.of("Kiểm tra nhà xưởng", "Giám sát khu vực", "Giám sát rừng", "Giám sát công trình")) {
            var doc = new ReportDocument(
                    "Báo cáo " + svc,
                    "Tóm tắt kết quả cho dịch vụ " + svc + ".",
                    "Phạm vi theo đơn hàng " + svc + ".",
                    "Thu thập dữ liệu ảnh drone đã duyệt.",
                    List.of(new ReportDocument.Section("s1", "Ghi nhận chính", "Hiện trạng ghi nhận từ ảnh nguồn.",
                            List.of(new ReportDocument.Image("qa-svc-01", "OVERVIEW", "Ảnh đại diện " + svc)),
                            List.of(), List.of("qa-svc-01"), null)),
                    List.of("Không thay thế đánh giá chuyên ngành."),
                    "Hoàn thành bàn giao báo cáo cho dịch vụ " + svc + ".",
                    List.of("Lưu hồ sơ định kỳ."));
            byte[] bytes = reportPdf.render("m-svc", "MS-SVC", svc, 2, "Manager", "2026-11-24T08:00:00Z", doc);
            try (PDDocument pdf = Loader.loadPDF(bytes)) {
                assertTrue(new PDFTextStripper().getText(pdf).contains(svc));
            }
        }
    }

    // ---- Fixture builders ----

    private static ReportDocument sampleFiveImageDocument() {
        Map<String, Object> rich = Map.of("type", "doc", "content", List.of(
                Map.of("type", "paragraph", "content", List.of(
                        Map.of("type", "text", "text", "Đợt bay ghi nhận hiện trạng bề mặt mái tôn và khu vực khe thoát nước khu A-B. "),
                        Map.of("type", "text", "text", "Các điểm cần lưu ý", "marks", List.of(Map.of("type", "bold"))),
                        Map.of("type", "text", "text", " đã được đối chiếu trực tiếp với nhận xét đã duyệt của nhân sự nghiệm thu."))),
                Map.of("type", "table", "content", List.of(
                        tableRow(true, List.of("Khu vực", "Mã ảnh nguồn", "Trạng thái hồ sơ", "Ghi nhận")),
                        tableRow(false, List.of("Mái khu A (Đông)", "qa-img-01", "Đã đối chiếu", "Độ phủ toàn cảnh rõ nét, đủ chi tiết tổng thể.")),
                        tableRow(false, List.of("Khe thoát nước B1", "qa-img-02", "Cần theo dõi", "Ghi nhận vệt ố cục bộ quanh vị trí nối máng.")),
                        tableRow(false, List.of("Dãy pin áp mái B2", "qa-img-04", "Chưa phân tích", "Ảnh rõ nét, chưa có tín hiệu phân tích tự động."))))
        ));

        return new ReportDocument(
                "Báo cáo giám sát mái nhà xưởng & hệ thống áp mái Khu A-B",
                "Báo cáo tổng hợp 5 ảnh drone đã được phê duyệt trong nhiệm vụ MS-2611-0149 tại KCN Hiệp Phước. "
                        + "Hồ sơ ghi nhận đầy đủ toàn cảnh bề mặt mái, khe thoát nước và cụm tấm pin áp mái, đối chiếu với 2 nhận xét đã xác minh từ bộ phận nghiệm thu.",
                "Giám sát trực quan bề mặt mái tôn, hệ thống máng xối và cụm thiết bị áp mái khu A-B trong ranh giới nhiệm vụ đã được khách hàng phê duyệt.",
                "Thu thập ảnh độ phân giải cao bằng drone theo lưới bay tiêu chuẩn, sàng lọc tín hiệu chất lượng và chỉ sử dụng ảnh đã được nhân sự phê duyệt.",
                List.of(
                        new ReportDocument.Section(
                                "sec-overview",
                                "Tổng quan hiện trạng mái nhà xưởng",
                                "Đợt bay ghi nhận hiện trạng bề mặt mái tôn và khu vực khe thoát nước khu A-B. Các điểm cần lưu ý đã được đối chiếu trực tiếp với nhận xét đã duyệt của nhân sự nghiệm thu.",
                                List.of(
                                        new ReportDocument.Image("qa-img-01", "OVERVIEW", "Toàn cảnh khu vực mái nhà xưởng khu A-B nhìn từ hướng Tây-Nam (QA fixture)."),
                                        new ReportDocument.Image("qa-img-02", "EVIDENCE", "Cận cảnh vị trí khe thoát nước khu B1 gắn với nhận xét FND-01 (QA fixture)."),
                                        new ReportDocument.Image("qa-img-03", "EVIDENCE", "Góc chụp bổ sung dọc mép mái khu B1 phục vụ đối chiếu (QA fixture).")),
                                List.of("FND-01"),
                                List.of("qa-img-01", "qa-img-02", "qa-img-03"),
                                rich),
                        new ReportDocument.Section(
                                "sec-solar",
                                "Khu vực dãy pin năng lượng áp mái",
                                "Các ảnh bổ trợ ghi nhận bề mặt dãy pin áp mái khu B2 trong điều kiện ánh sáng ổn định. Trạng thái chất lượng ảnh được ghi nhận trung thực theo dữ liệu hệ thống.",
                                List.of(
                                        new ReportDocument.Image("qa-img-04", "SUPPORTING", "Dãy tấm pin áp mái khu B2 — góc chụp trực diện (QA fixture)."),
                                        new ReportDocument.Image("qa-img-05", "SUPPORTING", "Hành lang kỹ thuật và hộp đấu nối dọc mép mái (QA fixture).")),
                                List.of("FND-02"),
                                List.of("qa-img-04", "qa-img-05"),
                                null)),
                List.of(
                        "Báo cáo chỉ phản ánh hiện trạng quan sát trực quan từ ảnh drone tại thời điểm bay.",
                        "Không suy diễn kích thước vật lý hoặc kết luận kết cấu từ độ phân giải pixel ảnh.",
                        "Các ảnh có trạng thái NOT_ANALYZED chưa có tín hiệu phân tích tự động và không được coi là PASS."),
                "Hồ sơ ảnh và nhận xét nguồn cho nhiệm vụ MS-2611-0149 đã được tổng hợp đầy đủ, sẵn sàng bàn giao cho khách hàng làm cơ sở theo dõi bảo trì định kỳ.",
                List.of(
                        "Kiểm tra thực địa tại vị trí khe thoát nước B1 trước đợt mưa lớn sắp tới.",
                        "Duy trì lịch bay giám sát định kỳ theo quý để xây dựng chuỗi đối chiếu chuẩn hóa."));
    }

    private static ReportEvidenceService.Collection sampleFiveImageCollection() {
        return new ReportEvidenceService.Collection(
                List.of(
                        new ReportEvidenceService.Evidence("qa-img-01", "qa-img-01.jpg", Instant.parse("2026-11-24T02:10:00Z"), 1200, 800, "PASS", 90, true, "QA", List.of("FND-01"), true, "sha-1"),
                        new ReportEvidenceService.Evidence("qa-img-02", "qa-img-02.jpg", Instant.parse("2026-11-24T02:12:00Z"), 1200, 800, "PASS", 85, true, "QA", List.of("FND-01"), true, "sha-2"),
                        new ReportEvidenceService.Evidence("qa-img-03", "qa-img-03.jpg", Instant.parse("2026-11-24T02:14:00Z"), 1200, 800, "WARNING", 70, true, "QA", List.of("FND-01"), false, "sha-3"),
                        new ReportEvidenceService.Evidence("qa-img-04", "qa-img-04.jpg", Instant.parse("2026-11-24T02:16:00Z"), 1200, 800, "NOT_ANALYZED", 65, true, "QA", List.of("FND-02"), false, "sha-4"),
                        new ReportEvidenceService.Evidence("qa-img-05", "qa-img-05.jpg", Instant.parse("2026-11-24T02:18:00Z"), 1200, 800, "PASS", 80, true, "QA", List.of("FND-02"), false, "sha-5")),
                List.of(
                        new ReportEvidenceService.Finding("FND-01", "Ghi nhận vệt ố cục bộ quanh vị trí nối máng khu B1.", "NEEDS_ATTENTION", List.of("qa-img-01", "qa-img-02", "qa-img-03")),
                        new ReportEvidenceService.Finding("FND-02", "Dãy pin áp mái khu B2 quan sát rõ nét.", "COMPLIANT", List.of("qa-img-04", "qa-img-05"))),
                List.of());
    }

    private static Map<String, Object> tableRow(boolean header, List<String> cells) {
        String cellType = header ? "tableHeader" : "tableCell";
        return Map.of("type", "tableRow", "content", cells.stream().map(txt ->
                Map.<String, Object>of("type", cellType, "content", List.of(
                        Map.of("type", "paragraph", "content", List.of(Map.of("type", "text", "text", txt)))))).toList());
    }

    private static void renderAllPagesToPng(PDDocument pdf, String prefix) throws IOException {
        var renderer = new PDFRenderer(pdf);
        for (int i = 0; i < pdf.getNumberOfPages(); i++) {
            BufferedImage pageImg = renderer.renderImageWithDPI(i, 120);
            ImageIO.write(pageImg, "png", OUT_DIR.resolve(prefix + "-page-" + (i + 1) + ".png").toFile());
        }
    }

    /**
     * Generates a high-contrast, aspect-ratio-preserved synthetic QA plate explicitly labeled
     * "QA FIXTURE" so it is never confused with real customer drone evidence.
     */
    private static BufferedImage createLabeledQaFixturePlate(String mediaId) {
        int w = 1200, h = 750;
        var img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int hash = Math.abs(mediaId.hashCode());
        Color top = new Color(24 + (hash % 35), 58 + (hash % 45), 98 + (hash % 55));
        Color bot = new Color(185 + (hash % 30), 205 + (hash % 25), 225 + (hash % 20));
        g.setPaint(new GradientPaint(0, 0, top, 0, h, bot));
        g.fillRect(0, 0, w, h);

        // Subtle telemetry grid lines (non-real QA graphic)
        g.setColor(new Color(255, 255, 255, 42));
        for (int x = 100; x < w; x += 100) g.drawLine(x, 0, x, h);
        for (int y = 100; y < h; y += 100) g.drawLine(0, y, w, y);

        // Framing box & explicit QA FIXTURE watermark
        g.setColor(new Color(15, 23, 42, 175));
        g.fillRoundRect(70, 255, w - 140, 230, 24, 24);
        g.setColor(new Color(56, 189, 248));
        g.setStroke(new BasicStroke(3f));
        g.drawRoundRect(70, 255, w - 140, 230, 24, 24);

        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 44));
        g.drawString("QA FIXTURE PLATE — " + mediaId, 115, 345);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 26));
        g.setColor(new Color(203, 213, 225));
        g.drawString("Synthetic test image for PDF layout & aspect-ratio QA (not real customer evidence)", 115, 405);
        g.drawString("1200 x 750 px · 16:10 · OnDemand Monitor PDF Design QA", 115, 448);
        g.dispose();
        return img;
    }
}
