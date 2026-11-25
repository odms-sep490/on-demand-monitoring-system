# Skills and dependencies

Loaded installed frontend, frontend-design, ui-ux-pro-max, web-design-guidelines, webapp-testing and PDF guidance. Existing skills were reused. Installed official Tiptap skill from `ueberdosis/tiptap/.agents/skills/tiptap` under the user's Codex skills directory without overwriting existing skills; read its setup reference. Skill is available next turn as well.

New frontend dependencies: @tiptap/react, @tiptap/starter-kit, @tiptap/core, @tiptap/pm, @tiptap/extension-table, version 3.35.0 (MIT, React 19 supported). Hook API is used because each section owns a single editor. No Tiptap Cloud/pro dependencies. PDFBox already existed; no OpenHTMLtoPDF dependency added because its PDFBox 2 renderer would duplicate the existing PDFBox 3 stack.

References reviewed: https://github.com/ueberdosis/tiptap ; https://github.com/danfickle/openhtmltopdf ; https://github.com/apache/pdfbox ; https://docs.ollama.com/capabilities/structured-outputs ; https://github.com/likyoo/open-cd ; https://github.com/JohannesBuchner/imagehash ; https://github.com/microsoft/playwright . Existing perceptual hash signals avoid adding ImageHash again. Noto Sans regular/bold/italic fonts bundled with OFL license from https://github.com/notofonts/noto-fonts . No third-party repository was copied into application source.
