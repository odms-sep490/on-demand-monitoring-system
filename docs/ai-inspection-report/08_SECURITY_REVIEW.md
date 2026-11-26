# Security review

Backend authority: assigned inspector can edit via existing canSubmitMissionResult policy; Manager/Admin can manage/approve; other staff can only access permitted Missions. Customer sees DELIVERED reports only for own Order and each included image must pass existing original-media publication, payment, retention and ownership gates. Stored image IDs are Mission-checked on every retrieval/finalization.

No arbitrary URL/path input, no storage credentials in model/browser, bounded image bytes/pixels, strict JPEG/PNG decoding, 200-media/12-autoselection limits, queue max 4, one job per scheduler invocation, bounded model retries/timeouts. Source metadata is untrusted text. Rich editor uses structured allowlisted nodes/marks; no raw HTML rendering. Expected revision prevents lost updates. Finalization never overwrites approved content. Significant actions have append-only version/audit snapshots. Report source guard integrates orphan cleanup protection.

Automatic guardrails do not replace human inspection. No fabricated signature, professional seal, confidence or safety certification.
