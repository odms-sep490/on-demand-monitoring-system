# Module dependencies

This is a source-layout and API-boundary map, not a claim that CodeGraph has resolved every runtime or framework-generated edge.

| Area | Frontend location | Backend location / interface | Impact checks |
| --- | --- | --- | --- |
| Authentication and identity | `src/features/auth/`, `src/features/user/` | `auth/`, `user/`, `/api/auth/**`, `/api/users/**`; Cognito adapter code is in `auth/infrastructure/cognito/` | Review auth API DTOs, permissions, token/session handling, and FE route guards. |
| Orders and planning | `src/features/customer/`, `src/features/manager/` | `order/`, `mission/`, `/api/orders/**`, `/api/manager/orders/**` | Check quote/order DTOs, execution-plan revisions, mission creation, and manager/customer consumers. |
| Mission execution and checklists | `src/features/mission/`, `src/features/drone-operator/`, `src/features/monitoring/` | `mission/`, `checklist/`, `devicecheck/`, `/api/missions/**`, `/api/operator/missions/**` | Check controller/service authorization, checklist state transitions, device assignments, operator API clients, and tests. |
| Devices and maintenance | `src/features/device/`, `src/features/manager/`, `src/features/drone-operator/` | `device/`, `devicecheck/`, `/api/devices/**`, `/api/drones/**`, `/api/maintenance-tickets/**` | Check device status, postflight checks, telemetry DTOs, ticket transitions, and simulator telemetry callers. |
| Media and delivery | `src/features/media/`, `src/features/delivery/`, operator/manager screens | `media/`, `delivery/`, `/api/**` media routes, `/api/manager/missions/{missionId}/media-approvals/**`, `/api/orders/{orderId}/delivery/**` | Check upload signing/storage, media review, evidence links, customer delivery, and order completion rules. |
| Finance and notifications | `src/features/finance/`, customer/manager order views | `finance/`, `/api/payments/**`, customer finance endpoints, finance websocket listeners | Check invoice/payment states, webhook persistence, expiry processing, response mapping, and notification consumers. |
| Support and operations | `src/features/support/`, `src/features/system-operator/` | `support/`, `dashboard/`, `weather/`, `flightarea/`, `/api/support-tickets/**`, `/api/manager/dashboard/**` | Check ticket APIs, operational dashboards, environmental assessment endpoints, and role-based access. |
| Drone/simulation tools | Some operator views consume status/telemetry APIs; no direct FE coupling is assumed here | `drone/` posts telemetry over HTTP; `Forest3D/` builds simulation worlds; `lambda/cognito-pre-signup-java/` is separate | Confirm the specific launcher/configuration and HTTP endpoint in source before changing a supporting tool. |

## Graph usage

- `codegraph_local`: default for local structure, symbol search, call paths, and impact navigation.
- `codegraph_semantic`: supplementary structural graph. It is configured with `--graph-only` while memory is constrained, so do not expect semantic embedding search until the setting is deliberately changed and memory is available.
- For cross-repository questions, run a query against each indexed root or inspect the API contract in both repositories. The graph indexes are independent.
