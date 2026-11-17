# Automatic remediation Mission draft verification — 2026-11-18

## Scope

Backend and frontend verification of automatically prepared internal supplemental-flight Missions and customer rework Missions, their shared planning lifecycle, and surrounding inspection/delivery gates. This is a targeted feature audit, not a complete repository certification.

## Confirmed defects fixed

1. **Initial Mission creation did not reject invalid Order states.** `MissionService.createMissionInternal` now requires APPROVED for initial planning. Additional Missions retain their existing APPROVED/IN_PROGRESS rule. Parameterized service and HTTP tests cover rejected, cancelled and completed Orders.
2. **Manual initial/additional Mission creation did not lock its Order before changing planning scope.** The shared creation path now uses `findByIdForUpdate`. Concurrent requests serialize against the same Order, preventing competing plan revision updates. An actual PostgreSQL test verifies two independent scopes both commit with two plan revision increments.

The previous 11 failures in `MissionCreationServiceTest` were not exclusively stale fixtures. The missing state guard/Order lock were real defects. The remaining fixture mismatches involved required checklist selections, mocked revision updates and the retired Manager approval endpoint. These have been corrected. Existing HTTP test configuration was also missing dependencies added to the application; cancellation tests expected physical deletion instead of the current audited cancellation contract.

## Verification results

- Backend: **264 tests, 0 failures, 0 errors, 0 skipped** in the final combined run.
- This includes **20 actual PostgreSQL tests** in `InspectionCorrectionPostgresTest` using an isolated disposable PostgreSQL 17 database and random schema.
- Frontend: **104 tests in 15 files**, all passed.
- `git diff --check` passed.
- CodeGraph was used on both repositories to trace planning, correction, rework, authorization, checklist assignment and delivery relationships; the backend index was synchronized after changes.

## Important scenarios covered

- Automatic unscheduled drafts and required schedule/resources at remediation plan finalization.
- Repeated and concurrent draft creation/restoration without duplicate effective Missions.
- Failed/cancelled attempt history survives replacement; ordinary page loading does not replace terminal attempts.
- Customer rework scopes from different source Missions in one Order append to the same current planning draft, without duplicating immutable snapshots or changing original assignments.
- Snapshot/assignment failure rolls back draft creation, item pointers, assignments and plan revision together.
- Two simultaneous initial Mission creation requests preserve both independent scopes and both plan revision increments.
- Pilot/Inspector authorization, narrowly scoped internal draft initialization, and denial of unrelated Staff/customer/anonymous operations.
- Dossier adjustment and reinspection, failed-flight replacement, accepted supplemental results, source Mission completion and customer delivery evidence policies.
- Deposit activation, normal planning, cancellation audit history and HTTP request validation/security.

## Reproduction

Use a disposable PostgreSQL instance with PostGIS/vector extensions and a database named `odms_contract_verification`. Set the opt-in variables `ODMS_CONTRACT_TEST_JDBC_URL`, `ODMS_CONTRACT_TEST_DB_USER`, `ODMS_CONTRACT_TEST_DB_PASSWORD`. Never point the create-schema test fixture at the development database.

The Maven test selection used was:

```text
Inspection*Test,InternalInspectionMissionServiceTest,MissionRework*Test,ReworkRecoveryTest,ReworkDeliveryEvidencePolicyTest,CurrentDeliveryEvidenceTest,DeliveryWorkflowServiceTest,ResultRevision*Test,MissionCompletionInspectionGateTest,SupplementAutomaticDeliveryTest,InspectorPublicationTest,InspectorResultOwnershipTest,MissionPlanningServiceTest,MissionPlanningScheduleTest,MissionCreation*Test,AdditionalMissionCreationTest,MissionChecklistAssignmentServiceTest,MissionAuthorizationServiceTest,EvidenceHandoffTest,MissionExecutionActivationServiceTest
```

The Maven build/output directories were outside the running application's checkout target directory. No live application restart or development schema recreation was performed. The disposable test container was removed after verification.

The frontend Vitest selection covered `src/features/manager/pages/MissionCreate/tests`, internal correction setup, `src/features/mission/inspection`, `src/features/delivery`, and planning workspace projections.

## Limits

No further confirmed defects were found within these tested paths. Passing tests do not establish that no hidden defects exist. This run did not execute the entire repository suite, a full browser end-to-end journey against the live backend, real drone flight, or real S3/payment/notification integrations. Those deployment integration paths still require staging verification before release.
