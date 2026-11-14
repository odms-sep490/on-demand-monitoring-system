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
          execution.setMissionId(missionId);
          execution.setOrderId(order.getId());
          execution.setOrderChecklistItemId(item.getId());
          execution.setMissionChecklistAssignmentId(assignment.getId());
          em.persist(execution);
          em.flush();
          executionId = execution.getId();
        });
  }

  InspectionCorrectionRequest request() {
    return new InspectionCorrectionRequest(
        UUID.randomUUID(),
        List.of(
            new InspectionCorrectionRequest.Item(
                executionId, 0L, "The image is blurred", List.of())));
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
    var opened = service.request(missionId, request());
    String[] childIds = new String[2];
    tx.executeWithoutResult(
        status -> {
          EntityManager em = SharedEntityManagerCreator.createSharedEntityManager(emf);
          var correction = corrections.findById(opened.id()).orElseThrow();
          var original = items.findItems(opened.id()).getFirst().getExecution();
          Mission child = new Mission();
          child.setOrder(correction.getMission().getOrder());
          child.setMissionCode("MS-INTERNAL-TEST");
          child.setPurpose(MissionPurpose.REWORK);
          child.setInspectionCorrection(correction);
          child.setStatus(MissionStatus.COMPLETED);
          child.setScopeLabel("Recapture fence");
          child.setScheduledStartAt(NOW.minusSeconds(1800));
          child.setScheduledEndAt(NOW.plusSeconds(1800));
          child.setActualEndAt(NOW);
          child.setCompletedAt(NOW);
          em.persist(child);
          em.flush();
          correction.setSupplementalMission(child);
          corrections.saveAndFlush(correction);
          var assignment = new MissionChecklistAssignment();
          assignment.setMissionId(child.getId());
          assignment.setOrderId(child.getOrder().getId());
          assignment.setOrderChecklistItemId(original.getOrderChecklistItemId());
          assignment.setAssignedAt(NOW.minusSeconds(60));
          em.persist(assignment);
          em.flush();
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
        .containsExactly("REQUESTED", "RESUBMITTED", "RESOLVED");
  }

  @Configuration
  @EnableTransactionManagement
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
