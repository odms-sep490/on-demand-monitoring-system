# Architecture overview

This workspace contains two independent Git repositories: the React web client (`ondemand-monitoring-web`) and the Spring Boot service (`on-demand-monitoring-system`). They communicate through the backend's `/api/**` HTTP endpoints. The frontend centralizes normal requests in `src/shared/api/httpClient.ts`; some specialized operator flows use their dedicated API clients.

The main application backend is `ondemandmonitoring/`, with domain packages for auth, orders, missions, checklists, devices, finance, media, delivery, weather, and support. Its `pom.xml` declares Spring Boot web, Spring Data JPA, MyBatis, PostgreSQL, and AWS SDK dependencies. Persistence and deployment settings come from runtime configuration; secrets and local `.env` files are not part of this documentation or the local graph.

## Supporting code in the backend repository

- `drone/` contains a Python telemetry adapter. Its README describes PX4 SITL telemetry being sent over HTTP to the backend's device telemetry API; image upload also passes through the backend before S3.
- `Forest3D/` contains terrain and Gazebo world generation/simulation assets. It is a development/simulation toolset, not a backend package.
- `lambda/cognito-pre-signup-java/` is a separate Java Lambda project associated with Cognito signup.
- `Python/`, `tools/`, and `scripts/` contain supporting utilities and run/packaging scripts. They are not part of the Spring Boot runtime unless a particular script launches them.

## Code intelligence and data handling

CodeGraph A indexes the FE and BE checkouts separately into ignored, local `.codegraph/` directories. It honors `.gitignore` and built-in exclusions for dependencies/build output. CodeGraph B is configured as graph-only while this machine has limited free RAM; semantic embeddings are not currently available. Neither graph index is committed.

GitDiagram is not used to ingest these repositories. The hosted MCP endpoint (`https://gitdiagram.com/mcp`) was verified and is read-only for already stored public diagrams; it cannot generate a private-repository diagram through that interface. No private repository name or source was sent. `architecture.mmd` is maintained from the checked-out source and local project documentation instead.
