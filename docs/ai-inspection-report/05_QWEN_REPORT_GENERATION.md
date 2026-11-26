# Qwen generation and guardrails

New router in existing description_agent.py; no second LLM deployment. Uses configured ORDER_AGENT_MODEL and DESCRIPTION_LLM_URL with JSON Schema. Per-request schema constrains mediaId and findingId to actual authorized enums. Inputs include current Mission metadata, selected image metadata, and only inspector-assessed source observations with linked eligible media. No storage URLs/credentials or arbitrary tools are provided.

Pydantic validates JSON and bounds; max two attempts. Every image/finding reference is checked again. Unknown IDs, finding/image mismatch, unsafe URLs/HTML, invented numbers/dates and unsupported structural assertion terms are rejected. Inspection finding paragraphs must quote reviewed source text exactly. No findings -> factual metadata/evidence report with explicit limitations. AI_DRAFT always requires human review; schema/reference checks are not a proof of semantic truth. Generic prose still needs the approver's substantive review.

Rewrite proposals preserve source restrictions and are displayed for explicit Apply; they never automatically save/overwrite human edits. Regeneration of an existing document is blocked: create a new report instead. Worker errors remain FAILED and can be retried. No fake percentage progress.

The new report route uses Ollama's native `/api/chat` schema format on the same configured local runtime; the existing Qwen-Agent conversation logic is unchanged. Two inference attempts, each at most 120 seconds; backend deadline 270 seconds. Capture timestamps are normalized across numeric/ISO serializers. Image filename/time/pixel dimensions are authorized metadata, not fabricated measurements.

Methodology, limitations and a neutral conclusion use constrained factual wording. Reviewed finding paragraphs quote the source exactly; free summary/caption prose is still validated and requires human review. Unsupported compliance/completion claims and unresolved template placeholders are rejected. These rules reduce hallucination risk, not prove arbitrary natural-language truth. No vision interpretation, calibrated measurement, signature or certification is invented.
