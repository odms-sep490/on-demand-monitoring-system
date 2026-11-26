# PDF rendering

Existing Apache PDFBox 3.0.8. A4 cover, Vietnamese Noto Sans embedded, Mission/service/version/approval metadata, headings, scope/method/summary, selected section images, captions and media IDs, limitations/recommendations/conclusion and page numbering. Private storage bytes are decoded server-side with limits. Optimized rendering copies preserve aspect ratio; original assets remain unchanged. Image/caption reserve is kept together. The final PDF is stored under a report/version-specific private key; customer download streams the saved bytes rather than re-rendering a changing document.

Render QA PDFs to PNG via PDFBox PDFRenderer, extract text with PDFTextStripper, assert image XObjects. QA outputs in `.ai-runtime/qa-artifacts/inspection-report`. Fixture images are labeled QA, not claimed as real customer drone photos.

Basic heading/bold/italic formatting and bounded editor tables are rendered from validated JSON. Tables support up to six columns/fifty rows and no merged cells. JPEG copies at quality 0.9 bound photographic PDF size. A 50 MiB artifact limit rejects oversize output instead of returning a truncated PDF.
