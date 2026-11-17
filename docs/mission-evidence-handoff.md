# Mission inspection and corrections

## Main flow

1. Pilot finishes the flight; the assigned Staff complete equipment postflight checks for every drone.
2. Inspector opens the inspection workspace immediately after postflight. There is no initial crew dossier submission prerequisite.
3. Inspector assesses the checklist and selects accepted media for Customer delivery. An account assigned both Pilot and Inspector still performs the inspection explicitly.
4. Complete inspection only when all checklist findings and Customer evidence requirements are satisfied. Technical validation and scope integrity remain mandatory.
5. Inspector confirms and publishes directly to the Customer through the existing result workflow. Manager does not approve inspection results.

## Unhappy cases

- Non-compliant checklist findings are grouped into a correction request.
- `DOSSIER_ADJUSTMENT` uses existing Mission media or original files from the completed flight.
- `SUPPLEMENTAL_FLIGHT` uses the main planning workspace, resource assignments, finalization, Staff acceptance, permits and execution.
- If original files are insufficient, Staff may escalate the existing request to a supplemental flight with a written reason.
- An OPEN correction blocks inspection approval. Staff resubmit corrected evidence; Inspector reassesses and resolves or returns the request. Resubmission snapshots and event history are retained.
- Resolving a correction does not itself publish results. Delegation from an accepted supplemental Mission preserves original checklist rows and flight times.

## State boundaries

- `actualEndAt` records the postflight boundary and unlocks the assigned Inspector's workspace and initial assessment.
- `completedAt` is set after checklist assessment and Customer evidence selection, rather than as a prerequisite for them.
- Mission remains IN_PROGRESS until Inspector confirmation/publication completes the result workflow.
- Correction resubmission locks reporter edits until returned. Initial assessment does not require `mission_evidence_submissions` records; existing snapshot history remains valid.
- Existing optional snapshot APIs remain compatible but are not a prerequisite or a visible step in the main workflow.
- Customer rework, per-Mission delivery/payment and contract workflows retain their own boundaries.

## Verification

Use an external Maven output directory. Do not restart a running application configured with `ddl-auto=create` or modify its database/S3 during verification. Existing Missions do not require a new Order or an invented submission record to regain initial Inspector assessment.


## Returning accepted supplemental results

Approving an internal supplemental result atomically resolves its linked correction. The current child must have Inspector approval, the exact pending checklist scope and eligible selected evidence. Accepted original checklist decisions are retained. When the source package is ready, it is completed and published with the supplemental evidence through the existing delivery workflow. The source Inspector does not reassess the accepted child result.

Original failed assessments and submission snapshots retain the history. A dossier resubmission returns to normal checklist inspection. Accepted assessments and complete Customer media selections resolve the request. Failed assessments with reasons return the remaining work to Staff. Escalation to a new flight includes only unresolved checklist work.

`POST /api/missions/{sourceMissionId}/inspection-corrections/{id}/synchronize-supplement` lets the original Inspector recover already-approved child results created before this bridge existed. It performs the same validation and is idempotent; no flight, Order or Mission must be recreated. GET remains read-only. A `SUPPLEMENT_ACCEPTED` event and immutable snapshot record the transition.
