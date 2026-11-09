package com.ondemandmonitoring.checklist;

import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.device.repository.DeviceRepository;
import com.ondemandmonitoring.devicecheck.domain.PersistedPostDeviceCheck;
import com.ondemandmonitoring.devicecheck.enums.DeviceCheckStatus;
import com.ondemandmonitoring.devicecheck.repository.PersistedPostDeviceCheckRepository;
import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.dto.request.*;
import com.ondemandmonitoring.mission.enums.*;
import com.ondemandmonitoring.mission.mapper.MissionMapper;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.*;
import com.ondemandmonitoring.mission.service.impl.*;
import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.repository.UserRepository;
import com.ondemandmonitoring.user.service.*;
import com.ondemandmonitoring.userschedule.repository.UserScheduleRepository;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(ResourceSafetyPostgresTest.Beans.class)
class ResourceSafetyPostgresTest extends ExecutionPlanAmendmentPostgresTest {
    @Autowired IResourceReservationService reservations;
    MissionService operational;
    DeviceConnectionService sessions;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = MissionStatus.class, names = {"COMPLETED", "CANCELLED"})
    void terminalMissionRejectsStructuralAndOperationalCommands(MissionStatus terminal) {
        ready(0);
        withMission(first, mission -> mission.setStatus(terminal));
        var before = history(first);
        long beforeRevision = revision();
        var schedule = new MissionUpdateRequest();
        var crew = new AssignStaffRequest(); crew.setStaffId(staff()); crew.setAssignedRole("PILOT");
        var device = new AssignDeviceRequest(); device.setDeviceId(device()); device.setDeviceRole(DeviceRole.MAIN);

        assertThrows(ApiException.class, () -> missionService.updateMission(first, schedule));
        assertThrows(ApiException.class, () -> transaction.executeWithoutResult(tx -> operational.assignStaff(first, crew)));
        assertThrows(ApiException.class, () -> transaction.executeWithoutResult(tx -> operational.assignDevice(first, device)));
        assertThrows(ApiException.class, () -> choose(first));
        assertThrows(ApiException.class, () -> transaction.executeWithoutResult(tx -> sessions.connectGcs(first)));
        assertThrows(ApiException.class, () -> transaction.executeWithoutResult(tx -> operational.startMission(first)));
        assertEquals(terminal, missions.findById(first).orElseThrow().getStatus());
        assertEquals(beforeRevision, revision()); assertEquals(before, history(first));
        assertEquals(0, rows("resource_time_locks"));
        assertNotNull(plan.get(orderId));
    }

    static <T> T repository(EntityManagerFactory factory, Class<T> type) {
        return new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(factory)).getRepository(type);
    }
    @Override @BeforeEach void prepare() {
        super.prepare();
        var mapper = mock(MissionMapper.class);
        when(mapper.toResponse(any())).thenAnswer(c -> {
            Mission m = c.getArgument(0); return com.ondemandmonitoring.mission.dto.response.MissionResponse.builder().id(m.getId()).status(m.getStatus()).build();
        });
        var directory = mock(IStaffDirectoryService.class);
        when(directory.requireActiveStaff(anyString())).thenAnswer(c -> repository(entityManagerFactory, UserRepository.class).findById(c.getArgument(0)).orElseThrow());
        sessions = new DeviceConnectionService(missions, repository(entityManagerFactory, DeviceConnectionRepository.class),
                repository(entityManagerFactory, MissionDeviceAssignmentRepository.class), repository(entityManagerFactory, MissionStaffAssignmentRepository.class),
                repository(entityManagerFactory, DeviceRepository.class), mapper, scope, reservations);
        operational = new MissionService(missions, repository(entityManagerFactory, DeviceRepository.class),
                mapper,
                null,repository(entityManagerFactory, DeviceConnectionRepository.class),null,
                repository(entityManagerFactory, PersistedPostDeviceCheckRepository.class),null,orders,null,null,null,sessions,resultService,
                null,null,directory,resolver,repository(entityManagerFactory, UserScheduleRepository.class),
                repository(entityManagerFactory, MissionDeviceAssignmentRepository.class),repository(entityManagerFactory, MissionStaffAssignmentRepository.class),
                repository(entityManagerFactory, ResourceTimeLockRepository.class),runtime,
                mock(com.ondemandmonitoring.mission.service.IMissionChecklistAssignmentService.class),
                reservations,
                repository(entityManagerFactory, com.ondemandmonitoring.device.repository.DeviceTelemetryRepository.class));
    }
    void withMission(String id, Consumer<Mission> action) {
        transaction.executeWithoutResult(tx -> {
            orders.findByIdForUpdate(missions.findOrderIdByMissionId(id).orElseThrow()).orElseThrow();
            action.accept(missions.findByIdForUpdate(id).orElseThrow());
        });
    }
    String independentMission() {
        String other = foreignOrder(); var request = request("Independent scope", 0);
        String id = missionService.createMission(other, request).getId();
        plan.finalizePlan(other,new ExecutionPlanFinalizeRequest(0L,true)); return id;
    }
    void assertSingleConflict(List<Object> outcomes) { assertEquals(1,outcomes.stream().filter(x -> x == ErrorCode.RESOURCE_TIME_CONFLICT).count()); }
    String device() {
        String id=UUID.randomUUID().toString(),model=UUID.randomUUID().toString();
        jdbc.update("insert into device_models(id,created_at,version,code,model_name) values (?,now(),0,?,'Model')",model,model);
        jdbc.update("insert into devices(id,created_at,version,serial_number,name,model_id,status) values (?,now(),0,?,'Drone',?,'AVAILABLE')",id,id,model); return id;
    }
    String staff() {
        String role = UUID.randomUUID().toString(), id = UUID.randomUUID().toString();
        jdbc.update("insert into roles(role_id,code,name,is_system_role,is_active,created_at,updated_at) values (?::uuid,'STAFF','Staff',false,true,now(),now()) on conflict(code) do nothing",role);
        jdbc.update("insert into users(id,created_at,version,full_name,email,email_verified,is_active,role_id) select ?,now(),0,'Crew',?,true,true,role_id from roles where code='STAFF'",id,id+"@test.local"); return id;
    }
    void planning(String id) { jdbc.update("update missions set status='PLANNING',completed_at=null where id=?",id); }

    List<String> availableDevices(String missionId) {
        return transaction.execute(tx -> {
            var mapper = mock(com.ondemandmonitoring.device.mapper.DeviceMapper.class);
            when(mapper.toResponse(any())).thenAnswer(c -> {
                com.ondemandmonitoring.device.domain.Device device = c.getArgument(0);
                return com.ondemandmonitoring.device.dto.response.DeviceResponse.builder().id(device.getId()).build();
            });
            return new com.ondemandmonitoring.device.service.impl.DeviceAvailabilityServiceImpl(
                    missions, repository(entityManagerFactory, DeviceRepository.class), mapper)
                    .getAvailableForMission(missionId).stream().map(d -> d.getId()).toList();
        });
    }
    List<String> availableStaff(String missionId) {
        return transaction.execute(tx -> new com.ondemandmonitoring.user.service.impl.OperatorDirectoryServiceImpl(
                repository(entityManagerFactory, UserRepository.class), missions,
                repository(entityManagerFactory, ResourceTimeLockRepository.class),
                repository(entityManagerFactory, UserScheduleRepository.class))
                .getAvailableOperators(missionId).stream().map(u -> u.getId()).toList());
    }
    @Test void availabilityReturnsFreeResourcesWithoutWritingReservations() {
        ready(0); String device = device(), user = staff();
        assertTrue(availableDevices(first).contains(device));
        assertTrue(availableStaff(first).contains(user));
        assertEquals(0, rows("resource_time_locks"));
    }
    @Test void availabilityExcludesOverlappingSiblingResources() {
        ready(0); String device = device(), user = staff();
        withMission(first, m -> reservations.reserveAll(m, Map.of(device, false, user, true)));
        assertFalse(availableDevices(second).contains(device));
        assertFalse(availableStaff(second).contains(user));
    }
    @Test void availabilityAllowsAdjacentIntervals() {
        ready(0); String device = device(), user = staff();
        withMission(first, m -> reservations.reserveAll(m, Map.of(device, false, user, true)));
        withMission(second, m -> { m.setScheduledStartAt(m.getScheduledEndAt()); m.setScheduledEndAt(m.getScheduledStartAt().plusSeconds(3600)); });
        assertTrue(availableDevices(second).contains(device));
        assertTrue(availableStaff(second).contains(user));
    }
    @Test void availabilityIncludesOwnCurrentAssignmentsAndOwnStaffSchedule() {
        ready(0); String device = device(), user = staff(); planning(first);
        var deviceRequest = new AssignDeviceRequest(); deviceRequest.setDeviceId(device);
        var staffRequest = new AssignStaffRequest(); staffRequest.setStaffId(user); staffRequest.setAssignedRole("PILOT");
        transaction.executeWithoutResult(tx -> { operational.assignDevice(first, deviceRequest); operational.assignStaff(first, staffRequest); });
        jdbc.update("update devices set status='RESERVED' where id=?", device);
        assertTrue(availableDevices(first).contains(device));
        assertTrue(availableStaff(first).contains(user));
        assertFalse(availableDevices(second).contains(device));
        assertFalse(availableStaff(second).contains(user));
    }
    @Test void availabilityExcludesOverlappingDifferentOrderAndReleasedLocksBecomeAvailable() {
        ready(0); String other = independentMission(), device = device(), user = staff();
        withMission(other, m -> reservations.reserveAll(m, Map.of(device, false, user, true)));
        assertFalse(availableDevices(first).contains(device));
        assertFalse(availableStaff(first).contains(user));
        withMission(other, m -> reservations.release(other, "TEST_RELEASE"));
        assertTrue(availableDevices(first).contains(device));
        assertTrue(availableStaff(first).contains(user));
    }
    @Test void availabilityRevalidatesAfterScheduleChanges() {
        ready(0); String device = device(), user = staff();
        withMission(first, m -> reservations.reserveAll(m, Map.of(device, false, user, true)));
        withMission(second, m -> { m.setScheduledStartAt(m.getScheduledEndAt()); m.setScheduledEndAt(m.getScheduledStartAt().plusSeconds(3600)); });
        assertTrue(availableDevices(second).contains(device));
        assertTrue(availableStaff(second).contains(user));
        withMission(second, m -> m.setScheduledStartAt(m.getScheduledStartAt().minusSeconds(1)));
        assertFalse(availableDevices(second).contains(device));
        assertFalse(availableStaff(second).contains(user));
    }
    @Test void availabilityPreservesDeviceAndAccountEligibility() {
        ready(0); String device = device(), user = staff();
        for (String status : List.of("MAINTENANCE", "OUT_OF_SERVICE", "OFFLINE", "CHARGING")) {
            jdbc.update("update devices set status=? where id=?", status, device);
            assertFalse(availableDevices(first).contains(device));
        }
        jdbc.update("update users set is_active=false where id=?", user);
        assertFalse(availableStaff(first).contains(user));
        jdbc.update("update users set is_active=true where id=?", user);
        jdbc.update("update roles set is_active=false where code='STAFF'");
        assertFalse(availableStaff(first).contains(user));
    }
    @Test void availabilityRequiresValidPersistedMissionSchedule() {
        ready(0);
        // Persisted Missions cannot lack an end. Service guards also protect incomplete legacy/mock data.
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("update missions set scheduled_end_at=null where id=?", first));
        var incomplete = new Mission(); incomplete.setId(first);
        var lookup = mock(MissionRepository.class);
        when(lookup.findById(first)).thenReturn(Optional.of(incomplete));
        error(ErrorCode.INVALID_REQUEST, () -> new com.ondemandmonitoring.device.service.impl.DeviceAvailabilityServiceImpl(
                lookup, mock(DeviceRepository.class), mock(com.ondemandmonitoring.device.mapper.DeviceMapper.class))
                .getAvailableForMission(first));
        error(ErrorCode.INVALID_REQUEST, () -> new com.ondemandmonitoring.user.service.impl.OperatorDirectoryServiceImpl(
                mock(UserRepository.class), lookup, mock(ResourceTimeLockRepository.class), mock(UserScheduleRepository.class))
                .getAvailableOperators(first));
    }

    @Test void telemetryReadinessIsNonMutatingInPostgresReadOnlyTransaction() {
        ready(0); operationalFixture(first);
        String connection = jdbc.queryForObject("select id from device_connections where mission_id=?",String.class,first);
        jdbc.update("insert into device_telemetry(id,created_at,version,device_connection_id,connected,recorded_at) values (?,now(),0,?,true,now())",UUID.randomUUID().toString(),connection);
        var before = readinessState(first);
        var readOnly = new org.springframework.transaction.support.TransactionTemplate(transaction.getTransactionManager());
        readOnly.setReadOnly(true);
        var response = readOnly.execute(tx -> {
            // Enforce server-side read-only, not just the JDBC hint (PostgreSQL regression).
            jdbc.execute("SET TRANSACTION READ ONLY");
            var result = operational.getTelemetryReadiness(first);
            assertEquals("on",jdbc.queryForObject("show transaction_read_only",String.class));
            assertEquals(0,jdbc.queryForObject("select count(*) from pg_locks where pid=pg_backend_pid() and mode in ('RowShareLock','RowExclusiveLock')",Integer.class));
            return result;
        });
        assertTrue(response.ready());
        assertEquals(jdbc.queryForObject("select device_id from mission_device_assignments where mission_id=?",String.class,first),response.deviceId());
        assertEquals(before,readinessState(first));
        jdbc.update("update device_telemetry set recorded_at=now()-interval '20 seconds' where device_connection_id=?",connection);
        assertFalse(readOnly.execute(tx -> operational.getTelemetryReadiness(first)).ready());
        jdbc.update("update missions set status='CANCELLED' where id=?",first);
        assertFalse(readOnly.execute(tx -> operational.getTelemetryReadiness(first)).ready());
    }
    List<String> readinessState(String missionId) {
        return List.of("missions","mission_device_assignments","mission_staff_assignments","device_connections","resource_time_locks").stream()
                .map(table -> jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(t) order by id)::text,'[]') from "+table+" t where "+(table.equals("missions")?"id":"mission_id")+"=?",String.class,missionId)).toList();
    }

    @Test void sameExclusiveResourceAcrossOrdersSerializesOverlap() throws Exception {
        ready(0); String other=independentMission();
        assertSingleConflict(race(() -> { withMission(first,m -> reservations.reserve(m,"R",false)); return true; },
                () -> { withMission(other,m -> reservations.reserve(m,"R",false)); return true; }));
        assertEquals(1,rows("resource_time_locks"));
    }
    @Test void backToBackResourceIntervalsAreAllowed() {
        ready(0); withMission(first,m -> reservations.reserve(m,"R",false));
        withMission(second,m -> { m.setScheduledStartAt(m.getScheduledEndAt()); m.setScheduledEndAt(m.getScheduledStartAt().plusSeconds(3600)); reservations.reserve(m,"R",false); });
        assertEquals(2,rows("resource_time_locks"));
    }
    @Test void inverseMultiResourceRequestsHaveOneWinnerAndNoDeadlock() throws Exception {
        ready(0); String other=independentMission();
        var a=new LinkedHashMap<String,Boolean>(); a.put("R1",false); a.put("R2",false);
        var b=new LinkedHashMap<String,Boolean>(); b.put("R2",false); b.put("R1",false);
        assertSingleConflict(race(() -> { withMission(first,m -> reservations.reserveAll(m,a)); return true; },
                () -> { withMission(other,m -> reservations.reserveAll(m,b)); return true; }));
        assertEquals(2,rows("resource_time_locks"));
    }
    @Test void conflictingMultiResourceBatchLeavesNoPartialReservation() {
        ready(0); withMission(first,m -> reservations.reserve(m,"R2",false));
        error(ErrorCode.RESOURCE_TIME_CONFLICT, () -> withMission(second,m -> reservations.reserveAll(m,Map.of("R1",false,"R2",false))));
        assertEquals(1,rows("resource_time_locks"));
    }
    @Test void actualDeviceAssignmentsAcrossOrdersCannotDoubleBook() throws Exception {
        ready(0); String other=independentMission(), device=device(); planning(first); planning(other);
        var request = new AssignDeviceRequest(); request.setDeviceId(device);
        assertSingleConflict(race(() -> transaction.execute(tx -> operational.assignDevice(first,request)),
                () -> transaction.execute(tx -> operational.assignDevice(other,request))));
        assertEquals(1,rows("mission_device_assignments")); assertEquals(1,rows("resource_time_locks"));
    }
    @Test void actualStaffAssignmentsAcrossOrdersCannotDoubleBook() throws Exception {
        ready(0); String other=independentMission(), user=staff(); planning(first); planning(other);
        var request=new AssignStaffRequest(); request.setStaffId(user); request.setAssignedRole("PILOT");
        assertSingleConflict(race(() -> transaction.execute(tx -> operational.assignStaff(first,request)),
                () -> transaction.execute(tx -> operational.assignStaff(other,request))));
        assertEquals(1,rows("mission_staff_assignments")); assertEquals(1,rows("user_schedules"));
    }
    @Test void allCrewRolesUseTheSameExclusiveStaffReservation() {
        ready(0); String user=staff(); planning(first); planning(second);
        var request=new AssignStaffRequest(); request.setStaffId(user);
        for (String role : List.of("PILOT","OPERATOR","MAINTAINER","INSPECTOR")) {
            request.setAssignedRole(role); transaction.executeWithoutResult(tx -> operational.assignStaff(first,request));
        }
        error(ErrorCode.RESOURCE_TIME_CONFLICT, () -> transaction.executeWithoutResult(tx -> operational.assignStaff(second,request)));
        assertEquals(4,rows("mission_staff_assignments")); assertEquals(1,rows("resource_time_locks"));
    }
    @Test void scheduleRevalidationIsAtomicAndReleaseKeepsAuditOnReplay() {
        ready(0); withMission(first,m -> reservations.reserve(m,"R",false));
        withMission(second,m -> { m.setScheduledStartAt(m.getScheduledEndAt()); m.setScheduledEndAt(m.getScheduledStartAt().plusSeconds(3600)); reservations.reserve(m,"R",false); });
        var before=jdbc.queryForMap("select start_time,end_time from resource_time_locks where mission_id=?",second);
        error(ErrorCode.RESOURCE_TIME_CONFLICT, () -> withMission(second,m -> reservations.revalidateSchedule(m,m.getScheduledStartAt().minusSeconds(1),m.getScheduledEndAt())));
        assertEquals(before,jdbc.queryForMap("select start_time,end_time from resource_time_locks where mission_id=?",second));
        withMission(first,m -> reservations.release(first,"TEST_RELEASE"));
        var audit=jdbc.queryForMap("select id,released_at,release_reason,version from resource_time_locks where mission_id=?",first);
        withMission(first,m -> reservations.release(first,"DIFFERENT_REASON"));
        assertEquals(audit,jdbc.queryForMap("select id,released_at,release_reason,version from resource_time_locks where mission_id=?",first));
        assertEquals(2,rows("resource_time_locks"));
    }

    String operationalFixture(String mission) {
        String pilot=staff(), device=device(), da=UUID.randomUUID().toString(), sa=UUID.randomUUID().toString();
        jdbc.update("insert into mission_device_assignments(id,created_at,version,mission_id,device_id,device_role,checkup_status,is_current) values (?,now(),0,?,?,'MAIN','PENDING',true)",da,mission,device);
        jdbc.update("insert into mission_staff_assignments(id,created_at,version,mission_id,staff_id,assigned_role,response_status,is_current) values (?,now(),0,?,?,'PILOT','ACCEPTED',true)",sa,mission,pilot);
        jdbc.update("insert into device_connections(id,created_at,version,mission_id,mission_device_assignment_id,mission_staff_assignment_id,connection_status,telemetry_active,connected_at) values (?,now(),0,?, ?,?,'CONNECTED',true,now())",UUID.randomUUID().toString(),mission,da,sa);
        withMission(mission,m -> reservations.reserveAll(m,Map.of(device,false,pilot,true)));
        jdbc.update("update missions set status='IN_FLIGHT',actual_start_at=now(),completed_at=null where id=?",mission); return pilot;
    }
    @Test void databaseRejectsOverlapEvenWithoutApplicationMutexButAllowsAdjacentWindows() {
        ready(0); withMission(first,m -> reservations.reserve(m,"R",false));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> jdbc.update(
                "insert into resource_time_locks(id,resource_id,mission_id,start_time,end_time,lock_status,created_at,version) select ?,resource_id,?,start_time,end_time,lock_status,now(),0 from resource_time_locks where mission_id=?",
                UUID.randomUUID().toString(),second,first));
        jdbc.update("insert into resource_time_locks(id,resource_id,mission_id,start_time,end_time,lock_status,created_at,version) select ?,resource_id,?,end_time,end_time+interval '1 hour',lock_status,now(),0 from resource_time_locks where mission_id=?",
                UUID.randomUUID().toString(),second,first);
        assertEquals(2,rows("resource_time_locks"));
        assertEquals(1,jdbc.queryForObject("select count(*) from pg_extension where extname='btree_gist'",Integer.class));
    }

    @Configuration @Import(ExecutionPlanAmendmentPostgresTest.Beans.class)
    static class Beans {
        @Bean IResourceReservationService resourceReservations(DataSource data,EntityManagerFactory factory,AuthenticatedUserResolver actor) {
            return new ResourceReservationServiceImpl(new JdbcTemplate(data),repository(factory,ResourceTimeLockRepository.class),repository(factory,UserScheduleRepository.class),actor);
        }
    }
}
