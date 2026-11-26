package com.ondemandmonitoring.inspectionreport;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;

/**
 * Deterministic PDFBox 3.0.8 layout & vector drawing canvas.
 * Guarantees consistent A4 geometry, Vietnamese UTF-8 glyph safety, orphan-heading
 * prevention, repeating table headers, and aspect-ratio-preserving image rendering.
 */
final class PdfCanvas {
    static final float PAGE_W = PDRectangle.A4.getWidth();   // 595.28
    static final float PAGE_H = PDRectangle.A4.getHeight();  // 841.89
    static final float MARGIN_X = 46f;
    static final float CONTENT_W = PAGE_W - MARGIN_X * 2;    // 503.28
    static final float TOP_Y = PAGE_H - 64f;                 // 777.89 (below running header)
    static final float COVER_TOP_Y = PAGE_H - 44f;           // 797.89 (no header on cover)
    static final float BOTTOM_Y = 56f;                       // above running footer

    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\n|[^\\S\\n]+|[^\\s]+");

    final PDDocument pdf;
    final PDType0Font regular;
    final PDType0Font bold;
    final PDType0Font italic;
    final ReportDesignTheme theme;

    PDPageContentStream stream;
    float y;
    int pageCount;
    int sectionCounter;

    record Span(String text, PDType0Font font) {}

    PdfCanvas(PDDocument pdf, PDType0Font regular, PDType0Font bold, PDType0Font italic, ReportDesignTheme theme) {
        this.pdf = pdf;
        this.regular = regular;
        this.bold = bold;
        this.italic = italic;
        this.theme = theme;
    }

    void closeStream() throws IOException {
        if (stream != null) {
            stream.close();
            stream = null;
        }
    }

    void newPage(boolean isCover) throws IOException {
        closeStream();
        var page = new PDPage(PDRectangle.A4);
        pdf.addPage(page);
        stream = new PDPageContentStream(pdf, page);
        pageCount++;
        y = isCover ? COVER_TOP_Y : TOP_Y;
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

    float remainingHeight() {
        return Math.max(0f, y - BOTTOM_Y);
    }

    void space(float pts) {
        y -= pts;
    }

    // ---- Low-level vector primitives ----

    void fillRect(float x, float bottomY, float w, float h, Color color) throws IOException {
        stream.setNonStrokingColor(color);
        stream.addRect(x, bottomY, w, h);
        stream.fill();
    }

    void strokeRect(float x, float bottomY, float w, float h, Color color, float lineWidth) throws IOException {
        stream.setStrokingColor(color);
        stream.setLineWidth(lineWidth);
        stream.addRect(x, bottomY, w, h);
        stream.stroke();
    }

    void fillAndStrokeRect(float x, float bottomY, float w, float h, Color fill, Color stroke, float lineWidth) throws IOException {
        fillRect(x, bottomY, w, h, fill);
        strokeRect(x, bottomY, w, h, stroke, lineWidth);
    }

    void hLine(float x1, float x2, float lineY, Color color, float lineWidth) throws IOException {
        stream.setStrokingColor(color);
        stream.setLineWidth(lineWidth);
        stream.moveTo(x1, lineY);
        stream.lineTo(x2, lineY);
        stream.stroke();
    }

    void drawTextRaw(String text, float x, float baselineY, float size, PDType0Font face, Color color) throws IOException {
        String safe = sanitize(text, face);
        if (safe.isEmpty()) return;
        stream.setNonStrokingColor(color);
        stream.beginText();
        stream.setFont(face, size);
        stream.newLineAtOffset(x, baselineY);
        stream.showText(safe);
        stream.endText();
    }

    float textWidth(String text, float size, PDType0Font face) throws IOException {
        String safe = sanitize(text, face);
        if (safe.isEmpty()) return 0f;
        return face.getStringWidth(safe) / 1000f * size;
    }

    String sanitize(String raw, PDType0Font face) {
        if (raw == null || raw.isEmpty()) return "";
        String cleaned = raw.replace('\t', ' ').replaceAll("[\\p{Cntrl}&&[^\\n]]", " ");
        var out = new StringBuilder(cleaned.length());
        for (int cp : cleaned.codePoints().toArray()) {
            if (cp == '\n') {
                out.append('\n');
                continue;
            }
            String ch = new String(Character.toChars(cp));
            try {
                face.getStringWidth(ch);
                out.append(ch);
            } catch (Exception ignored) {
                out.append(' ');
            }
        }
        return out.toString();
    }

    // ---- Text wrapping & typography ----

    List<String> wrap(String raw, float size, float maxWidth, PDType0Font face) throws IOException {
        List<String> result = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return result;
        String safe = sanitize(raw, face);
        for (String paragraph : safe.split("\\n", -1)) {
            if (paragraph.isBlank()) {
                result.add("");
                continue;
            }
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.trim().split("\\s+")) {
                if (word.isEmpty()) continue;
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (textWidth(candidate, size, face) <= maxWidth) {
                    line.setLength(0);
                    line.append(candidate);
                } else {
                    if (!line.isEmpty()) {
                        result.add(line.toString());
                        line.setLength(0);
                    }
                    if (textWidth(word, size, face) <= maxWidth) {
                        line.append(word);
                    } else {
                        for (int cp : word.codePoints().toArray()) {
                            String ch = new String(Character.toChars(cp));
                            if (!line.isEmpty() && textWidth(line + ch, size, face) > maxWidth) {
                                result.add(line.toString());
                                line.setLength(0);
                            }
                            line.append(ch);
                        }
                    }
                }
            }
            if (!line.isEmpty()) result.add(line.toString());
        }
        return result;
    }

