# PDF Design System — AI Inspection Report Studio

**Renderer Version:** `pdfbox-design-v2`  
**Selected Production Theme:** `CINEMATIC_AERIAL_EDITORIAL` (`cinematic-aerial-editorial`)  
**Page Geometry:** A4 Portrait (`595.28 × 841.89 pt`), left/right margin `46 pt`, content width `503.28 pt`, top margin `64 pt` (content pages) / `44 pt` (cover), bottom margin `56 pt`.

## 1. Visual Identity & Tokens

- **Paper & Ink:** Pure white page (`#FFFFFF`) for print clarity and minimal ink usage; deep midnight navy headings (`#0B192C`), slate body ink (`#1E293B`), muted metadata ink (`#475569`).
- **Accents:** Aerial sky blue (`#0284C7`) for editorial eyebrows, active left rules, and section underlines; soft sky tint (`#F0F9FF`) for callouts; cool slate hairlines (`#CBD5E1`).
- **Typography:** Embedded OFL-licensed `NotoSans-Regular.ttf`, `NotoSans-Bold.ttf`, and `NotoSans-Italic.ttf` via `PDType0Font` (full Vietnamese UTF-8 diacritics, selectable and searchable text).
  - Cover Title: `25 pt Bold` (leading `31 pt`)
  - Section Heading: `14.5 pt Bold` (leading `20.5 pt`) with `7.5 pt Bold` uppercase eyebrow + `2 pt` sky-blue accent rule (`2.5 pt` below baseline to clear Vietnamese descenders)
  - Body Copy: `10 pt Regular` (leading `15.2 pt`)
  - Tables: `8.8 pt` header (`Bold`) & cells (`Regular`), leading `13 pt`
  - Captions: `9.2 pt Bold` (feature) / `8.4 pt Bold` (gallery); metadata `7.2–7.6 pt Regular`

## 2. Three Concept Comparison

All three concepts were generated from the exact same 5-image QA fixture input and visually inspected page-by-page (`.ai-runtime/qa-artifacts/inspection-report/pdf-design-v2/`):

| Criterion | Concept 1: Modern Enterprise | Concept 2: Cinematic Aerial Editorial (**Selected**) | Concept 3: Technical Inspection |
|---|---|---|---|
| **Cover & Identity** | Royal blue top stripe + bordered 2-col metadata box | Clean gallery masthead, bold title + accent rule, largest hero photo (`305 pt`), 4-col editorial metadata strip | Engineering DOC-ID registration block + spec table above photo |
| **Vietnamese Typography** | Clean & crisp | Strongest editorial hierarchy & breathing room | Dense engineering tone, numbered headings (`01.`, `02.`) |
| **Photo & Evidence Focus** | Balanced | **Best** — hero photo commands the cover without cropping; feature plates & 2-col gallery feel like a published aerial report | More utilitarian; smaller cover photo (`255 pt`) |
| **KPI, Charts & Tables** | Identical vector engine (`#1D4ED8` accent) | High-contrast sky-blue accent (`#0284C7`) + semantic status colors | Cool gray header (`#E5E7EB`) + cobalt accent (`#1E40AF`) |
| **File Size (5 images + QA pair)** | ~284 KB (6 pages) | ~292 KB (6 pages) / ~248 KB production (5 pages) | ~278 KB (6 pages) |
| **Print Readiness** | High | **High** (white paper, distinct luminance in grayscale, zero dark background floods) | High |

**Selection Rationale:** `CINEMATIC_AERIAL_EDITORIAL` best matches the product direction (*Cinematic Aerial Inspection Report*): it elevates the Mission's aerial photography on a bright, print-safe editorial canvas while preserving rigorous engineering metadata and evidence traceability.

## 3. Reusable Page Components (14 Components)

Implemented in `ReportPageComponents.java` + `PdfCanvas.java`:

1. **`COVER_PAGE`**: Uses the Mission's first `OVERVIEW` photo (or first selected photo) aspect-ratio-preserved without cropping evidence, report title, service eyebrow, mission code, snapshot revision, approval status, and disclaimer.
2. **`MISSION_INFORMATION`**: Structured 2-column grid of mission code, service, snapshot revision, renderer version, order code, scope label, and location (omits unavailable optional fields).
3. **`KPI_OVERVIEW`**: Four factual metric cards derived strictly from the snapshot: Selected Images, Cited Source Findings, Content Sections, and Image Quality (`PASS / total` or explicit `"Chưa phân tích"` — never equating `NOT_ANALYZED` with `PASS`).
4. **`EXECUTIVE_SUMMARY`**: Soft sky-tinted callout box with `3.5 pt` left accent bar (flows cleanly across pages if longer than remaining page height).
5. **`INSPECTION_SCOPE`**: Scope and methodology subsections.
6. **`DATA_VISUALIZATION`**: 100% vector horizontal stacked bar + breakdown rows for `PASS`, `WARNING`, `FAIL`, and `NOT_ANALYZED` with an explicit legend note. Automatically omitted when no quality signals exist.
7. **`PHOTO_OVERVIEW`**: Full-width editorial feature plate (`maxH = 265 pt`, aspect-ratio preserved) with atomic left-accent caption, Media ID, role, capture timestamp, and quality status.
8. **`FINDING_DETAIL`**: Section heading, verified finding reference strip (when `sourceFindingIds` is non-empty), Tiptap rich text (`heading`, `paragraph`, `bold`, `italic`, `bulletList`, `orderedList`, `blockquote`, `horizontalRule`, `table`), and linked evidence photos.
9. **`BEFORE_AFTER_COMPARISON`**: Side-by-side verified comparison plate with explicit `TRƯỚC` / `SAU` labels, Media IDs, timestamps, and verified note. **Strictly hidden in production** while `beforeAfterPairs` is empty (no unverified pairing inference).
10. **`EVIDENCE_GALLERY`**: Balanced 2-column grid for multi-image sections (2+ supporting/evidence photos), keeping each photo + caption + metadata card atomic.
11. **`LIMITATIONS`**: Amber-tinted callout box (or flowing bullet list when long) for inspection limitations.
12. **`CONCLUSION`**: Bulleted recommendations + conclusion prose.
13. **`APPROVAL_METADATA`**: Formal sign-off block recording approver, approval timestamp, snapshot revision, design theme, and `pdfbox-design-v2` renderer version.
14. **`APPENDIX`**: Compact 5-column evidence manifest table (`Hình | Media ID | Vai trò | Thời điểm chụp | Chất lượng`) with automatic repeating header row across page breaks.

## 4. Security & Immutability Guarantees

- LLM output supplies structured JSON (`ReportDocument`) only — never HTML, CSS, scripts, or arbitrary URLs.
- All images are loaded through `ReportEvidenceService.requireMedia` + `readImage` with existing authorization, format, pixel, and 12 MiB limits; JPEG rendering copies at quality `0.88` keep total PDF size well below the 50 MiB cap.
- Already finalized/delivered reports stream their saved `pdf_bucket`/`pdf_key` bytes directly from storage and are never re-rendered by template upgrades.
