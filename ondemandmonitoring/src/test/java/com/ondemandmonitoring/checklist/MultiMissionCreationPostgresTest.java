package com.ondemandmonitoring.checklist;

import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.dto.request.MissionCreateRequest;
import com.ondemandmonitoring.mission.dto.response.MissionResponse;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.mission.service.IMissionService;
import com.ondemandmonitoring.order.enums.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in actual PostgreSQL, random schema, real Order lock and transaction boundaries. */
@SpringJUnitConfig(OrderMissionPostgresTest.MissionConfig.class)
class MultiMissionCreationPostgresTest extends ChecklistPostgresTest {
    @Autowired MissionRepository missions;
    @Autowired IMissionService missionService;
    JdbcTemplate jdbc;
    com.ondemandmonitoring.order.dto.request.OrderCreateRequest baseRequest;
    @Override @BeforeEach void prepare() {
        jdbc=new JdbcTemplate(dataSource); jdbc.execute("TRUNCATE missions CASCADE");
        super.prepare(); baseRequest=null;
    }
    String order() {
        // Reuse one identity/time fixture when creating another Order in the same test.
        var s=baseRequest==null ? service("Monitoring") : baseRequest.getServiceId();
        if(baseRequest==null) baseRequest=orderRequest(s);
        baseRequest.setServiceId(s);
        var created=orderService.createOrder(baseRequest);
        transaction.executeWithoutResult(tx->orders.findById(created.getId()).orElseThrow().setOrderStatus(OrderStatus.APPROVED));
        return created.getId();
    }
    MissionCreateRequest request() {
        var r=new MissionCreateRequest();
        r.setScopeLabel("Whole Order scope");
        var start=java.time.LocalDate.now().plusDays(7).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        r.setScheduledStartAt(start); r.setScheduledEndAt(start.plusSeconds(3600)); return r;
    }
    long count(String order) {return jdbc.queryForObject("select count(*) from missions where order_id=?",Long.class,order);}
    @Test void multipleRequestsPersistWithoutAnyExecutions() {
        String o=order();
        var first=missionService.createMission(o, request());
        var second=missionService.createMission(o, request());
        assertNotEquals(first.getId(),second.getId()); assertEquals(2,count(o));
        assertEquals(0,jdbc.queryForObject("select count(*) from mission_checklist_executions",Integer.class));
        var children=missions.findAllByOrderIdOrderByScheduledStartAtAscIdAsc(o);
        assertEquals(2,children.size()); assertTrue(children.get(0).getId().compareTo(children.get(1).getId())<0);
        assertTrue(children.stream().allMatch(m->m.getStatus() == com.ondemandmonitoring.mission.enums.MissionStatus.PLANNING));
    }
    @Test void failedInsertRollsBackPlanRevisionAtomically() {
        String o=order(); var request=request();
        jdbc.execute("alter table missions add constraint fixture_reject_new check (mission_code is null)");
        try {assertThrows(jakarta.persistence.PersistenceException.class,()->missionService.createMission(o, request)); assertEquals(0,count(o));}
        finally {jdbc.execute("alter table missions drop constraint fixture_reject_new");}
        missionService.createMission(o, request);
        assertEquals(1,count(o));
    }
    List<Object> concurrent(String orderId, MissionCreateRequest a,MissionCreateRequest b) throws Exception {
        var locked=new CountDownLatch(1); var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(3)) {
            var holder=pool.submit(()->transaction.executeWithoutResult(tx->{orders.findByIdForUpdate(orderId).orElseThrow();locked.countDown();try {assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}}));
            assertTrue(locked.await(5,TimeUnit.SECONDS));
            Callable<Object> first=()->{try{return missionService.createMission(orderId, a);}catch(ApiException e){return e.getErrorCode();}};
            Callable<Object> second=()->{try{return missionService.createMission(orderId, b);}catch(ApiException e){return e.getErrorCode();}};
            var f=pool.submit(first);var s=pool.submit(second);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);int waiting=0;
            while(System.nanoTime()<deadline&&waiting<2){waiting=jdbc.queryForObject("select count(*) from pg_stat_activity where datname=current_database() and usename=current_user and wait_event_type='Lock' and cardinality(pg_blocking_pids(pid)) > 0",Integer.class);Thread.sleep(20);}
            assertEquals(2,waiting); release.countDown();holder.get(10,TimeUnit.SECONDS);
            return List.of(f.get(10,TimeUnit.SECONDS),s.get(10,TimeUnit.SECONDS));
        } finally {release.countDown();}
    }
    @Test void concurrentRequestsAreSerializedAndBothPersist() throws Exception {
        String o=order();var a=request();var b=request();
        var outcomes=concurrent(o,a,b);
        assertEquals(2,outcomes.stream().filter(MissionResponse.class::isInstance).count());
        assertEquals(2,count(o));
    }
}
