# Visual and PDF QA

Actual browser captures at 1440×1000, 1920×1080, 1024×900 and 390×844. Desktop, laptop and mobile captures were visually inspected, in addition to automated overflow/page-error checks. Evidence library, document editor, table, captions, quality labels, review controls and customer read-only actions are visible. The final capture pass waits for source images before screenshots.

Corrections made during review: false initial unsaved state, wrapping quality tags on narrow cards, Vietnamese image-role labels, sticky command/review controls on wide screens, bounded gallery scrolling, mobile stacking, missing-capture-date fallback, reduced-motion behavior and modal scroll containment. Tables retain horizontal scrolling inside their editor wrapper when necessary.

The actual finalized PDF was extracted and rendered with PDFBox. All pages were inspected: A4 cover and approval metadata; Vietnamese text and headings; an edited table; actual embedded image XObjects; captions/capture timestamps; limitations/recommendations/conclusion; page breaks and page numbering. No overlapping text or stretched source photographs was observed in the QA export. Image copies are optimized for the PDF; original private objects are unchanged.

Runtime fixture photographs are artificial color panels labeled ISOLATED QA IMAGE. They validate actual object reads/image embedding/layout, not inspection accuracy or real-world defect detection. Live S3/Cognito/customer production visual QA remains outside this isolated run.