    void drawParagraph(String raw, float size, float leading, float x, float width, PDType0Font face, Color color) throws IOException {
        for (String line : wrap(raw, size, width, face)) {
            ensure(leading + 4f);
            if (!line.isEmpty()) {
                drawTextRaw(line, x, y - size, size, face, color);
            }
            y -= leading;
        }
    }

    void drawBody(String raw) throws IOException {
        drawParagraph(raw, 10f, 15.2f, MARGIN_X, CONTENT_W, regular, ReportDesignTheme.INK_BODY);
    }

    /**
     * Draws a section heading with orphan protection (reserves heading + >= 48pt of following body).
     */
    void drawSectionHeading(String title, String eyebrow) throws IOException {
        String displayTitle = theme.numberedHeadings
                ? String.format("%02d. %s", ++sectionCounter, title)
                : title;
        List<String> titleLines = wrap(displayTitle, 14.5f, CONTENT_W - 12f, bold);
        float blockH = (eyebrow != null ? 14f : 0f) + titleLines.size() * 20f + 10f;
        ensure(blockH + 54f); // keep-with-next protection
        if (y < TOP_Y - 12f) space(12f);

        float topStart = y;
        if (eyebrow != null && !eyebrow.isBlank()) {
            drawTextRaw(eyebrow.toUpperCase(Locale.ROOT), MARGIN_X, y - 8f, 7.5f, bold, theme.accentBlue);
            y -= 13f;
        }
        for (String line : titleLines) {
            drawTextRaw(line, MARGIN_X, y - 14.5f, 14.5f, bold, theme.primaryNavy);
            y -= 20.5f;
        }
        // Subtle editorial accent underline with clear descender clearance for Vietnamese glyphs
        hLine(MARGIN_X, MARGIN_X + CONTENT_W, y - 2.5f, theme.borderHairline, 0.75f);
        hLine(MARGIN_X, MARGIN_X + 42f, y - 2.5f, theme.accentBlue, 2.0f);
        y -= 13f;
        if (topStart == y) y -= 8f;
    }

