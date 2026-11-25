# PDF Skills & Renderer Decision

## 1. Skills Research & Compatibility Assessment (Windows + Gemini)

We inspected the actual `README.md` / `SKILL.md` of all five requested repositories before installing anything:

| Repository | Inspected Source | Findings | Compatibility & Decision |
|---|---|---|---|
| `janus-tiu/pdf-design-skills` | `README.md` (fetched) | Claude `.skill` release package with 5 named visual styles, clean SVG charts, cover + sample page draft workflow. | Not natively packaged for Windows Gemini runtime; **not installed**. Adopted its design-system vocabulary and multi-concept visual comparison workflow. |
| `syx-labs/pdf-forge` | `README.md` (fetched) | Uses `bun install` + POSIX `./install.sh`, Tailwind CSS via CDN, and Playwright Chromium rendering. | POSIX shell installer + CDN Tailwind + browser HTML execution conflicts with Windows native workflow and our offline/no-arbitrary-HTML security boundary; **not installed**. |
| `openai/skills/.curated/pdf` | `SKILL.md` (fetched) | Visual-first QA loop: render every PDF page to PNG, visually inspect layout/typography/alignment, extract text for searchability, iterate until clean. | **Primary QA methodology adopted**, implemented natively via our bundled PDFBox `PDFRenderer` + `PDFTextStripper` (no Poppler binary required on Windows). |
| `hubayirp/agentic-science/skills/pdf-report-generation` | `SKILL.md` (fetched) | Python ReportLab Platypus turning a strict JSON spec into styled reports + `validate_output.py` structural QC. | US Letter + Helvetica lacks Vietnamese glyphs and adds a Python hop; **not installed**. Adopted its strict JSON-spec-to-layout separation and automated structural QC pattern. |
| `anis-dr/skills` (`technical-pdf`) | Raw GitHub `main` returned HTTP 404 | File not publicly reachable on `main`. | **Not installed**; documented transparently. |

## 2. Renderer Evaluation (Java 21 + Spring Boot 4.1.1 + PDFBox 3.0.8)

| Criterion | Apache PDFBox 3.0.8 (**Selected**) | OpenHTMLtoPDF | Chromium / Playwright | Typst |
|---|---|---|---|---|
| **Typography & Vietnamese** | Native TrueType subset embedding (`NotoSans` Regular/Bold/Italic, OFL); 100% selectable UTF-8 Vietnamese text. | Supports TTF, but depends on PDFBox 2.x (dependency conflict with our PDFBox 3.0.8). | Full web typography, requires system/bundled font management. | Excellent typography, requires external CLI binary. |
| **Pagination & Tables** | Exact point-level control: keep-with-next headings, atomic figure+caption cards, automatic repeating table headers across pages. | CSS Paged Media has known edge cases with nested flex/tables. | Good CSS `@page`, weaker deterministic repeating-header control on complex grids. | Strong native pagination. |
| **Security** | **Highest** — pure Java drawing from validated `ReportDocument` records; zero HTML/CSS/JS parsing or SSRF surface. | HTML/CSS parsing attack surface; maintenance stalled. | High — headless browser process requires sandboxing & SSRF hardening. | External subprocess invocation + template escaping surface. |
| **Operations & Performance** | Zero new dependencies, zero external binaries, in-process millisecond rendering, ~250–600 KB output. | Conflicts with PDFBox 3.0.8 in `pom.xml`. | ~300+ MB RAM per Chromium worker, heavy container footprint. | Extra `typst.exe` / Linux binary deployment dependency. |

**Decision:** Retain **Apache PDFBox 3.0.8** and upgrade it into a modular, reusable Java design engine (`ReportDesignTheme`, `ReportRenderContext`, `PdfCanvas`, `ReportPageComponents`, `ReportPdf`) with explicit renderer version `pdfbox-design-v2`. No new Maven dependencies, external servers, or cloud services were added.

## 3. Static Analysis & CodeGraph Note

- `codegraph_local` MCP server was not available in this session's toolset; all callers, DTOs, services, controllers, and tests were verified directly in source.
