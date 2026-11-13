package com.ondemandmonitoring.userschedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.mission.repository.ResourceTimeLockRepository;
import com.ondemandmonitoring.mission.service.impl.ResourceReservationServiceImpl;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.repository.UserRepository;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import com.ondemandmonitoring.userschedule.dto.request.BusyScheduleRequest;
import com.ondemandmonitoring.userschedule.enums.BusySession;
import com.ondemandmonitoring.userschedule.repository.UserScheduleRepository;
import com.ondemandmonitoring.userschedule.service.impl.BusyScheduleServiceImpl;
import jakarta.persistence.EntityManagerFactory;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real-Postgres check of operator busy sessions and the staff-assignment guard. Requires a disposable database:
 * set ODMS_BUSY_TEST_JDBC_URL (port 5433 / the dev database is refused). Skipped when unset.
 */
class BusySchedulePostgresTest {
    static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-11T01:00:00Z"), VN); // Wed 08:00 VN
    static EntityManagerFactory emf;
    static JdbcTemplate jdbc;
    static TransactionTemplate tx;

    AuthenticatedUserResolver resolver = mock(AuthenticatedUserResolver.class);
    UserRepository users;
    UserScheduleRepository schedules;
    ResourceTimeLockRepository locks;
    MissionRepository missions;
    BusyScheduleServiceImpl busy;
    ResourceReservationServiceImpl reservations;
    String operatorId;
    UUID roleId;

    static org.springframework.context.annotation.AnnotationConfigApplicationContext context;

    /** Same JPA auditing as the app (@EnableJpaAuditing) so created_at/updated_at are populated. */
    @org.springframework.context.annotation.Configuration
    @org.springframework.data.jpa.repository.config.EnableJpaAuditing
    static class Jpa {
        @org.springframework.context.annotation.Bean
        javax.sql.DataSource dataSource() {
            return new DriverManagerDataSource(System.getenv("ODMS_BUSY_TEST_JDBC_URL"),
                    System.getenv().getOrDefault("ODMS_BUSY_TEST_DB_USER", "busy_test"),
                    System.getenv().getOrDefault("ODMS_BUSY_TEST_DB_PASSWORD", "busy_test_only"));
        }

        @org.springframework.context.annotation.Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(javax.sql.DataSource ds) {
            var f = new LocalContainerEntityManagerFactoryBean();
            f.setDataSource(ds);
            f.setPackagesToScan("com.ondemandmonitoring");
            f.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            f.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop", "hibernate.jdbc.time_zone", "UTC"));
            return f;
        }

