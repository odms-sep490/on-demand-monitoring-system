package com.ondemandmonitoring.mission.inspection;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.enums.*;
import com.ondemandmonitoring.mission.inspection.dto.request.InspectionCorrectionDecision;
import com.ondemandmonitoring.mission.inspection.dto.request.InspectionCorrectionRequest;
import com.ondemandmonitoring.mission.inspection.dto.response.InspectionCorrectionResponse;
import com.ondemandmonitoring.mission.inspection.enums.InspectionCorrectionStatus;
import com.ondemandmonitoring.mission.inspection.mapper.InspectionCorrectionMapper;
import com.ondemandmonitoring.mission.inspection.repository.InspectionCorrectionEventRepository;
import com.ondemandmonitoring.mission.inspection.repository.InspectionCorrectionItemRepository;
import com.ondemandmonitoring.mission.inspection.repository.InspectionCorrectionRepository;
import com.ondemandmonitoring.mission.inspection.service.IInspectionCorrectionService;
import com.ondemandmonitoring.mission.inspection.service.impl.InspectionCorrectionPolicy;
import com.ondemandmonitoring.mission.inspection.service.impl.InspectionCorrectionService;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.*;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import com.ondemandmonitoring.order.enums.*;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.order.service.IOrderExecutionCompletionPolicy;
import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import com.ondemandmonitoring.warehouse.domain.PreferredTime;
import com.ondemandmonitoring.warehouse.enums.PreferredTimeCode;
import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mapstruct.factory.Mappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

