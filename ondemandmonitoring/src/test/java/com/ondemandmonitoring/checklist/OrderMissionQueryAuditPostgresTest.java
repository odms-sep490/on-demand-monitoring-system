package com.ondemandmonitoring.checklist;

import com.ondemandmonitoring.media.enums.MediaStatus;
import com.ondemandmonitoring.media.repository.MediaAssetRepository;
import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.enums.*;
import com.ondemandmonitoring.mission.mapper.*;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.impl.CustomerMissionReportQueryService;
import com.ondemandmonitoring.user.repository.UserRepository;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.hibernate.SessionFactory;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mapstruct.factory.Mappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;
import static com.ondemandmonitoring.checklist.ResourceSafetyPostgresTest.repository;
import static org.junit.jupiter.api.Assertions.*;

/** Cold transaction measurements on canonical, disposable PostgreSQL; not a production benchmark. */
@SpringJUnitConfig(OrderMissionQueryAuditPostgresTest.Beans.class)
@EnabledIfEnvironmentVariable(named = "ODMS_CHECKLIST_TEST_JDBC_URL",
        matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/odms_checklist_verification")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderMissionQueryAuditPostgresTest {
    @Autowired ApplicationContext context;
    ResourceSafetyPostgresTest fixture;
    MissionMapper mapper;
    CustomerMissionReportQueryService reports;
    final List<String> ids = new ArrayList<>();
    final Map<String, String> devices = new HashMap<>();
    final Map<String, String> operators = new HashMap<>();
    static final List<String> SQL = new CopyOnWriteArrayList<>();

    @BeforeEach void prepare() {
        fixture = new ResourceSafetyPostgresTest();
        context.getAutowireCapableBeanFactory().autowireBean(fixture);
        fixture.prepare(); ids.clear(); devices.clear(); operators.clear();
        var plans = Mappers.getMapper(MissionPlanMapper.class);
        var helper = new MissionMapperHelper(repository(fixture.entityManagerFactory, MissionPlanRepository.class),
                repository(fixture.entityManagerFactory, MissionDeviceAssignmentRepository.class),
                repository(fixture.entityManagerFactory, MissionStaffAssignmentRepository.class),
                plans, Mappers.getMapper(MissionStaffAssignmentMapper.class),
                Mappers.getMapper(MissionDeviceAssignmentMapper.class),
                repository(fixture.entityManagerFactory, UserRepository.class));
        mapper = Mappers.getMapper(MissionMapper.class);
        ReflectionTestUtils.setField(mapper, "missionMapperHelper", helper);
        ReflectionTestUtils.setField(mapper, "missionPlanMapper", plans);
        ReflectionTestUtils.setField(fixture.operational, "missionMapper", mapper);
        ReflectionTestUtils.setField(fixture.operational, "missionPlanRepository", repository(fixture.entityManagerFactory, MissionPlanRepository.class));
        reports = new CustomerMissionReportQueryService(fixture.orders, fixture.missions, fixture.resultRepository,
                fixture.executions, fixture.resolver, Mappers.getMapper(CustomerMissionReportMapper.class));
    }

    @AfterAll void cleanup() { if (fixture != null) fixture.cleanup(); }

    void data(int missionCount, int itemCount) {
        fixture.fixture(itemCount); ids.add(fixture.first); ids.add(fixture.second);
        while (ids.size() < missionCount) ids.add(fixture.create());
        int perMission = itemCount == 5 ? 2 : 4;
        for (int i = 0; i < ids.size(); i++) {
            int[] selected = new int[perMission];
            for (int j = 0; j < perMission; j++) selected[j] = (i * perMission + j) % itemCount;
            fixture.choose(ids.get(i), selected);
        }
        fixture.finalizePlan();
        for (String id : ids) {
            String mediaId = fixture.asset(id);
            var resources = new HashMap<String, Boolean>();
            resources.put(fixture.jdbc.queryForObject("select device_id from mission_device_assignments where mission_id=? and is_current", String.class, id), false);
            devices.put(id, resources.keySet().iterator().next());
            fixture.transaction.executeWithoutResult(tx -> {
                var m = fixture.missions.findById(id).orElseThrow();
                m.setStatus(MissionStatus.COMPLETED); m.setCompletedAt(Instant.now());
                var plan = new MissionPlan(); plan.setMission(m);
                plan.setPlanningAlgorithm(PlanningAlgorithm.DIRECT); plan.setFeasibilityStatus(FeasibilityStatus.FEASIBLE);
                repository(fixture.entityManagerFactory, MissionPlanRepository.class).saveAndFlush(plan);
                var media = repository(fixture.entityManagerFactory, MediaAssetRepository.class).findById(mediaId).orElseThrow();
                media.setMediaStatus(MediaStatus.AVAILABLE);
                for (var e : fixture.executions.findOrderedByMissionId(id)) {
                    e.setExecutionStatus(ChecklistExecutionStatus.COMPLETED);
                    e.setStartedAt(Instant.now()); e.setCompletedAt(Instant.now());
                    var link = new MissionChecklistEvidence(); link.setMissionId(id);
                    link.setMissionChecklistExecution(e); link.setMediaAsset(media);
                    link.setAttachedAt(Instant.now()); link.setAttachedBy(fixture.resolver.getCurrentUserId());
                    repository(fixture.entityManagerFactory, MissionChecklistEvidenceRepository.class).saveAndFlush(link);
                }
            });
            fixture.result(id, MissionResultApprovalStatus.APPROVED);
            for (var role : MissionStaffRole.values()) {
                String staff = fixture.staff();
                if (role == MissionStaffRole.OPERATOR) operators.put(id, staff);
                resources.put(staff, true);
                fixture.transaction.executeWithoutResult(tx -> {
                    var a = new MissionStaffAssignment(); a.setMission(fixture.missions.findById(id).orElseThrow());
                    a.setStaff(repository(fixture.entityManagerFactory, UserRepository.class).findById(staff).orElseThrow());
                    a.setAssignedRole(role); a.setResponseStatus(MissionStaffAssignmentStatus.ACCEPTED); a.setAssignedAt(Instant.now());
                    repository(fixture.entityManagerFactory, MissionStaffAssignmentRepository.class).saveAndFlush(a);
                });
            }
            fixture.withMission(id, mission -> fixture.reservations.reserveAll(mission, resources));
        }
    }

    void measure(String name, int missionCount, int itemCount, Runnable call) {
        var stats = fixture.entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.clear(); SQL.clear(); long start = System.nanoTime(); call.run();
        var grouped = new TreeMap<String, Long>(); SQL.forEach(sql -> grouped.merge(sql.replaceAll("\\s+", " "), 1L, Long::sum));
        System.out.println("QUERY_AUDIT " + name + " missions=" + missionCount + " items=" + itemCount
                + " statements=" + stats.getPrepareStatementCount() + " wallMs=" + (System.nanoTime()-start)/1_000_000);
        grouped.forEach((sql, count) -> System.out.println("QUERY_PATTERN " + name + " count=" + count + " " + sql));
        long maximum = switch (name) {
            case "aggregate" -> 6L * missionCount + 6;
            case "mission-detail" -> 15;
            case "customer-list" -> 3;
            case "customer-report" -> 4;
            case "manager-workspace" -> 6L * missionCount + 18;
            case "checklist-response", "final-readiness" -> 7;
            case "result-approve-replay" -> 6L * missionCount + 9;
            // Separate media review and the Order deliverable-quantity guard each add a bulk read,
            // independent of sibling/item count; preserve the existing linear bound.
            case "result-approve" -> 6L * missionCount + 19;
            case "crew-gate" -> 1;
            case "staff-availability" -> 4;
            case "device-availability" -> 2;
            default -> throw new AssertionError(name);
        };
        if (name.equals("result-approve")) {
            assertEquals(1, SQL.stream().filter(sql -> sql.contains(".order_deliverables ")).count(),
                    "Order quantity validation must load deliverables once, not per Mission/item");
        }
        assertTrue(stats.getPrepareStatementCount() <= maximum,
                name + " exceeded measured statement budget: " + stats.getPrepareStatementCount() + " > " + maximum);
    }

    @ParameterizedTest @CsvSource({"5,5", "10,20"})
    void representativeColdQueryMeasurements(int missionCount, int itemCount) {
        data(missionCount, itemCount);
        String first = ids.getFirst();
        measure("aggregate", missionCount, itemCount, () -> assertTrue(fixture.evaluate()));
        measure("mission-detail", missionCount, itemCount, () -> fixture.transaction.execute(tx -> {
            var response = fixture.operational.getByIdResponse(first);
            assertEquals(first, response.getId()); assertEquals(devices.get(first), response.getDeviceId());
            assertEquals(operators.get(first), response.getStaffId()); assertEquals(response.getStaffId(), response.getOperatorId());
            assertEquals("Test camera", response.getDeviceName()); assertNotNull(response.getDeviceModelName());
            assertEquals(4, response.getStaffAssignments().size()); assertNotNull(response.getPlan());
            return response;
        }));
        measure("customer-list", missionCount, itemCount, () -> fixture.transaction.execute(tx -> reports.list(fixture.orderId)));
        measure("customer-report", missionCount, itemCount, () -> fixture.transaction.execute(tx -> reports.report(fixture.orderId,first)));
        measure("manager-workspace", missionCount, itemCount, () -> {
            fixture.plan.get(fixture.orderId);
            fixture.transaction.execute(tx -> fixture.missions.findAllByOrderIdOrderByScheduledStartAtAscIdAsc(fixture.orderId).stream().map(mapper::toResponse).toList());
            fixture.orderService.getOrderById(fixture.orderId);
        });
        measure("checklist-response", missionCount, itemCount, () -> fixture.runtime.getByMissionId(first));
        measure("final-readiness", missionCount, itemCount, () -> fixture.transaction.execute(tx -> {
            fixture.runtime.requireReadyForFinalApproval(fixture.missions.findById(first).orElseThrow()); return null;
        }));
        String result = fixture.resultRepository.findByMissionId(first).orElseThrow().getId();
        measure("result-approve-replay", missionCount, itemCount, () -> fixture.resultService.approve(result,null));
        fixture.transaction.executeWithoutResult(tx -> fixture.resultRepository.findById(result).orElseThrow()
                .setApprovalStatus(MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL));
        measure("result-approve", missionCount, itemCount, () -> fixture.resultService.approve(result,null));
        measure("crew-gate", missionCount, itemCount, () -> fixture.transaction.execute(tx -> {
            assertTrue((Boolean) ReflectionTestUtils.invokeMethod(fixture.operational,"isRequiredCrewAccepted",first)); return null;
        }));
        measure("staff-availability", missionCount, itemCount, () -> fixture.availableStaff(first));
        measure("device-availability", missionCount, itemCount, () -> fixture.availableDevices(first));
        if (missionCount == 10) explain(first);
    }

    void explain(String id) {
        fixture.jdbc.execute("ANALYZE");
        var mission = fixture.missions.findById(id).orElseThrow();
        var rows = fixture.jdbc.queryForList("select tablename,indexname,indexdef from pg_indexes where schemaname=? and tablename in ('missions','mission_results','mission_checklist_assignments','mission_checklist_executions','mission_checklist_evidence','resource_time_locks','mission_device_assignments','user_schedules') order by tablename,indexname", ChecklistPostgresTest.SCHEMA);
        rows.forEach(row -> System.out.println("INDEX_AUDIT " + row));
        String resource = fixture.jdbc.queryForObject("select device_id from mission_device_assignments where mission_id=? and is_current",String.class,id);
        List<String> plan = fixture.jdbc.queryForList("explain (analyze,buffers) select 1 from resource_time_locks where resource_id=? and released_at is null and mission_id<>? and start_time<? and end_time>?",String.class,resource,id,java.sql.Timestamp.from(mission.getScheduledEndAt()),java.sql.Timestamp.from(mission.getScheduledStartAt()));
        plan.forEach(line -> System.out.println("EXPLAIN_AUDIT overlap " + line));
        fixture.jdbc.queryForList("explain (analyze,buffers) select id from missions where order_id=? order by scheduled_start_at,id",String.class,fixture.orderId)
                .forEach(line -> System.out.println("EXPLAIN_AUDIT missions " + line));
        var placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        fixture.jdbc.queryForList("explain (analyze,buffers) select * from mission_results where mission_id in (" + placeholders + ")", String.class, ids.toArray())
                .forEach(line -> System.out.println("EXPLAIN_AUDIT results " + line));
        fixture.jdbc.queryForList("explain (analyze,buffers) select * from mission_checklist_evidence where mission_id=? and detached_at is null", String.class, id)
                .forEach(line -> System.out.println("EXPLAIN_AUDIT evidence " + line));
        fixture.jdbc.queryForList("explain (analyze,buffers) select * from mission_checklist_assignments where order_id=? and removed_at is null order by mission_id,display_order,id", String.class, fixture.orderId)
                .forEach(line -> System.out.println("EXPLAIN_AUDIT assignments " + line));
        var staffIds = new ArrayList<>(operators.values());
        var availabilityArgs = new ArrayList<Object>(staffIds);
        availabilityArgs.add(id); availabilityArgs.add(java.sql.Timestamp.from(mission.getScheduledEndAt()));
        availabilityArgs.add(java.sql.Timestamp.from(mission.getScheduledStartAt()));
        fixture.jdbc.queryForList("explain (analyze,buffers) select distinct resource_id from resource_time_locks where resource_id in ("
                + String.join(",", Collections.nCopies(staffIds.size(), "?")) + ") and released_at is null and mission_id<>? and start_time<? and end_time>?", String.class, availabilityArgs.toArray())
                .forEach(line -> System.out.println("EXPLAIN_AUDIT staff-overlap " + line));
    }

    @Test void availableDeviceDtoMappingQueryAudit() {
        data(5, 5);
        for (int i = 0; i < 10; i++) fixture.device();
        fixture.transaction.executeWithoutResult(tx -> {
            var entityManager = org.springframework.orm.jpa.SharedEntityManagerCreator.createSharedEntityManager(fixture.entityManagerFactory);
            var model = entityManager.createQuery("select d.deviceModel from Device d where d.id = :id", com.ondemandmonitoring.device.domain.DeviceModel.class)
                    .setParameter("id", devices.get(ids.getFirst())).getSingleResult();
            for (int i = 0; i < 2; i++) {
                var type = new com.ondemandmonitoring.device.domain.DeviceType();
                type.setCode("AUDIT_TYPE_" + i); type.setName("Audit type " + i);
                entityManager.persist(type);
                model.getDeviceTypes().add(type);
            }
        });
        var deviceMapper = Mappers.getMapper(com.ondemandmonitoring.device.mapper.DeviceMapper.class);
        var modelMapper = Mappers.getMapper(com.ondemandmonitoring.device.mapper.DeviceModelMapper.class);
        ReflectionTestUtils.setField(modelMapper, "deviceTypeMapper",
                Mappers.getMapper(com.ondemandmonitoring.device.mapper.DeviceTypeMapper.class));
        ReflectionTestUtils.setField(deviceMapper, "deviceModelMapper",
                modelMapper);
        var availability = new com.ondemandmonitoring.device.service.impl.DeviceAvailabilityServiceImpl(
                fixture.missions, repository(fixture.entityManagerFactory, com.ondemandmonitoring.device.repository.DeviceRepository.class), deviceMapper);
        var expectedIds = fixture.transaction.execute(tx -> {
            var mission = fixture.missions.findById(ids.getFirst()).orElseThrow();
            return repository(fixture.entityManagerFactory, com.ondemandmonitoring.device.repository.DeviceRepository.class)
                    .findAvailableForMission(mission.getId(), mission.getScheduledStartAt(), mission.getScheduledEndAt())
                    .stream().map(d -> d.getId()).collect(java.util.stream.Collectors.toSet());
        });
        var stats = fixture.entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.clear(); SQL.clear();
        var available = fixture.transaction.execute(tx -> availability.getAvailableForMission(ids.getFirst()));
        assertTrue(available.size() >= 11);
        assertEquals(expectedIds, available.stream().map(d -> d.getId()).collect(java.util.stream.Collectors.toSet()));
        System.out.println("DEVICE_DTO_AUDIT available=" + available.size() + " statements=" + stats.getPrepareStatementCount());
        assertEquals(2, stats.getPrepareStatementCount());
        assertTrue(available.stream().allMatch(d -> d.getDeviceModel() != null));
        assertEquals(available.size(), available.stream().map(d -> d.getId()).distinct().count());
        assertTrue(available.stream().anyMatch(d -> d.getDeviceModel().getDeviceTypes().size() == 2));
    }

    @Configuration @Import(ResourceSafetyPostgresTest.Beans.class)
    static class Beans {
        @Bean static BeanPostProcessor sqlInspector() {
            return new BeanPostProcessor() {
                @Override public Object postProcessBeforeInitialization(Object bean, String name) {
                    if (bean instanceof LocalContainerEntityManagerFactoryBean factory)
                        factory.getJpaPropertyMap().put("hibernate.session_factory.statement_inspector",
                                (StatementInspector) sql -> { SQL.add(sql); return sql; });
                    return bean;
                }
            };
        }
    }
}