        @org.springframework.context.annotation.Bean
        org.springframework.transaction.PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
            return new JpaTransactionManager(emf);
        }
    }

    @BeforeAll
    static void boot() {
        String url = System.getenv("ODMS_BUSY_TEST_JDBC_URL");
        assumeTrue(url != null && !url.isBlank(), "ODMS_BUSY_TEST_JDBC_URL not set");
        if (url.contains(":5433/")) throw new IllegalStateException("Refusing dev database port 5433");
        context = new org.springframework.context.annotation.AnnotationConfigApplicationContext(Jpa.class);
        emf = context.getBean(EntityManagerFactory.class);
        jdbc = new JdbcTemplate(context.getBean(javax.sql.DataSource.class));
        jdbc.execute("CREATE TABLE IF NOT EXISTS resource_reservation_mutex(resource_id varchar(100) PRIMARY KEY)");
        tx = new TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
    }

    @AfterAll
    static void shutdown() {
        if (context != null) context.close();
    }

    <T> T repo(Class<T> type) {
        return new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(emf)).getRepository(type);
    }

    @BeforeEach
    void setUp() throws Exception {
        jdbc.execute("TRUNCATE resource_time_locks, user_schedules, missions, users, roles CASCADE");
        users = repo(UserRepository.class);
        schedules = repo(UserScheduleRepository.class);
        locks = repo(ResourceTimeLockRepository.class);
        missions = repo(MissionRepository.class);
        busy = new BusyScheduleServiceImpl(schedules, users, resolver, locks);
        Method m = BusyScheduleServiceImpl.class.getDeclaredMethod("setClock", Clock.class);
        m.setAccessible(true);
        m.invoke(busy, CLOCK);
        reservations = new ResourceReservationServiceImpl(jdbc, locks, schedules, resolver);

        roleId = UUID.randomUUID();
        jdbc.update("""
                insert into roles(role_id, code, name, is_system_role, is_active, created_at, updated_at)
                values (?, 'STAFF', 'Staff', true, true, now(), now())""", roleId);
        operatorId = insertUser("op@test.local");
        when(resolver.getCurrentUser()).thenAnswer(i -> inTx(() -> users.findById(operatorId).orElseThrow()));
        when(resolver.getCurrentUserId()).thenReturn("manager");
    }

    String insertUser(String email) {
        String id = UUID.randomUUID().toString();
        fillRequired("users", Map.of("id", id, "full_name", email, "email", email, "role_id", roleId,
                "is_active", true, "email_verified", true));
        return id;
    }

    /** Inserts a row filling every NOT NULL column without default; FK checks are skipped for the dummy order etc. */
    void fillRequired(String table, Map<String, Object> values) {
        var cols = jdbc.queryForList("""
                select column_name, data_type from information_schema.columns
                where table_schema = current_schema() and table_name = ? and is_nullable = 'NO' and column_default is null""",
                table);
        var names = new java.util.ArrayList<String>();
        var args = new java.util.ArrayList<Object>();
        values.forEach((k, v) -> { names.add(k); args.add(v); });
        for (var c : cols) {
            String name = (String) c.get("column_name");
            if (values.containsKey(name)) continue;
            names.add(name);
            args.add(switch ((String) c.get("data_type")) {
                case "boolean" -> false;
                case "integer", "bigint", "smallint", "numeric", "double precision", "real" -> 0;
                case "timestamp with time zone", "timestamp without time zone" -> java.sql.Timestamp.from(Instant.now());
                case "date" -> java.sql.Date.valueOf(LocalDate.now());
                case "uuid" -> UUID.randomUUID();
                default -> name.endsWith("_id") ? UUID.randomUUID().toString() : "X" + UUID.randomUUID().toString().substring(0, 8);
            });
        }
        String sql = "insert into " + table + "(" + String.join(",", names) + ") values ("
                + String.join(",", names.stream().map(n -> "?").toList()) + ")";
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update(sql, args.toArray());
        });
    }

    Mission insertMission(Instant start, Instant end) {
        String id = UUID.randomUUID().toString();
        fillRequired("missions", Map.of("id", id, "status", "READY", "mission_purpose", "ORIGINAL",
                "scheduled_start_at", java.sql.Timestamp.from(start), "scheduled_end_at", java.sql.Timestamp.from(end)));
        return inTx(() -> missions.findById(id).orElseThrow());
    }

    <T> T inTx(Supplier<T> work) {
        return tx.execute(s -> work.get());
    }

    Instant vn(String date, int hour) {
        return LocalDate.parse(date).atTime(hour, 0).atZone(VN).toInstant();
    }

    @Test
    void busySessionsPersistAndWeeklyLimitIsEnforcedInDatabase() {
        inTx(() -> busy.create(new BusyScheduleRequest(LocalDate.parse("2026-11-12"), BusySession.MORNING, "khám bệnh")));
        inTx(() -> busy.create(new BusyScheduleRequest(LocalDate.parse("2026-11-13"), BusySession.AFTERNOON, null)));
        inTx(() -> busy.create(new BusyScheduleRequest(LocalDate.parse("2026-11-14"), BusySession.EVENING, null)));
        var fourth = inTx(() -> busy.create(new BusyScheduleRequest(LocalDate.parse("2026-11-15"), BusySession.MORNING, null)));

        assertThatThrownBy(() -> inTx(() -> busy.create(
                new BusyScheduleRequest(LocalDate.parse("2026-11-15"), BusySession.EVENING, null))))
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.BUSY_SCHEDULE_WEEKLY_LIMIT_EXCEEDED));
        assertThat(jdbc.queryForObject("select count(*) from user_schedules where staff_id=? and schedule_type='LEAVE' and status='SCHEDULED'",
                Long.class, operatorId)).isEqualTo(4);

        inTx(() -> { busy.delete(fourth.id()); return null; });
        assertThat(jdbc.queryForObject("select status from user_schedules where id=?", String.class, fourth.id())).isEqualTo("CANCELLED");
        inTx(() -> busy.create(new BusyScheduleRequest(LocalDate.parse("2026-11-15"), BusySession.EVENING, null)));

        var week = inTx(() -> busy.getMyWeek(LocalDate.parse("2026-11-09")));
        assertThat(week.usedSessions()).isEqualTo(4);
        assertThat(week.items()).extracting(i -> i.session())
                .containsExactly(BusySession.MORNING, BusySession.AFTERNOON, BusySession.EVENING, BusySession.EVENING);
    }

    @Test
    void assigningBusyOperatorIsRejectedAndNoLockIsWritten() {
        inTx(() -> busy.create(new BusyScheduleRequest(LocalDate.parse("2026-11-12"), BusySession.MORNING, null)));
        Mission mission = insertMission(vn("2026-11-12", 9), vn("2026-11-12", 11));

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> reservations.reserve(mission, operatorId, true)))
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.STAFF_BUSY_IN_SLOT));
        assertThat(jdbc.queryForObject("select count(*) from resource_time_locks", Long.class)).isZero();
    }

    @Test
    void assigningOperatorOutsideBusySessionSucceeds() {
        inTx(() -> busy.create(new BusyScheduleRequest(LocalDate.parse("2026-11-12"), BusySession.MORNING, null)));
        Mission mission = insertMission(vn("2026-11-12", 13), vn("2026-11-12", 15)); // afternoon

        tx.executeWithoutResult(s -> reservations.reserve(mission, operatorId, true));
        assertThat(jdbc.queryForObject("select count(*) from resource_time_locks where resource_id=? and resource_type='STAFF'",
                Long.class, operatorId)).isEqualTo(1);
    }

    @Test
    void cancelledBusySessionNoLongerBlocksAssignment() {
        var s = inTx(() -> busy.create(new BusyScheduleRequest(LocalDate.parse("2026-11-12"), BusySession.MORNING, null)));
        inTx(() -> { busy.delete(s.id()); return null; });
        Mission mission = insertMission(vn("2026-11-12", 9), vn("2026-11-12", 11));

        tx.executeWithoutResult(t -> reservations.reserve(mission, operatorId, true));
        assertThat(jdbc.queryForObject("select count(*) from resource_time_locks", Long.class)).isEqualTo(1);
    }

    @Test
    void reschedulingMissionIntoBusySessionIsRejected() {
        Mission mission = insertMission(vn("2026-11-12", 13), vn("2026-11-12", 15));
        tx.executeWithoutResult(t -> reservations.reserve(mission, operatorId, true));
        inTx(() -> busy.create(new BusyScheduleRequest(LocalDate.parse("2026-11-13"), BusySession.MORNING, null)));

        assertThatThrownBy(() -> tx.executeWithoutResult(t -> reservations.revalidateSchedule(
                mission, vn("2026-11-13", 8), vn("2026-11-13", 10))))
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.STAFF_BUSY_IN_SLOT));
        assertThat(jdbc.queryForObject("select start_time from resource_time_locks", java.sql.Timestamp.class).toInstant())
                .isEqualTo(vn("2026-11-12", 13));
    }

    @Test
    void operatorCannotMarkBusyWhereTheyAlreadyHoldAMissionSlot() {
        Mission mission = insertMission(vn("2026-11-12", 9), vn("2026-11-12", 11));
        tx.executeWithoutResult(t -> reservations.reserve(mission, operatorId, true));

        assertThatThrownBy(() -> inTx(() -> busy.create(
                new BusyScheduleRequest(LocalDate.parse("2026-11-12"), BusySession.MORNING, null))))
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.BUSY_SCHEDULE_MISSION_CONFLICT));
        assertThat(jdbc.queryForObject("select count(*) from user_schedules", Long.class)).isZero();
    }

    @Test
    void availableOperatorListExcludesBusyOperator() {
        String freeId = insertUser("free@test.local");
        inTx(() -> busy.create(new BusyScheduleRequest(LocalDate.parse("2026-11-12"), BusySession.MORNING, null)));
        Mission mission = insertMission(vn("2026-11-12", 9), vn("2026-11-12", 11));
        var directory = new com.ondemandmonitoring.user.service.impl.OperatorDirectoryServiceImpl(
                users, missions, locks, schedules);
        var available = inTx(() -> directory.getAvailableOperators(mission.getId()));
        assertThat(available).extracting(a -> a.getId()).containsExactly(freeId);
    }
}