@SpringJUnitConfig(InspectionCorrectionPostgresTest.Config.class)
@EnabledIfEnvironmentVariable(
    named = "ODMS_CONTRACT_TEST_JDBC_URL",
    matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/odms_contract_verification")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InspectionCorrectionPostgresTest {
  static final String SCHEMA = "inspection_verify_" + UUID.randomUUID().toString().replace("-", "");
  static final Instant NOW = Instant.parse("2026-11-14T00:00:00Z");
  @Autowired DataSource dataSource;
  @Autowired EntityManagerFactory emf;
  @Autowired PlatformTransactionManager transactions;
  @Autowired IInspectionCorrectionService service;
  @Autowired IMissionChecklistAssignmentService assignmentService;
  @Autowired com.ondemandmonitoring.mission.inspection.service.IInternalInspectionMissionService drafts;
  @Autowired InspectionCorrectionRepository corrections;
  @Autowired InspectionCorrectionItemRepository items;
  @Autowired MissionChecklistExecutionRepository executions;
  @Autowired IMissionAuthorizationService auth;
  @Autowired IChecklistEvidenceService evidence;
  JdbcTemplate jdbc;
  TransactionTemplate tx;
  String missionId, executionId;

  @BeforeEach
  void setup() {
    jdbc = new JdbcTemplate(dataSource);
    tx = new TransactionTemplate(transactions);
    jdbc.execute(
        "TRUNCATE"
            + " mission_inspection_correction_events,mission_inspection_correction_items,mission_inspection_corrections,mission_checklist_executions,mission_checklist_assignments,missions,order_checklist_items,orders,users,roles,services,preferred_times"
            + " CASCADE");
    reset(auth, evidence);
    when(auth.canAssessMonitoringChecklist(anyString())).thenReturn(true);
    when(auth.canRequestInspectionCorrection(anyString())).thenReturn(true);
    when(auth.canAttachChecklistEvidence(anyString())).thenReturn(true);
    when(auth.canViewMission(anyString())).thenReturn(true);
    when(evidence.ready(any(), anyList(), eq(false))).thenReturn(true);
    when(evidence.readyForInspectorApproval(any(), anyList())).thenReturn(true);
    tx.executeWithoutResult(
        status -> {
          EntityManager em = SharedEntityManagerCreator.createSharedEntityManager(emf);
          Role role = new Role();
          role.setCode(RoleCode.CUSTOMER);
          role.setName("Customer");
          role.setActive(true);
          em.persist(role);
          User user = new User();
          user.setFullName("Test");
          user.setEmail("inspection@example.test");
          user.setRole(role);
          em.persist(user);
          var catalog = new com.ondemandmonitoring.service.domain.Service();
          catalog.setName("Inspection test");
          em.persist(catalog);
          var time = new PreferredTime();
          time.setCode(PreferredTimeCode.MORNING);
          time.setName("Morning");
          time.setStartTime(LocalTime.of(7, 0));
          time.setEndTime(LocalTime.of(11, 0));
          em.persist(time);
          Order order = new Order();
          order.setTitle("Inspection test");
          order.setCustomer(user);
          order.setService(catalog);
          order.setPreferredTime(time);
          order.setDataRetentionDays(30);
          order.setOrderStatus(OrderStatus.IN_PROGRESS);
          order.getExecutionPlan().setStatus(ExecutionPlanStatus.FINALIZED);
          order.getExecutionPlan().setRevision(2);
          order.setFlightCompliance(null);
          order.setFlightAreaReview(null);
          em.persist(order);
          Mission mission = new Mission();
          mission.setOrder(order);
          mission.setMissionCode("MS-INSPECTION-TEST");
          mission.setStatus(MissionStatus.IN_PROGRESS);
          mission.setScheduledStartAt(NOW.minusSeconds(3600));
          mission.setScheduledEndAt(NOW.plusSeconds(3600));
          mission.setScopeLabel("Original inspection scope");
          mission.setActualEndAt(NOW.minusSeconds(60));
          em.persist(mission);
          em.flush();
          missionId = mission.getId();
          OrderChecklistItem item = new OrderChecklistItem();
          item.setOrder(order);
          item.setSourceType(OrderChecklistSourceType.CUSTOMER_CUSTOM);
          item.setContent("Inspect fence");
          item.setReviewStatus(ChecklistReviewStatus.INCLUDED);
          em.persist(item);
          em.flush();
          MissionChecklistAssignment assignment = new MissionChecklistAssignment();
          assignment.setMissionId(missionId);
          assignment.setOrderId(order.getId());
          assignment.setOrderChecklistItemId(item.getId());
          assignment.setAssignedAt(NOW.minusSeconds(90));
          em.persist(assignment);
          em.flush();
          MissionChecklistExecution execution = new MissionChecklistExecution();
          execution.setAssessmentStatus(ChecklistAssessmentStatus.NON_COMPLIANT);
          execution.setAssessedBy("inspector");
          execution.setAssessedAt(NOW);
          execution.setMissionId(missionId);
          execution.setOrderId(order.getId());
          execution.setOrderChecklistItemId(item.getId());
          execution.setMissionChecklistAssignmentId(assignment.getId());
          em.persist(execution);
          em.flush();
          executionId = execution.getId();
        });
  }

  @Autowired com.ondemandmonitoring.mission.service.impl.MissionEvidenceSubmissionService submissions;

  @Test
  void evidenceHandoffPersistsSnapshotAndReplaysWithoutAnotherRevision() {
    jdbc.update("update missions set completed_at=? where id=?", java.sql.Timestamp.from(NOW), missionId);
    var state = submissions.get(missionId);
    assertThat(state.canSubmit()).isTrue();
    var command = new com.ondemandmonitoring.mission.service.impl.MissionEvidenceSubmissionService.Command(UUID.randomUUID(), state.reviewToken());
    var sent = submissions.submit(missionId, command);
    assertThat(sent.reviewable()).isTrue();
    assertThat(sent.canSubmit()).isFalse();
    assertThat(sent.history()).hasSize(1);
    assertThat(submissions.submit(missionId, command).history()).hasSize(1);
    assertThat(jdbc.queryForObject("select jsonb_array_length(snapshot) from mission_evidence_submissions", Integer.class)).isEqualTo(1);
    jdbc.update("update mission_checklist_executions set observation='changed later' where id=?", executionId);
    assertThat(jdbc.queryForObject("select snapshot::text from mission_evidence_submissions", String.class)).doesNotContain("changed later");
  }

  @Test
  void dossierAdjustmentNeverCreatesANewFlight() {
    var opened = service.request(missionId, request());
    assertThatThrownBy(() -> internalMissions.create(missionId, opened.id(),
        new com.ondemandmonitoring.mission.inspection.dto.request.InternalCorrectionMissionRequest(
            opened.version(), UUID.randomUUID(), "Not a flight", NOW.plusSeconds(3600), NOW.plusSeconds(7200))))
        .isInstanceOf(ApiException.class);
    assertThat(jdbc.queryForObject("select count(*) from missions", Integer.class)).isEqualTo(1);
  }

  @Test
  void supplementalRequestCannotBeResubmittedWithoutAcceptedFlight() {
    var opened = service.request(missionId, flightRequest());
    assertThatThrownBy(() -> service.resubmit(missionId, opened.id(), decision(opened))).isInstanceOf(ApiException.class);
    assertThat(service.list(missionId).getFirst().status()).isEqualTo(InspectionCorrectionStatus.OPEN);
  }

  @Test
  void missingOriginalDataCanEscalateToFlightWithoutLosingHistoryOrRequestReplay() {
    var request = request();
    var opened = service.request(missionId, request);
    var escalated = service.requireFlight(missionId, opened.id(), decision(opened));
    assertThat(escalated.mode()).isEqualTo(com.ondemandmonitoring.mission.inspection.enums.InspectionCorrectionMode.SUPPLEMENTAL_FLIGHT);
    assertThat(escalated.events()).hasSize(3);
    when(auth.canAssessMonitoringChecklist(missionId)).thenReturn(false);
    assertThat(service.request(missionId, request).id()).isEqualTo(opened.id());
    assertThatThrownBy(() -> service.requireFlight(missionId, opened.id(), decision(escalated))).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.resubmit(missionId, opened.id(), decision(escalated))).isInstanceOf(ApiException.class);
  }

  @Test
  void concurrentHandoffsProduceExactlyOneSubmission() throws Exception {
    jdbc.update("update missions set completed_at=? where id=?", java.sql.Timestamp.from(NOW), missionId);
    var token = submissions.get(missionId).reviewToken();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    var start = new CountDownLatch(1);
    try {
      List<Future<Boolean>> calls = new ArrayList<>();
      for (int i = 0; i < 2; i++) calls.add(pool.submit(() -> {
        start.await();
        try {
          submissions.submit(missionId, new com.ondemandmonitoring.mission.service.impl.MissionEvidenceSubmissionService.Command(UUID.randomUUID(), token));
          return true;
        } catch (ApiException expected) { return false; }
      }));
      start.countDown();
      int accepted = 0;
      for (var call : calls) if (call.get(30, TimeUnit.SECONDS)) accepted++;
      assertThat(accepted).isEqualTo(1);
      assertThat(jdbc.queryForObject("select count(*) from mission_evidence_submissions", Integer.class)).isEqualTo(1);
    } finally { pool.shutdownNow(); }
  }

  InspectionCorrectionRequest request() {
    return new InspectionCorrectionRequest(
        UUID.randomUUID(),
        com.ondemandmonitoring.mission.inspection.enums.InspectionCorrectionMode.DOSSIER_ADJUSTMENT,
        List.of(
            new InspectionCorrectionRequest.Item(
                executionId, 0L, "The image is blurred", List.of())));
  }

  InspectionCorrectionRequest flightRequest() {
    var request = request();
    return new InspectionCorrectionRequest(request.clientRequestId(),
        com.ondemandmonitoring.mission.inspection.enums.InspectionCorrectionMode.SUPPLEMENTAL_FLIGHT, request.items());
  }

  InspectionCorrectionDecision decision(InspectionCorrectionResponse row) {
    return new InspectionCorrectionDecision(row.version(), "Reviewed", row.reviewToken());
  }

  @Test
  void postgresRoundTripPreservesHistoryAndVersion() {
    var opened = service.request(missionId, request());
    var pending = service.resubmit(missionId, opened.id(), decision(opened));
    var resolved = service.resolve(missionId, opened.id(), decision(pending));
    assertThat(resolved.status()).isEqualTo(InspectionCorrectionStatus.RESOLVED);
    assertThat(resolved.events()).hasSize(3);
    assertThat(resolved.version()).isGreaterThan(opened.version());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from mission_inspection_correction_items", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select data_type from information_schema.columns where table_schema=? and"
                    + " table_name='mission_inspection_correction_items' and"
                    + " column_name='media_ids'",
                String.class,
                SCHEMA))
        .isEqualTo("jsonb");
  }

  @Test
  void concurrentRequestsLeaveExactlyOneActiveBatch() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    var start = new CountDownLatch(1);
    try {
      List<Future<Boolean>> calls = new ArrayList<>();
      for (int i = 0; i < 2; i++)
        calls.add(
            pool.submit(
                () -> {
                  start.await();
                  try {
                    service.request(missionId, request());
                    return true;
                  } catch (ApiException expected) {
                    return false;
                  }
                }));
      start.countDown();
      int count = 0;
      for (var call : calls) if (call.get(30, TimeUnit.SECONDS)) count++;
      assertThat(count).isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from mission_inspection_corrections where active_mission_id is"
                      + " not null",
                  Integer.class))
          .isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void databaseRejectsSecondActiveRequestEvenOutsideService() {
    var row = service.request(missionId, request());
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into"
                        + " mission_inspection_corrections(id,version,created_at,mission_id,active_mission_id,client_request_id,status,requested_by,requested_at)"
                        + " select ?,0,now(),mission_id,active_mission_id,?,'OPEN','test',now()"
                        + " from mission_inspection_corrections where id=?",
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    row.id()))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  @Test
  void rollbackDoesNotLeavePartialItemsOrAudit() {
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      service.request(missionId, request());
                      throw new IllegalStateException("forced rollback");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from mission_inspection_corrections", Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from mission_inspection_correction_events", Integer.class))
        .isZero();
  }

  @Test
  void changedEvidenceVersionInvalidatesReviewToken() {
    var opened = service.request(missionId, request());
    jdbc.update(
        "update mission_checklist_executions set version=version+1 where id=?", executionId);
    assertThatThrownBy(() -> service.resubmit(missionId, opened.id(), decision(opened)))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void acceptedSupplementClosesTheOriginalRequestWithoutCopyingExecutionRows() {
    var opened = service.request(missionId, flightRequest());
    String[] childIds = new String[2];
    tx.executeWithoutResult(
        status -> {
          EntityManager em = SharedEntityManagerCreator.createSharedEntityManager(emf);
          var correction = corrections.findById(opened.id()).orElseThrow();
          var original = items.findItems(opened.id()).getFirst().getExecution();
          Mission child = correction.getSupplementalMission();
          child.setStatus(MissionStatus.COMPLETED);
          child.setScopeLabel("Recapture fence");
          child.setScheduledStartAt(NOW.minusSeconds(1800));
          child.setScheduledEndAt(NOW.plusSeconds(1800));
          child.setActualEndAt(NOW);
          child.setCompletedAt(NOW);
          em.flush();
          var assignment = em.createQuery(
              "select a from MissionChecklistAssignment a where a.missionId=:id and a.removedAt is null",
              MissionChecklistAssignment.class).setParameter("id", child.getId()).getSingleResult();
          var replacement = new MissionChecklistExecution();
          replacement.setMissionId(child.getId());
          replacement.setOrderId(child.getOrder().getId());
          replacement.setOrderChecklistItemId(original.getOrderChecklistItemId());
          replacement.setMissionChecklistAssignmentId(assignment.getId());
          replacement.setExecutionStatus(ChecklistExecutionStatus.COMPLETED);
          replacement.setAssessmentStatus(ChecklistAssessmentStatus.COMPLIANT);
          replacement.setStartedAt(NOW.minusSeconds(30));
          replacement.setCompletedAt(NOW);
          em.persist(replacement);
          em.flush();
          var result = new MissionResult();
          result.setMission(child);
          result.setStatus(MissionResultStatus.COMPLETED);
          result.setApprovalStatus(MissionResultApprovalStatus.APPROVED);
          result.setInspectorConfirmed(true);
          result.setCompletedAt(NOW);
          result.setMediaCount(1);
          em.persist(result);
          em.flush();
          childIds[0] = child.getId();
          childIds[1] = replacement.getId();
        });
    var current = service.list(missionId).getFirst();
    var submitted = service.resubmit(missionId, current.id(), decision(current));
    var resolved = service.resolve(missionId, current.id(), decision(submitted));
    assertThat(resolved.status()).isEqualTo(InspectionCorrectionStatus.RESOLVED);
    assertThat(
            jdbc.queryForObject(
                "select execution_status from mission_checklist_executions where id=?",
                String.class,
                executionId))
        .isEqualTo("PENDING");
    var guard =
        new com.ondemandmonitoring.mission.inspection.service.impl
            .InspectionCorrectionMutationGuard(corrections, items);
    assertThatThrownBy(() -> guard.requireEditable(missionId, executionId))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> guard.requireEditable(childIds[0], childIds[1]))
        .isInstanceOf(ApiException.class);
    assertThat(resolved.events())
        .extracting(
            com.ondemandmonitoring.mission.inspection.dto.response.InspectionCorrectionResponse
                    .Event
                ::action)
        .containsExactly("REQUESTED", "INTERNAL_MISSION_CREATED", "RESUBMITTED", "RESOLVED");
  }

  @Test
  void cannotCreateACorrectionForACompliantChecklistInTheDatabase() {
    jdbc.update(
        "update mission_checklist_executions set assessment_status='COMPLIANT' where id=?",
        executionId);
    assertThatThrownBy(() -> service.request(missionId, request()))
        .isInstanceOf(ApiException.class)
        .extracting("errorCode")
        .isEqualTo(com.ondemandmonitoring.common.exception.ErrorCode.CHECKLIST_NOT_READY);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from mission_inspection_corrections", Integer.class))
        .isZero();
  }

  @Autowired
  com.ondemandmonitoring.mission.inspection.service.IInternalInspectionMissionService
      internalMissions;

  @Test
  void createsInternalMissionAndAssignsFailedScopeInOneTransaction() {
    var correction = service.request(missionId, flightRequest());
    assertThat(correction.items().getFirst().orderChecklistItemId())
        .isEqualTo(jdbc.queryForObject("select order_checklist_item_id from mission_checklist_executions where id=?",String.class,executionId));
    var child = internalMissions.ensureDraft(missionId, correction.id(), false);
    var repeated = internalMissions.ensureDraft(missionId, correction.id(), false);
    assertThat(repeated.getId()).isEqualTo(child.getId());
    assertThat(jdbc.queryForObject("select count(*) from missions", Integer.class)).isEqualTo(2);
    assertThat(jdbc.queryForObject("select scheduled_start_at from missions where id=?", java.sql.Timestamp.class, child.getId())).isNull();
    assertThat(child.getId()).isNotBlank().isNotEqualTo(missionId);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from mission_checklist_assignments where mission_id=? and"
                    + " removed_at is null",
                Integer.class,
                child.getId()))
        .isEqualTo(1);
    assertThat(service.list(missionId).getFirst().supplementalMissionId()).isEqualTo(child.getId());
  }

  @Test
  void schemaUpgradeFromNotNullSchedulePreservesExistingMissionsAndAllowsUnscheduledDraftsWhileBlockingFinalizationWithoutSchedule()
      throws Exception {
    jdbc.execute(
        "ALTER TABLE missions ALTER COLUMN scheduled_start_at SET NOT NULL, ALTER COLUMN"
            + " scheduled_end_at SET NOT NULL");
    new com.ondemandmonitoring.mission.config.MissionScheduleSchemaUpdater(dataSource).run();
    assertThat(
            jdbc.queryForObject(
                "select scheduled_start_at from missions where id=?",
                java.sql.Timestamp.class,
                missionId))
        .isNotNull();
    var correction = service.request(missionId, flightRequest());
    var child = internalMissions.ensureDraft(missionId, correction.id(), false);
    assertThat(
            jdbc.queryForObject(
                "select scheduled_start_at from missions where id=?",
                java.sql.Timestamp.class,
                child.getId()))
        .isNull();
    assertThatThrownBy(
            () ->
                internalMissions.finalizePlan(
                    missionId,
                    correction.id(),
                    new com.ondemandmonitoring.mission.dto.request
                        .ExecutionPlanFinalizeRequest(2L, false)))
        .isInstanceOf(ApiException.class);
  }

  @Autowired AuthenticatedUserResolver users;

  @Test
  void rollsBackNewMissionAndCorrectionLinkWhenAssignmentFails() {
    when(users.getCurrentUserId()).thenReturn("test-actor", "test-actor")
        .thenThrow(new IllegalStateException("Assignment actor unavailable"));
    try {
      assertThatThrownBy(() -> service.request(missionId, flightRequest()))
          .isInstanceOf(IllegalStateException.class).hasMessage("Assignment actor unavailable");
      assertThat(jdbc.queryForObject("select count(*) from missions", Integer.class)).isEqualTo(1);
      assertThat(jdbc.queryForObject("select count(*) from mission_checklist_assignments", Integer.class)).isEqualTo(1);
      assertThat(jdbc.queryForObject("select count(*) from mission_inspection_corrections", Integer.class)).isZero();
    } finally {
      doReturn("test-actor").when(users).getCurrentUserId();
    }
  }

  @Test
  void failedInternalMissionCanBeReplacedWithoutDeletingItsHistory() {
    var correction=service.request(missionId,flightRequest());
    var first=internalMissions.ensureDraft(missionId,correction.id(),false);
    jdbc.update("update missions set status='FAILED' where id=?",first.getId());
    var current=service.list(missionId).getFirst();
    var second=internalMissions.ensureDraft(missionId,current.id(),true);
    assertThat(second.getId()).isNotEqualTo(first.getId());
    assertThat(jdbc.queryForObject("select status from missions where id=?",String.class,first.getId())).isEqualTo("FAILED");
    assertThat(jdbc.queryForObject("select count(*) from mission_checklist_assignments where mission_id=? and removed_at is null",Integer.class,first.getId())).isEqualTo(1);
    assertThat(service.list(missionId).getFirst().supplementalMissionId()).isEqualTo(second.getId());
    assertThat(jdbc.queryForObject("select assignment_status from order_checklist_items where id=(select order_checklist_item_id from mission_checklist_executions where id=?)",String.class,executionId)).isEqualTo("IN_PROGRESS");
  }

  @Test
  void concurrentDraftRestorationCreatesOnlyOneReplacementAndRetainsTheFailedAttempt() throws Exception {
    var correction = service.request(missionId, flightRequest());
    var failedId = correction.supplementalMissionId();
    jdbc.update("update missions set status='FAILED' where id=?", failedId);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    var start = new CountDownLatch(1);
    try {
      List<Future<String>> calls = new ArrayList<>();
      for (int i=0; i<2; i++) calls.add(pool.submit(() -> {
        start.await();
        return drafts.ensureDraft(missionId, correction.id(), true).getId();
      }));
      start.countDown();
      var first = calls.getFirst().get(30, TimeUnit.SECONDS);
      assertThat(calls.getLast().get(30, TimeUnit.SECONDS)).isEqualTo(first).isNotEqualTo(failedId);
      assertThat(jdbc.queryForObject("select count(*) from missions", Integer.class)).isEqualTo(3);
      assertThat(jdbc.queryForObject("select status from missions where id=?", String.class, failedId)).isEqualTo("FAILED");
      assertThat(jdbc.queryForObject("select supplemental_mission_id from mission_inspection_corrections where id=?", String.class, correction.id())).isEqualTo(first);
    } finally { pool.shutdownNow(); }
  }

  private com.ondemandmonitoring.mission.service.impl.MissionService customerDraftService() {
    var service = mock(com.ondemandmonitoring.mission.service.impl.MissionService.class, CALLS_REAL_METHODS);
    var repositoryFactory = new org.springframework.data.jpa.repository.support.JpaRepositoryFactory(
        SharedEntityManagerCreator.createSharedEntityManager(emf));
    var invoices = mock(com.ondemandmonitoring.finance.repository.InvoiceRepository.class);
    var invoice = new com.ondemandmonitoring.finance.domain.Invoice();
    var quote = new com.ondemandmonitoring.finance.domain.Quote();
    quote.setStatus(com.ondemandmonitoring.finance.enums.QuoteStatus.ACCEPTED_BY_CUSTOMER); invoice.setQuote(quote);
    when(invoices.findByOrderId(anyString())).thenReturn(Optional.of(invoice));
    var eligibility = mock(com.ondemandmonitoring.finance.service.IMissionPaymentEligibilityService.class);
    when(eligibility.isDepositSatisfied(any())).thenReturn(true);
    Map<String,Object> dependencies = Map.of(
        "missionRepository", repositoryFactory.getRepository(MissionRepository.class),
        "orderRepository", repositoryFactory.getRepository(OrderRepository.class),
        "resultRevisionCases", repositoryFactory.getRepository(com.ondemandmonitoring.delivery.repository.ResultRevisionCaseRepository.class),
        "resultRevisionCaseItems", repositoryFactory.getRepository(com.ondemandmonitoring.delivery.repository.ResultRevisionCaseItemRepository.class),
        "invoiceRepository", invoices,
        "missionPaymentEligibilityService", eligibility,
        "checklistAssignmentService", assignmentService,
        "reworkContextSnapshots", new com.ondemandmonitoring.mission.service.impl.MissionReworkContextSnapshotService(
            repositoryFactory.getRepository(com.ondemandmonitoring.delivery.repository.ResultRevisionRequestItemRepository.class),
            repositoryFactory.getRepository(MissionReworkChecklistContextRepository.class)));
    dependencies.forEach((name,value) -> org.springframework.test.util.ReflectionTestUtils.setField(service,name,value));
    var mapper = mock(com.ondemandmonitoring.mission.mapper.MissionMapper.class);
    when(mapper.toResponse(any())).thenAnswer(call -> com.ondemandmonitoring.mission.dto.response.MissionResponse.builder()
        .id(((Mission)call.getArgument(0)).getId()).build());
    org.springframework.test.util.ReflectionTestUtils.setField(service,"missionMapper",mapper);
    return service;
  }

  private String customerCase() {
    return tx.execute(status -> {
      var em = SharedEntityManagerCreator.createSharedEntityManager(emf);
      var source = em.find(Mission.class,missionId);
      var revisionCase = new com.ondemandmonitoring.delivery.domain.ResultRevisionCase();
      revisionCase.setOrder(source.getOrder()); revisionCase.setTicketId(UUID.randomUUID().toString());
      revisionCase.setOpenedAt(NOW); em.persist(revisionCase);
      addCustomerCaseItem(em, revisionCase, em.find(MissionChecklistExecution.class,executionId).getOrderChecklistItem(), missionId);
      return revisionCase.getId();
    });
  }

  private void addCustomerCaseItem(EntityManager em, com.ondemandmonitoring.delivery.domain.ResultRevisionCase revisionCase,
      OrderChecklistItem checklist, String sourceId) {
    var item = new com.ondemandmonitoring.delivery.domain.ResultRevisionCaseItem();
    item.setResultRevisionCase(revisionCase); item.setOrderChecklistItem(checklist); item.setSourceMissionId(sourceId);
    item.setScopeDecision(com.ondemandmonitoring.delivery.enums.ResultRevisionScope.IN_SCOPE);
    item.setStrategy(com.ondemandmonitoring.delivery.enums.ResultRevisionStrategy.REWORK);
    item.setStatus(com.ondemandmonitoring.delivery.enums.ResultRevisionCaseItemStatus.IN_PROGRESS);
    item.setManagerInstructions("Redo this scope"); em.persist(item);
  }

  @Test
  void concurrentCustomerDraftsAreIdempotentAndTerminalReplacementIsExplicit() throws Exception {
    var caseId = customerCase();
    var service = customerDraftService();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    var start = new CountDownLatch(1);
    try {
      List<Future<?>> calls = new ArrayList<>();
      for (int i=0; i<2; i++) calls.add(pool.submit(() -> {
        try { start.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new RuntimeException(interrupted); }
        tx.executeWithoutResult(status -> service.ensureResultRevisionReworkDrafts(caseId,false));
      }));
      start.countDown(); for (var call:calls) call.get(30,TimeUnit.SECONDS);
    } finally { pool.shutdownNow(); }
    assertThat(jdbc.queryForObject("select count(*) from missions",Integer.class)).isEqualTo(2);
    assertThat(jdbc.queryForObject("select count(*) from mission_rework_checklist_contexts",Integer.class)).isEqualTo(1);
    var failedId = jdbc.queryForObject("select assigned_rework_mission_id from result_revision_case_items where case_id=?",String.class,caseId);
    jdbc.update("update missions set status='CANCELLED' where id=?",failedId);
    tx.executeWithoutResult(status -> service.ensureResultRevisionReworkDrafts(caseId,false));
    assertThat(jdbc.queryForObject("select count(*) from missions",Integer.class)).isEqualTo(2);
    tx.executeWithoutResult(status -> service.ensureResultRevisionReworkDrafts(caseId,true));
    assertThat(jdbc.queryForObject("select count(*) from missions",Integer.class)).isEqualTo(3);
    assertThat(jdbc.queryForObject("select assigned_rework_mission_id from result_revision_case_items where case_id=?",String.class,caseId)).isNotEqualTo(failedId);
    assertThat(jdbc.queryForObject("select count(*) from mission_rework_checklist_contexts",Integer.class)).isEqualTo(2);
  }

  @Test
  void scopesFromSiblingMissionsJoinOneCustomerDraftWithoutDuplicatingSnapshots() {
    var caseId = customerCase(); var service = customerDraftService();
    tx.executeWithoutResult(status -> service.ensureResultRevisionReworkDrafts(caseId,false));
    var draftId = jdbc.queryForObject("select assigned_rework_mission_id from result_revision_case_items where case_id=?",String.class,caseId);
    tx.executeWithoutResult(status -> {
      var em = SharedEntityManagerCreator.createSharedEntityManager(emf);
      var revisionCase = em.find(com.ondemandmonitoring.delivery.domain.ResultRevisionCase.class,caseId);
      var sibling = new Mission(); sibling.setOrder(revisionCase.getOrder()); sibling.setMissionCode("MS-SIBLING");
      sibling.setStatus(MissionStatus.COMPLETED); em.persist(sibling);
      var checklist = new OrderChecklistItem(); checklist.setOrder(revisionCase.getOrder());
      checklist.setSourceType(OrderChecklistSourceType.CUSTOMER_CUSTOM); checklist.setContent("Second source mission scope");
      checklist.setReviewStatus(ChecklistReviewStatus.INCLUDED); em.persist(checklist);
      addCustomerCaseItem(em,revisionCase,checklist,sibling.getId());
    });
    tx.executeWithoutResult(status -> service.ensureResultRevisionReworkDrafts(caseId,false));
    assertThat(jdbc.queryForObject("select count(distinct assigned_rework_mission_id) from result_revision_case_items where case_id=?",Integer.class,caseId)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from mission_checklist_assignments where mission_id=? and removed_at is null",Integer.class,draftId)).isEqualTo(2);
    assertThat(jdbc.queryForObject("select count(*) from mission_rework_checklist_contexts where mission_id=?",Integer.class,draftId)).isEqualTo(2);
    assertThat(jdbc.queryForObject("select count(*) from mission_checklist_assignments where mission_id=? and removed_at is null",Integer.class,missionId)).isEqualTo(1);
  }

  @Test
  void failedCustomerSnapshotRollsBackTheDraftScopeAndPlanRevisionTogether() {
    var caseId = customerCase(); var service = customerDraftService();
    var snapshots = mock(IMissionReworkContextSnapshotService.class);
    doThrow(new IllegalStateException("Snapshot unavailable")).when(snapshots).capture(any(),anyList());
    org.springframework.test.util.ReflectionTestUtils.setField(service,"reworkContextSnapshots",snapshots);
    assertThatThrownBy(() -> tx.executeWithoutResult(status -> service.ensureResultRevisionReworkDrafts(caseId,false)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(jdbc.queryForObject("select count(*) from missions",Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from result_revision_case_items where assigned_rework_mission_id is not null",Integer.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from mission_checklist_assignments",Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select revision from order_execution_plans",Long.class)).isEqualTo(2L);
  }

  @Test
  void concurrentInitialPlanningPreservesBothScopesAndEveryPlanRevision() throws Exception {
    var ids = tx.execute(status -> {
      var em = SharedEntityManagerCreator.createSharedEntityManager(emf);
      var source = em.find(Mission.class,missionId); source.setStatus(MissionStatus.CANCELLED);
      var order = source.getOrder(); order.setOrderStatus(OrderStatus.APPROVED);
      order.getExecutionPlan().setStatus(ExecutionPlanStatus.DRAFT);
      var first = em.find(MissionChecklistExecution.class,executionId).getOrderChecklistItem();
      var second = new OrderChecklistItem(); second.setOrder(order);
      second.setSourceType(OrderChecklistSourceType.CUSTOMER_CUSTOM); second.setContent("Independent second scope");
      second.setReviewStatus(ChecklistReviewStatus.INCLUDED); em.persist(second);
      return List.of(order.getId(),first.getId(),second.getId());
    });
    var service = customerDraftService();
    ExecutorService pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
    try {
      List<Future<String>> calls = new ArrayList<>();
      for (String itemId:ids.subList(1,3)) calls.add(pool.submit(() -> {
        start.await();
        var request = new com.ondemandmonitoring.mission.dto.request.MissionCreateRequest();
        request.setScopeLabel("Concurrent scope"); request.setChecklistIds(List.of(itemId));
        request.setScheduledStartAt(Instant.parse("2026-12-01T02:00:00Z"));
        request.setScheduledEndAt(Instant.parse("2026-12-01T03:00:00Z"));
        return tx.execute(status -> service.createMission(ids.getFirst(),request).getId());
      }));
      start.countDown();
      assertThat(calls.getFirst().get(30,TimeUnit.SECONDS)).isNotEqualTo(calls.getLast().get(30,TimeUnit.SECONDS));
    } finally { pool.shutdownNow(); }
    assertThat(jdbc.queryForObject("select count(*) from missions",Integer.class)).isEqualTo(3);
    assertThat(jdbc.queryForObject("select revision from order_execution_plans",Long.class)).isEqualTo(4L);
    assertThat(jdbc.queryForObject("select count(*) from mission_checklist_assignments where mission_id<>?",Integer.class,missionId)).isEqualTo(2);
  }

  @Configuration
  @EnableTransactionManagement
  @EnableAspectJAutoProxy
  @EnableJpaAuditing
  @EnableJpaRepositories(
      basePackageClasses = {
        InspectionCorrectionRepository.class,
        MissionRepository.class,
        OrderRepository.class
      })
  static class Config {
    @Bean
    DataSource dataSource() {
      String url = System.getenv("ODMS_CONTRACT_TEST_JDBC_URL");
      if (url.contains(":5433/")) throw new IllegalStateException("Dev database prohibited");
      String user = System.getenv("ODMS_CONTRACT_TEST_DB_USER"),
          password = System.getenv("ODMS_CONTRACT_TEST_DB_PASSWORD");
      var base = new DriverManagerDataSource(url, user, password);
      var jdbc = new JdbcTemplate(base);
      jdbc.execute("CREATE EXTENSION IF NOT EXISTS postgis");
      jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
      jdbc.execute("CREATE SCHEMA " + SCHEMA);
      return new DriverManagerDataSource(
          url + "?currentSchema=" + SCHEMA + ",public", user, password);
    }

    @Bean
    LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
      var factory = new LocalContainerEntityManagerFactoryBean();
      factory.setDataSource(dataSource);
      factory.setPackagesToScan("com.ondemandmonitoring");
      factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
      factory.setJpaPropertyMap(
          Map.of(
              "hibernate.hbm2ddl.auto",
              "create",
              "hibernate.hbm2ddl.halt_on_error",
              "true",
              "hibernate.default_schema",
              SCHEMA));
      return factory;
    }

    @Bean
    PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
      return new JpaTransactionManager(factory);
    }

    @Bean
    IMissionAuthorizationService auth() {
      return mock(IMissionAuthorizationService.class);
    }

    @Bean
    AuthenticatedUserResolver users() {
      var user = mock(AuthenticatedUserResolver.class);
      when(user.getCurrentUserId()).thenReturn("test-actor");
      return user;
    }

    @Bean
    IMissionChecklistScopeService scope() {
      return mock(IMissionChecklistScopeService.class);
    }

    @Bean
    IChecklistEvidenceService evidence() {
      return mock(IChecklistEvidenceService.class);
    }

    @Bean
    InspectionCorrectionMapper mapper() {
      return Mappers.getMapper(InspectionCorrectionMapper.class);
    }

    @Bean
    com.ondemandmonitoring.mission.service.impl.ChecklistEvidencePolicy eligibility() {
      return new com.ondemandmonitoring.mission.service.impl.ChecklistEvidencePolicy();
    }

    @Bean
    com.ondemandmonitoring.mission.service.impl.EvidenceHandoffPolicy handoff(
        MissionEvidenceSubmissionRepository submissions, InspectionCorrectionRepository corrections) {
      return new com.ondemandmonitoring.mission.service.impl.EvidenceHandoffPolicy(submissions, corrections);
    }

    @Bean
    com.ondemandmonitoring.mission.service.impl.MissionEvidenceSubmissionService submissions(
        MissionRepository missions, MissionEvidenceSubmissionRepository submissions,
        MissionChecklistExecutionRepository executions, IChecklistEvidenceService evidence,
        com.ondemandmonitoring.mission.service.impl.ChecklistEvidencePolicy eligibility,
        IMissionChecklistScopeService scope, IMissionAuthorizationService authorization,
        InspectionCorrectionRepository corrections,
        com.ondemandmonitoring.mission.service.impl.EvidenceHandoffPolicy handoff,
        AuthenticatedUserResolver users, Clock clock) {
      return new com.ondemandmonitoring.mission.service.impl.MissionEvidenceSubmissionService(
          missions, submissions, executions, evidence, eligibility, scope, authorization,
          corrections, handoff, users, clock);
    }

    @Bean
    com.ondemandmonitoring.mission.mapper.MissionChecklistExecutionMapper executionMapper() {
      return Mappers.getMapper(com.ondemandmonitoring.mission.mapper.MissionChecklistExecutionMapper.class);
    }

    @Bean
    IMissionResultService resultWorkflow() {
      return mock(IMissionResultService.class);
    }

    @Bean
    Clock clock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    @Bean
    InspectionCorrectionPolicy policy(
        InspectionCorrectionRepository c,
        InspectionCorrectionItemRepository i,
        MissionChecklistExecutionRepository e,
        MissionResultRepository r,
        IChecklistEvidenceService p,
        IMissionChecklistScopeService s) {
      return new InspectionCorrectionPolicy(c, i, e, r, p, s);
    }

    @Bean
    IOrderExecutionCompletionPolicy aggregate(OrderRepository orders, MissionRepository missions) {
      return new IOrderExecutionCompletionPolicy() {
        public LockedExecution lockAggregate(String id) {
          var order = orders.findByIdForUpdate(id).orElseThrow();
          var children =
              missions.findIdsByOrderIdOrderById(id).stream()
                  .sorted()
                  .map(mid -> missions.findByIdForUpdate(mid).orElseThrow())
                  .toList();
          return new LockedExecution(order, children);
        }

        public boolean completeIfReady(LockedExecution e) {
          return false;
        }
      };
    }

    @Bean
    com.ondemandmonitoring.audit.service.AdminAuditLogService auditLog() {
      return mock(com.ondemandmonitoring.audit.service.AdminAuditLogService.class);
    }

    @Bean
    com.ondemandmonitoring.audit.service.AuditInvocationSnapshot auditSnapshots() {
      return new com.ondemandmonitoring.audit.service.AuditInvocationSnapshot(
          new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @Bean
    com.ondemandmonitoring.audit.aspect.BusinessAuditAspect auditAspect(
        com.ondemandmonitoring.audit.service.AdminAuditLogService logs,
        com.ondemandmonitoring.audit.service.AuditInvocationSnapshot snapshots,
        PlatformTransactionManager transactions) {
      return new com.ondemandmonitoring.audit.aspect.BusinessAuditAspect(
          logs, snapshots, transactions);
    }

    @Bean
    com.ondemandmonitoring.delivery.repository.ResultRevisionCaseItemRepository
        revisionCaseItems(EntityManagerFactory emf) {
      return new org.springframework.data.jpa.repository.support.JpaRepositoryFactory(
          SharedEntityManagerCreator.createSharedEntityManager(emf))
          .getRepository(com.ondemandmonitoring.delivery.repository.ResultRevisionCaseItemRepository.class);
    }

    @Bean
    IMissionChecklistAssignmentService assignmentService(
        OrderRepository orders,
        MissionRepository missions,
        com.ondemandmonitoring.order.repository.OrderChecklistItemRepository checklist,
        MissionChecklistAssignmentRepository assignments,
        MissionChecklistExecutionRepository executions,
        AuthenticatedUserResolver users,
        InspectionCorrectionItemRepository items) {
      var guidance =
          mock(com.ondemandmonitoring.finance.service.IAcceptedChecklistGuidanceService.class);
      var assignmentService =
          new com.ondemandmonitoring.mission.service.impl.MissionChecklistAssignmentServiceImpl(
              orders,
              missions,
              checklist,
              assignments,
              executions,
              users,
              Mappers.getMapper(
                  com.ondemandmonitoring.mission.mapper.MissionChecklistAssignmentMapper.class),
              guidance);
      org.springframework.test.util.ReflectionTestUtils.setField(
          assignmentService, "inspectionItems", items);
      return assignmentService;
    }

    @Bean
    com.ondemandmonitoring.mission.inspection.service.IInternalInspectionMissionService
        internalMissions(
            IOrderExecutionCompletionPolicy aggregate,
            MissionRepository missions,
            OrderRepository orders,
            InspectionCorrectionRepository corrections,
            InspectionCorrectionItemRepository items,
            IMissionChecklistAssignmentService assignmentService,
            AuthenticatedUserResolver users,
            Clock clock,
            InspectionCorrectionEventRepository events) {
      var invoices = mock(com.ondemandmonitoring.finance.repository.InvoiceRepository.class);
      when(invoices.findByOrderId(anyString()))
          .thenReturn(Optional.of(new com.ondemandmonitoring.finance.domain.Invoice()));
      var payment =
          mock(com.ondemandmonitoring.finance.service.IMissionPaymentEligibilityService.class);
      when(payment.isDepositSatisfied(any())).thenReturn(true);
      var mapper = mock(com.ondemandmonitoring.mission.mapper.MissionMapper.class);
      when(mapper.toResponse(any()))
          .thenAnswer(
              call ->
                  com.ondemandmonitoring.mission.dto.response.MissionResponse.builder()
                      .id(((Mission) call.getArgument(0)).getId())
                      .build());
      return new com.ondemandmonitoring.mission.inspection.service.impl
          .InternalInspectionMissionService(
          aggregate,
          missions,
          orders,
          invoices,
          corrections,
          items,
          assignmentService,
          payment,
          mock(com.ondemandmonitoring.mission.service.impl.MissionPlanningActivationService.class),
          mapper,
          clock,
          events,
          users);
    }

    @Bean
    IInspectionCorrectionService service(
        MissionRepository m,
        MissionResultRepository r,
        MissionChecklistExecutionRepository e,
        MissionChecklistEvidenceRepository evidence,
        InspectionCorrectionRepository c,
        InspectionCorrectionItemRepository i,
        InspectionCorrectionEventRepository events,
        IMissionAuthorizationService a,
        AuthenticatedUserResolver u,
        IMissionChecklistScopeService s,
        IChecklistEvidenceService p,
        InspectionCorrectionMapper mapper,
        Clock clock,
        IOrderExecutionCompletionPolicy aggregate,
        InspectionCorrectionPolicy policy) {
      return new InspectionCorrectionService(
          m, r, e, evidence, c, i, events, a, u, s, p, mapper, clock, aggregate, policy);
    }
  }
}
