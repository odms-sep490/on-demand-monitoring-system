# Smart Workforce & Drone Lifecycle Implementation Plan

Goal: Integrate audited usage, maintenance and workload policies with existing assignment and portals.
Architecture: Pure interval/rule engines, additive PostgreSQL records and existing domain services. No automatic flight-control commands or fabricated metrics.
Stack: Java 21, Spring Boot 4.1.1, PostgreSQL/PostGIS, React19/Vite8/Ant Design.
Spec: user attachment 11c4b3e8-b729-4040-bb54-08164072d696/Pasted text.txt.

Global constraints: preserve existing Order/Mission/acceptance/permit workflows; no destructive migration; actual vs estimated separate; user changes untouched; no automatic commit/deploy.
Review focus: duplicate and out-of-order telemetry; assignments crossing local midnight/week; policy version races; maintenance resolution without verified inspection; missing data never implies healthy.

- [ ] Pure flight interval engine tests: duplicates, gaps, multiple sorties, motor/airborne corroboration, assignment clipping, distance quality.
- [ ] Work interval union and timezone bucket tests; projected work/rest policies and certificates.
- [ ] Additive migration and typed contracts; policy version/preview/activation/audit.
- [ ] Real usage collection, idempotent aggregation and manual correction audit.
- [ ] Automated tickets and checkpoints; block allocation/replacement/reschedule through shared reservation boundary.
- [ ] Admin, Manager and Staff API/UI; reuse existing maintenance/catalog pages rather than duplicate.
- [ ] Grounded recommendation scoring, telemetry anomaly baseline and schedule-based forecast; AI Ops traceability.
- [ ] Isolated PostgreSQL, method security, frontend and browser checks; final status matrix and limitations.
