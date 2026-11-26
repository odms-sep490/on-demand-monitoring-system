# PDF Visual QA Report — `pdfbox-design-v2`

**Date:** 2026-11-24  
**Artifacts Directory:** `.ai-runtime/qa-artifacts/inspection-report/pdf-design-v2/` (local, gitignored)  
**Fixture Disclosure:** All photographs in the QA PDFs are synthetic `1200 × 750` plates explicitly labeled **`QA FIXTURE PLATE`** (not real customer drone photographs). Before/after comparison pairs in the 3 concept PDFs are explicitly labeled QA fixtures; in `production-sample.pdf` and production `InspectionReportService`, `beforeAfterPairs` is empty and `BEFORE_AFTER_COMPARISON` is hidden.

## 1. Generated Artifacts

- **Concept 1 (`MODERN_ENTERPRISE`)**: `concept-1-modern-enterprise.pdf` (6 pages, ~284 KB) + `concept-1-modern-enterprise-page-1..6.png`
- **Concept 2 (`CINEMATIC_AERIAL_EDITORIAL` — Selected Winner)**: `concept-2-cinematic-aerial-editorial.pdf` (6 pages, ~292 KB) + `concept-2-cinematic-aerial-editorial-page-1..6.png`
- **Concept 3 (`TECHNICAL_INSPECTION`)**: `concept-3-technical-inspection.pdf` (6 pages, ~278 KB) + `concept-3-technical-inspection-page-1..6.png`
- **Production Sample**: `production-sample.pdf` (5 pages, ~248 KB, no Before/After section) + `production-sample-page-1..5.png`
- **Stress Test (20 images + 35-row table + long Vietnamese text)**: `stress-20-images-long-table.pdf` (~583 KB)

## 2. Visual Inspection Findings & Fixes Applied

Every page of the three concept covers and all 6 pages of the winning `CINEMATIC_AERIAL_EDITORIAL` concept were rendered at 120 DPI via PDFBox `PDFRenderer` and visually inspected:

| Pass | Page / Component | Issue Observed During Visual Inspection | Fix Applied | Re-verified |
|---|---|---|---|---|
| Pass 1 | Page 4 bottom (`BEFORE_AFTER_COMPARISON`) | Section heading `So sánh Trước / Sau đã xác minh` appeared orphaned at bottom of Page 4 while the comparison plate moved to Page 5. | Added `c.ensure(295f)` before drawing the `BEFORE_AFTER_COMPARISON` heading so heading + first comparison plate always stay together. | Pass 2 verified |
| Pass 1 | Page 2 (`KPI_OVERVIEW`) | Sub-labels inside the 4 KPI cards truncated with `...` (`Bằng chứng đã duyệt tro...`, `Tham chiếu từ hồ sơ ngh...`). | Concise, non-truncating Vietnamese sub-labels (`Ảnh bằng chứng đã duyệt`, `Hồ sơ đã xác minh`, `Mục ghi nhận hiện trạng`, `Đạt chuẩn chất lượng (PASS)`). | Pass 2 verified |
| Pass 1 | Pages 2–6 (Section Headings) | Accent underline sat ~1–2 pt too close to Vietnamese descenders (`g`, `p`, `ụ`). | Lowered heading underline by `3.5 pt` (`y - 2.5f`) and increased line leading to `20.5 pt`. | Pass 2 verified |
| Pass 1 | Page 2 (`MISSION_INFORMATION`) | `Địa điểm` and `Phạm vi công việc` truncated at 32 chars. | Adjusted column offsets and font size (`8.2 pt`) to fit up to 36 chars cleanly. | Pass 2 verified |

## 3. Verified Quality Checklist

- [x] **Vietnamese Diacritics & Searchability**: Full diacritics (`ă, â, đ, ê, ô, ơ, ư` and all tones) verified both visually and via `PDFTextStripper`.
- [x] **Image Aspect Ratio & Integrity**: `1200 × 750` (16:10) fixture plates rendered without stretching or cropping on cover, feature plates, 2-column gallery, and before/after slots.
- [x] **Data Integrity**: `NOT_ANALYZED` is explicitly separated in `DATA_VISUALIZATION` and never counted as `PASS` (`3 / 5` PASS in 5-image sample). Chart omitted cleanly when no quality signals exist.
- [x] **Pagination & Tables**: 35-row table splits cleanly across pages and repeats its styled header row on every continuation page (`headerCount >= 2` asserted). No blank pages, no overlapping text, no detached captions.
- [x] **Immutability**: `storedFinalizedPdfBytesAreStreamedWithoutRerendering` confirms existing finalized/delivered PDFs return exact stored bytes without re-rendering.

## 4. Unverified / Out-of-Scope Boundaries

- Real Cognito authentication, live AWS S3 storage reads/writes, real VNPAY payment/delivery transitions, and live production database migration were **not** exercised in this pass.
- Real customer aerial drone photographs were not used; visual QA used explicitly labeled synthetic QA fixture plates.
