# AGENTS.md

Repository: `on-demand-monitoring-system` — Spring Boot/Java backend plus supporting drone, simulator, Lambda, and Python tooling. The main Spring application is under `ondemandmonitoring/`.

## CodeGraph First Policy

Apply this policy automatically to coding tasks; do not wait for the user to mention CodeGraph.

- Before editing, identify the relevant features, modules, files, and symbols. For non-trivial changes, use the `codegraph_local` MCP server to inspect definitions, callers, callees, dependencies, call chains, and impact where relevant.
- The frontend and backend are separate Git checkouts with separate local indexes. Set `projectPath` to the checkout containing the code being analyzed; for cross-repository work, inspect both sides and confirm the API contract in source.
- Use `codegraph_semantic` as a supplementary structural graph only. It is configured graph-only, so semantic embeddings/search are unavailable.
- Verify graph findings against source and tests. Follow existing patterns, reuse helpers, avoid duplicate logic, and inspect API consumers/contracts before changing an API.
- After implementation, recheck affected references and run relevant verification. Skip graph queries for small, isolated edits. If CodeGraph is unavailable, continue with source analysis and report that limitation. Never claim a graph query was run unless its tool was actually executed.
- If the local index reports stale state after material changes, run `codegraph sync` from this checkout root. Graph data under `.codegraph/` is local and ignored.

## Backend Rules

For Java application changes under `ondemandmonitoring/`:

- Trace Controller → Service → Repository → database where applicable.
- Inspect DTOs, validation, API contracts, dependency injection, and related business rules.
- Identify affected frontend/API consumers and tests; the separate frontend graph may not contain complete cross-repository edges.

## Verification

- Main backend Maven project: `ondemandmonitoring/pom.xml`; run the relevant Maven verification for changes in that module.
- Keep existing developer changes intact; do not include them in unrelated commits.

## Java Coding Intelligence Policy

Before non-trivial Java changes:

1. Inspect the related classes, methods, callers, and persistence/API boundaries.
2. Use `codegraph_local` first for structural navigation before non-trivial Java changes; use Serena to supplement symbol/reference lookups, and confirm important findings in source and tests.
3. Use Context7 when external Java, Spring, or library documentation is needed, and prefer documentation matching the dependency version in `ondemandmonitoring/pom.xml`.
4. Search for existing implementations before adding new logic; identify edge cases and regressions while preserving architecture and business rules.

## Java Bug Detection Policy

When debugging Java:

1. Reproduce or characterize the failure and trace its root cause.
2. Use targeted SpotBugs/PMD reports when relevant; validate each warning against the code before changing it.
3. Make the smallest correct change, add or update a regression test when appropriate, and rerun relevant checks.
4. Do not treat every static-analysis warning as a confirmed defect or apply bulk fixes automatically.

## Java Refactoring Policy

- Inspect callers and dependencies before refactoring.
- Use OpenRewrite `rewrite:dryRun` with an explicitly selected, suitable recipe before considering an automated refactor; review `target/rewrite/rewrite.patch`.
- Do not run `rewrite:run`, broad style recipes, dependency upgrades, or Java/framework migrations without an explicit request.
- Avoid unrelated formatting changes and run relevant tests after code changes.

## Java Tool Usage Policy

- Do not run every tool for trivial changes; prefer targeted scans.
- Available Maven reports: `cd ondemandmonitoring && mvn spotbugs:spotbugs pmd:pmd pmd:cpd` (reports are under `target/`). These goals are not bound to the regular build and are configured not to fail the build on findings.
- OpenRewrite is opt-in. Example dry-run: `cd ondemandmonitoring && mvn org.openrewrite.maven:rewrite-maven-plugin:6.46.1:dryRun '-Dmaven.test.skip=true' '-Drewrite.activeRecipes=org.openrewrite.java.RemoveUnusedImports' '-DreportOutputDirectory=target/rewrite'`; `maven.test.skip` avoids compiling unrelated stale test fixtures during this dry-run. This writes a reviewable patch and does not apply it.
- Never claim a check passed unless it was executed. If a tool fails, report the reason and use an appropriate fallback.
- Never modify unrelated application behavior.
