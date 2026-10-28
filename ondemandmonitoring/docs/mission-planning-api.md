# Mission planning API

All commands below require `MANAGER` or `ADMIN`.
`DRAFT` and `FINALIZED` refer to `Order.executionPlanStatus`, not `Order.orderStatus`.

## Finalize an Order plan

`POST /api/orders/{orderId}/execution-plan/finalize`

```json
{ "expectedPlanRevision": 3 }
```

`decompositionConfirmed` is still accepted for compatibility but is optional and ignored.
The Order must be `APPROVED` or `IN_PROGRESS`, its plan must be `DRAFT`, and the revision must match.
There must be at least one effective Mission. Every effective Mission must be `PLANNING`, own
at least one checklist item, have current unreleased staff covering `PILOT`, `OPERATOR`,
`MAINTAINER`, `INSPECTOR`, and have at least one current unreleased `MAIN` device.
Rejected staff assignments do not count. One staff member may cover several roles.
Every Order checklist item must be allocated exactly once across effective Missions.
Cancelled Missions do not count towards coverage.

During planning, staff assignments have `PLANNING` response status and `isCurrent=false`;
device assignments have `PLANNING` checkup status and `isCurrent=false`. Finalization does not
validate deposit, dates, or geometry. It creates checklist executions for runtime, changes the
Order plan to `FINALIZED`, increments its revision, records finalization audit, changes Missions
to `READY`, changes `resourcePrepareStatus` to `PENDING`, and changes unreleased assignments to
`PENDING` with `isCurrent=true` in one transaction.
A second finalize call is rejected because the plan is no longer `DRAFT`.

## Create a Mission after Order finalization

`POST /api/missions/{orderId}/additional` returns HTTP 201.

```json
{
  "scopeLabel": "Additional flight",
  "scheduledStartAt": "2026-11-01T01:00:00Z",
  "scheduledEndAt": "2026-11-01T02:00:00Z",
  "checklistIds": ["order-checklist-item-id"]
}
```

The Order plan must be `FINALIZED` and the Order must be `APPROVED` or `IN_PROGRESS`.
The existing creation schedule and payment checks still apply. The new Mission starts in
`PLANNING` without reopening the Order plan. Checklist allocation increments the plan revision.
Checklist IDs must belong to the Order and must not be owned by another effective Mission.
For example, checklist items released by a cancelled Mission can be selected.

The original `POST /api/missions/{orderId}` remains the creation endpoint for draft plans.

## Edit and confirm an additional Mission

`PUT /api/missions/{missionId}` accepts the existing schedule/checklist payload while the Mission
is `PLANNING`, including when the Order plan is `FINALIZED`.
`PUT /api/missions/{missionId}/checklist-assignments` also permits selection on an unconfirmed
Mission under a finalized plan, using the current `expectedPlanRevision`.
Materialized/confirmed Missions cannot rewrite their planning checklist selection.

Assign staff/device using the existing assignment endpoints, then call:

`POST /api/missions/{missionId}/confirm-planning` (no request body).

The Order plan must remain `FINALIZED`. Confirmation validates the Mission's `PLANNING` status,
the same resource requirements as Order finalization, nonempty checklist selection, and complete
exclusive Order checklist coverage. It creates checklist executions, changes only this Mission
to `READY`, changes its `resourcePrepareStatus` to `PENDING`, activates its assignments with
`isCurrent=true`, and increments the plan revision. Existing runtime Missions keep their states.

Staff assignment status is `PLANNING`, `PENDING`, `ACCEPTED`, or `REJECTED`. Device assignment
checkup status is `PLANNING`, `PENDING`, `CHECKING`, `PASSED`, or `FAILED`. When a confirmed
Mission's current staff assignments are all `ACCEPTED` and its current device assignments are all
`PASSED`, `resourcePrepareStatus` becomes `READY`; otherwise it remains `PENDING`.

Removing an assignment while the Mission is `PLANNING` deletes that assignment row. Removing a
confirmed assignment keeps its history, sets `isCurrent=false`, and releases its resource
reservation. `WAITING_CREW_CONFIRMATION` has been removed from `MissionStatus`.

## Delete a draft Mission

`DELETE /api/missions/{missionId}` returns HTTP 204.

The Order plan must be `DRAFT`, the Mission must be `PLANNING`, and execution must not have
started or been materialized. This physically deletes planning checklist assignments (including
removed rows), staff/device assignments, reservation rows, and Mission calendar entries before
deleting the Mission. Unrelated calendar entries are retained. Released checklist items become
`UNASSIGNED` unless another effective Mission owns them. The Order plan revision increments.

## Cancel a Mission

`POST /api/missions/{missionId}/cancel`

```json
{ "expectedPlanRevision": 4, "reason": "Cancelled by manager" }
```

Cancellation validates Order status, a finalized plan, the revision, an uncancelled Mission,
and the absence of an approved MissionResult. It changes the Mission to `CANCELLED` and records
audit. It does not consult or invoke safe-stop, inspect unsafe resources, or perform operational
resource cleanup. The separate safe-stop classes and endpoints remain available.

## Removed endpoint

`POST /api/orders/{orderId}/execution-plan/amend` is no longer exposed (HTTP 404).
Use additional Mission creation and individual confirmation without reopening the Order plan.
The Mission replacement endpoints and service methods have also been removed.

## Row locking

Mission planning commands use ordinary repository reads instead of pessimistic `FOR UPDATE` reads.
Assignment, schedule validation, and reservation release do not automatically acquire resource mutex rows.
Transactions, revision checks, and database constraints remain in place.
Explicit locking helpers used by separate modules such as safe-stop remain available.
