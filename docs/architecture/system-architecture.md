# System architecture

The diagram reflects the checked-out source layout and documented runtime interfaces. It distinguishes deployed application pieces from local development/simulation tools; dashed lines mark supporting workflows rather than a guaranteed always-on production connection.

## Runtime boundary

1. The browser runs the React 19/Vite/TypeScript client. Feature areas include customer, manager, finance, mission, media, staff, and drone operator workflows.
2. The client calls Spring Boot `/api/**` endpoints using the shared HTTP client and feature-specific API modules. The backend exposes separate controllers for auth, orders, missions, finance, devices, media, delivery, checklists, and support.
3. Backend controllers delegate into domain services and repositories under `ondemandmonitoring/src/main/java/com/ondemandmonitoring/`. The application uses Spring Data JPA and MyBatis with PostgreSQL, according to the Maven configuration.
4. The backend includes a Cognito identity adapter, S3 media storage, and an SQS media-event consumer that only starts when `app.media.sqs.enabled=true`. It also has an authenticated STOMP WebSocket endpoint; the customer order page subscribes to order events. Exact credentials/endpoints are supplied at runtime and intentionally omitted here.

## Development and simulation boundary

The Python drone controller README documents HTTP telemetry posts to `/api/devices/{deviceId}/telemetry`; the backend README also documents simulator workflows that can launch the UI, service, and simulator together. Forest3D creates Gazebo assets/worlds used by those workflows. The Cognito pre-signup Lambda is a separately packaged project. These supporting tools are not folded into the Spring Boot module graph.

See [module-dependencies.md](module-dependencies.md) for code boundaries and likely change-impact starting points.