    void drawBulletList(List<String> items) throws IOException {
        if (items == null || items.isEmpty()) return;
        for (String item : items) {
            if (item == null || item.isBlank()) continue;
            List<String> wrapped = wrap(item, 9.8f, CONTENT_W - 16f, regular);
            ensure(Math.min(wrapped.size(), 2) * 14.8f + 6f);
            for (int i = 0; i < wrapped.size(); i++) {
                ensure(15f);
                if (i == 0) {
                    fillRect(MARGIN_X + 3f, y - 8.2f, 3.8f, 3.8f, theme.accentBlue);
                }
                drawTextRaw(wrapped.get(i), MARGIN_X + 14f, y - 9.8f, 9.8f, regular, ReportDesignTheme.INK_BODY);
                y -= 14.8f;
            }
            y -= 3f;
        }
    }

    // ---- Image drawing (aspect-ratio preserving, high quality, bounded size) ----

    void drawImageAt(BufferedImage bitmap, float x, float bottomY, float drawW, float drawH) throws IOException {
        int targetW = Math.max(1, Math.min(bitmap.getWidth(), Math.min(1800, (int) Math.ceil(drawW * 2.2f))));
        int targetH = Math.max(1, Math.min(bitmap.getHeight(), Math.min(1800, (int) Math.ceil(drawH * 2.2f))));
        var resized = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, targetW, targetH);
        g.drawImage(bitmap, 0, 0, targetW, targetH, null);
        g.dispose();
        var pdImage = JPEGFactory.createFromImage(pdf, resized, 0.88f);
        stream.drawImage(pdImage, x, bottomY, drawW, drawH);
    }

    float[] fitInside(int imgW, int imgH, float maxW, float maxH) {
        float scale = Math.min(maxW / Math.max(1, imgW), maxH / Math.max(1, imgH));
        return new float[]{imgW * scale, imgH * scale};
    }

    // ---- Tiptap RichText renderer ----

    void drawRichText(Map<String, Object> node) throws IOException {
        drawRichNode(node, 0, null);
    }

    private void drawRichNode(Map<String, Object> node, int indentLevel, String listPrefix) throws IOException {
        String type = Objects.toString(node.get("type"), "");
        switch (type) {
            case "table" -> {
                drawRichTable(node);
                return;
            }
            case "heading" -> {
                List<Span> spans = new ArrayList<>();
                collectInline(node, spans, true);
                ensure(44f);
                y -= 4f;
                drawStyledSpans(spans, 11.8f, 17f, MARGIN_X, CONTENT_W, theme.primaryNavy);
                y -= 3f;
                return;
            }
            case "paragraph" -> {
                List<Span> spans = new ArrayList<>();
                collectInline(node, spans, false);
                float indentX = MARGIN_X + indentLevel * 14f;
                float availW = CONTENT_W - indentLevel * 14f;
                if (listPrefix != null) {
                    ensure(16f);
                    drawTextRaw(listPrefix, indentX - 12f, y - 10f, 10f, bold, theme.accentBlue);
                }
                drawStyledSpans(spans, 10f, 15.2f, indentX, availW, ReportDesignTheme.INK_BODY);
                y -= 3f;
                return;
            }
            case "blockquote" -> {
                String quoteText = ReportRichText.text(node);
                List<String> lines = wrap(quoteText, 9.8f, CONTENT_W - 24f, italic);
                float boxH = Math.max(24f, lines.size() * 14.8f + 12f);
                ensure(boxH + 6f);
                fillRect(MARGIN_X, y - boxH, CONTENT_W, boxH, theme.surfaceTint);
                fillRect(MARGIN_X, y - boxH, 3f, boxH, theme.accentBlue);
                float ty = y - 7f;
                for (String line : lines) {
                    drawTextRaw(line, MARGIN_X + 12f, ty - 9.8f, 9.8f, italic, theme.bodyMuted);
                    ty -= 14.8f;
                }
                y -= boxH + 8f;
                return;
            }
            case "horizontalRule" -> {
                ensure(16f);
                hLine(MARGIN_X, MARGIN_X + CONTENT_W, y - 6f, theme.borderHairline, 0.75f);
                y -= 14f;
                return;
            }
            case "bulletList" -> {
                for (var child : ReportRichText.children(node)) {
                    drawListItem(child, indentLevel + 1, "•");
                }
                y -= 3f;
                return;
            }
            case "orderedList" -> {
                int idx = 1;
                if (node.get("attrs") instanceof Map<?, ?> attrs && attrs.get("start") instanceof Number n) {
                    idx = n.intValue();
                }
                for (var child : ReportRichText.children(node)) {
                    drawListItem(child, indentLevel + 1, (idx++) + ".");
                }
                y -= 3f;
                return;
            }
            default -> {
                for (var child : ReportRichText.children(node)) {
                    drawRichNode(child, indentLevel, listPrefix);
                }
            }
        }
    }

    private void drawListItem(Map<String, Object> itemNode, int indentLevel, String prefix) throws IOException {
        var children = ReportRichText.children(itemNode);
        for (int i = 0; i < children.size(); i++) {
            drawRichNode(children.get(i), indentLevel, i == 0 ? prefix : null);
        }
    }

    private void collectInline(Map<String, Object> node, List<Span> spans, boolean forceBold) {
        String type = Objects.toString(node.get("type"), "");
        if ("text".equals(type)) {
            var marks = node.get("marks") instanceof List<?> list ? list : List.of();
            boolean isBold = forceBold || marks.stream().anyMatch(m -> m instanceof Map<?, ?> mark && "bold".equals(mark.get("type")));
            boolean isItalic = marks.stream().anyMatch(m -> m instanceof Map<?, ?> mark && "italic".equals(mark.get("type")));
            PDType0Font face = isBold ? bold : isItalic ? italic : regular;
            spans.add(new Span(Objects.toString(node.get("text"), ""), face));
        } else if ("hardBreak".equals(type)) {
            spans.add(new Span("\n", regular));
        } else {
            for (var child : ReportRichText.children(node)) {
                collectInline(child, spans, forceBold);
            }
        }
    }

    private void drawStyledSpans(List<Span> spans, float size, float leading, float startX, float maxWidth, Color color) throws IOException {
        float x = startX;
        float rightEdge = startX + maxWidth;
        ensure(leading + 2f);
        boolean drewAnything = false;
        for (var span : spans) {
            String safe = sanitize(span.text(), span.font());
            var matcher = TOKEN_PATTERN.matcher(safe);
            while (matcher.find()) {
                String token = matcher.group();
                if ("\n".equals(token)) {
                    y -= leading;
                    ensure(leading + 2f);
                    x = startX;
                    continue;
                }
                float w = textWidth(token, size, span.font());
                if (x + w > rightEdge && x > startX) {
                    if (token.isBlank()) continue;
                    y -= leading;
                    ensure(leading + 2f);
                    x = startX;
                }
                if (w > maxWidth) {
                    for (String part : wrap(token, size, maxWidth, span.font())) {
                        ensure(leading + 2f);
                        drawTextRaw(part, startX, y - size, size, span.font(), color);
                        y -= leading;
                        x = startX;
                        drewAnything = true;
                    }
                } else {
                    drawTextRaw(token, x, y - size, size, span.font(), color);
                    x += w;
                    drewAnything = true;
                }
            }
        }
        if (drewAnything || spans.isEmpty()) {
            y -= leading;
        }
    }

    // ---- Styled Table with Automatic Repeating Header across Page Breaks ----

    void drawRichTable(Map<String, Object> tableNode) throws IOException {
        var rows = ReportRichText.children(tableNode);
        if (rows.isEmpty()) return;
        List<List<TableCellSpec>> specRows = new ArrayList<>();
        int maxCols = 1;
        for (var row : rows) {
            var cells = ReportRichText.children(row);
            if (cells.isEmpty()) continue;
            maxCols = Math.max(maxCols, cells.size());
            List<TableCellSpec> specRow = new ArrayList<>();
            for (var cell : cells) {
                boolean isHeader = "tableHeader".equals(cell.get("type"));
                var attrs = cell.get("attrs") instanceof Map<?, ?> map ? map : Map.of();
                String align = Objects.toString(attrs.get("align"), "left");
                specRow.add(new TableCellSpec(ReportRichText.text(cell), isHeader, align));
            }
            specRows.add(specRow);
        }
        if (specRows.isEmpty()) return;
        float[] colWidths = new float[maxCols];
        Arrays.fill(colWidths, CONTENT_W / maxCols);
        drawStyledTable(specRows, colWidths);
    }

    record TableCellSpec(String text, boolean header, String align) {}

    void drawStyledTable(List<List<TableCellSpec>> rows, float[] colWidths) throws IOException {
        if (rows.isEmpty()) return;
        List<TableCellSpec> headerRow = rows.getFirst().stream().anyMatch(TableCellSpec::header)
                ? rows.getFirst()
                : null;
        int bodyIndex = 0;

        for (int r = 0; r < rows.size(); r++) {
            var row = rows.get(r);
            boolean isHeader = row.stream().anyMatch(TableCellSpec::header);
            float fontSize = isHeader ? 8.8f : 8.8f;
            float lineH = 13f;

            List<List<String>> wrappedCells = new ArrayList<>();
            int maxLines = 1;
            for (int c = 0; c < row.size(); c++) {
                float cw = c < colWidths.length ? colWidths[c] : colWidths[colWidths.length - 1];
                PDType0Font face = row.get(c).header() ? bold : regular;
                var lines = wrap(row.get(c).text(), fontSize, Math.max(20f, cw - 12f), face);
                wrappedCells.add(lines);
                maxLines = Math.max(maxLines, lines.size());
            }

            float rowH = maxLines * lineH + 11f;
            if (isHeader && r == 0) {
                ensure(rowH + 34f); // never orphan table header
            } else if (y - rowH < BOTTOM_Y) {
                newPage(false);
                if (headerRow != null && r > 0) {
                    renderTableRow(headerRow, colWidths, true, false);
                }
            }

            // If a single row is taller than one page, split it cleanly across pages
            if (rowH > (TOP_Y - BOTTOM_Y - 24f)) {
                int startLine = 0;
                while (startLine < maxLines) {
                    int fitLines = Math.max(1, (int) ((remainingHeight() - 12f) / lineH));
                    if (remainingHeight() < 34f) {
                        newPage(false);
                        if (headerRow != null && !isHeader) {
                            renderTableRow(headerRow, colWidths, true, false);
                        }
                        fitLines = Math.max(1, (int) ((remainingHeight() - 12f) / lineH));
                    }
                    int count = Math.min(maxLines - startLine, fitLines);
                    drawTableSlice(row, wrappedCells, colWidths, startLine, count, lineH, fontSize, isHeader, !isHeader && (bodyIndex % 2 == 1));
                    startLine += count;
                }
            } else {
                renderTableRow(row, colWidths, isHeader, !isHeader && (bodyIndex % 2 == 1));
            }
            if (!isHeader) bodyIndex++;
        }
        space(10f);
    }

    private void renderTableRow(List<TableCellSpec> row, float[] colWidths, boolean isHeader, boolean zebra) throws IOException {
        float fontSize = 8.8f;
        float lineH = 13f;
        List<List<String>> wrappedCells = new ArrayList<>();
        int maxLines = 1;
        for (int c = 0; c < row.size(); c++) {
            float cw = c < colWidths.length ? colWidths[c] : colWidths[colWidths.length - 1];
            PDType0Font face = row.get(c).header() ? bold : regular;
            var lines = wrap(row.get(c).text(), fontSize, Math.max(20f, cw - 12f), face);
            wrappedCells.add(lines);
            maxLines = Math.max(maxLines, lines.size());
        }
        drawTableSlice(row, wrappedCells, colWidths, 0, maxLines, lineH, fontSize, isHeader, zebra);
    }

    private void drawTableSlice(
            List<TableCellSpec> row,
            List<List<String>> wrappedCells,
            float[] colWidths,
            int startLine,
            int count,
            float lineH,
            float fontSize,
            boolean isHeader,
            boolean zebra) throws IOException {
        float rowH = count * lineH + 11f;
        float bottomY = y - rowH;
        Color bg = isHeader ? theme.tableHeaderBg : (zebra ? theme.surfaceTint : Color.WHITE);
        fillRect(MARGIN_X, bottomY, CONTENT_W, rowH, bg);

        float cx = MARGIN_X;
        for (int c = 0; c < row.size(); c++) {
            float cw = c < colWidths.length ? colWidths[c] : colWidths[colWidths.length - 1];
            strokeRect(cx, bottomY, cw, rowH, theme.borderHairline, 0.55f);
            PDType0Font face = row.get(c).header() ? bold : regular;
            Color fg = row.get(c).header() ? theme.primaryNavy : ReportDesignTheme.INK_BODY;
            var lines = wrappedCells.get(c);
            for (int i = 0; i < count && startLine + i < lines.size(); i++) {
                String val = lines.get(startLine + i);
                float tw = textWidth(val, fontSize, face);
                float offset = "right".equals(row.get(c).align())
                        ? Math.max(6f, cw - tw - 6f)
                        : "center".equals(row.get(c).align())
                        ? Math.max(6f, (cw - tw) / 2f)
                        : 6f;
                drawTextRaw(val, cx + offset, y - 6f - fontSize - i * lineH, fontSize, face, fg);
            }
            cx += cw;
        }
        if (isHeader) {
            hLine(MARGIN_X, MARGIN_X + CONTENT_W, bottomY, theme.accentBlue, 1.0f);
        }
        y = bottomY;
    }

    // ---- Running header & footer stamped across all pages ----

    void stampHeadersAndFooters(ReportRenderContext ctx) throws IOException {
        closeStream();
        int total = pdf.getNumberOfPages();
        String shortTitle = ctx.document().reportTitle();
        if (shortTitle.length() > 64) shortTitle = shortTitle.substring(0, 61) + "…";
        String rightHead = ctx.missionCode() + " · v" + ctx.revision();

        for (int i = 0; i < total; i++) {
            var page = pdf.getPage(i);
            try (var s = new PDPageContentStream(pdf, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                // Running header on pages 2..N (cover page stays clean)
                if (i > 0) {
                    s.setNonStrokingColor(theme.bodyMuted);
                    s.beginText();
                    s.setFont(bold, 7.8f);
                    s.newLineAtOffset(MARGIN_X, PAGE_H - 36f);
                    s.showText(sanitize("ONDEMAND MONITOR  |  " + shortTitle, bold));
                    s.endText();

                    float rw = textWidth(rightHead, 7.8f, regular);
                    s.beginText();
                    s.setFont(regular, 7.8f);
                    s.newLineAtOffset(PAGE_W - MARGIN_X - rw, PAGE_H - 36f);
                    s.showText(sanitize(rightHead, regular));
                    s.endText();

                    s.setStrokingColor(theme.borderHairline);
                    s.setLineWidth(0.6f);
                    s.moveTo(MARGIN_X, PAGE_H - 44f);
                    s.lineTo(PAGE_W - MARGIN_X, PAGE_H - 44f);
                    s.stroke();
                }

                // Running footer on every page
                s.setStrokingColor(theme.borderHairline);
                s.setLineWidth(0.6f);
                s.moveTo(MARGIN_X, 40f);
                s.lineTo(PAGE_W - MARGIN_X, 40f);
                s.stroke();

                String footerLeft = "OnDemand Monitor · " + ctx.missionCode() + " · " + ctx.rendererVersion();
                s.setNonStrokingColor(theme.bodyMuted);
                s.beginText();
                s.setFont(regular, 7.8f);
                s.newLineAtOffset(MARGIN_X, 26f);
                s.showText(sanitize(footerLeft, regular));
                s.endText();

                String pageText = "Trang " + (i + 1) + " / " + total;
                float pw = textWidth(pageText, 8f, bold);
                s.setNonStrokingColor(theme.primaryNavy);
                s.beginText();
                s.setFont(bold, 8f);
                s.newLineAtOffset(PAGE_W - MARGIN_X - pw, 26f);
                s.showText(sanitize(pageText, bold));
                s.endText();
            }
        }
    }
}
