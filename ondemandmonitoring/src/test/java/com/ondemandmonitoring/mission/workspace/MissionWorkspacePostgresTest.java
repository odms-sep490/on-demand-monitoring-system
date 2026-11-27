package com.ondemandmonitoring.mission.workspace;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.enums.MediaStatus;
import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.enums.*;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import jakarta.persistence.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.hibernate.jpa.boot.spi.IntegratorProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL and production Hibernate listeners, confined to a random disposable schema. */
@EnabledIfEnvironmentVariable(named="ODMS_CHECKLIST_TEST_JDBC_URL",
        matches="jdbc:postgresql://127[.]0[.]0[.]1:(?!5433/)[0-9]+/odms_checklist_verification")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MissionWorkspacePostgresTest {
    final String schema = "checklist_verify_" + UUID.randomUUID().toString().replace("-", "");
    final List<MissionWorkspaceEvents.Changed> delivered = new ArrayList<>();
    LocalContainerEntityManagerFactoryBean factory;
    EntityManager em;
    JdbcTemplate jdbc;
    TransactionTemplate tx;
    MissionWorkspaceVersionService versions;
    String missionId, resultId, mediaId, secondMediaId;
    MissionDeviceAssignment assignment;

    @BeforeAll void setup() throws Exception {
        String url = System.getenv("ODMS_CHECKLIST_TEST_JDBC_URL");
        if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:(?!5433/)[0-9]+/odms_checklist_verification"))
            throw new IllegalStateException("Dedicated local verification database required");
        String user = System.getenv().getOrDefault("ODMS_CHECKLIST_TEST_DB_USER", "checklist_test");
        String password = System.getenv().getOrDefault("ODMS_CHECKLIST_TEST_DB_PASSWORD", "checklist_test_only");
        var admin = new DriverManagerDataSource(url, user, password);
        new JdbcTemplate(admin).execute("create schema " + schema);
        // Public contains extension types in this dedicated verification database.
        var ds = new DriverManagerDataSource(url + "?currentSchema=" + schema + ",public", user, password);
        jdbc = new JdbcTemplate(ds);
        var events = new MissionWorkspaceEvents(event -> delivered.add((MissionWorkspaceEvents.Changed) event));
        var listener = new MissionWorkspaceWriteListener(jdbc, events);
        factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(ds);
        factory.setPackagesToScan("com.ondemandmonitoring");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create",
                "hibernate.default_schema", schema, "hibernate.jdbc.time_zone", "UTC",
                "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl",
                "hibernate.integrator_provider", (IntegratorProvider) () -> List.of(
                        MissionWorkspaceHibernateConfiguration.integrator(listener))));
        factory.afterPropertiesSet();
        var emf = factory.getObject();
        em = SharedEntityManagerCreator.createSharedEntityManager(emf);
        tx = new TransactionTemplate(new JpaTransactionManager(emf));
        // Canonical schema constraints, not hand-invented tables for the version query.
        jdbc.execute(resource("bootstrap_after_hibernate.sql"));
        jdbc.execute(resource("verify_current_schema.sql"));
        var resolver = mock(AuthenticatedUserResolver.class);
        versions = new MissionWorkspaceVersionService(new NamedParameterJdbcTemplate(ds), resolver);
        tx.executeWithoutResult(status -> seed(resolver));
    }

    String resource(String name) throws Exception {
        return new ClassPathResource("seeddata/migrations/" + name).getContentAsString(StandardCharsets.UTF_8)
                .replace("public.", schema + ".")
                .replace("table_schema='public'", "table_schema='" + schema + "'")
                .replace("schemaname='public'", "schemaname='" + schema + "'")
                .replace("search_path = public, pg_catalog", "search_path = " + schema + ", public, pg_catalog");
    }

    @BeforeEach void clearEvents() {
        tx.executeWithoutResult(status -> {
            var result = em.find(MissionResult.class, resultId);
            result.getMediaFiles().clear(); result.setApprovalStatus(MissionResultApprovalStatus.DRAFT);
        });
        delivered.clear();
    }

    @AfterAll void cleanup() {
        if (factory != null) factory.destroy();
        if (jdbc != null && schema.matches("checklist_verify_[a-f0-9]{32}"))
            jdbc.execute("drop schema " + schema + " cascade");
    }

    void persist(BaseEntity entity) { entity.setCreatedAt(Instant.now()); em.persist(entity); }

    void seed(AuthenticatedUserResolver resolver) {
        var role = new com.ondemandmonitoring.role.domain.Role();
        role.setCode(com.ondemandmonitoring.role.enumeration.RoleCode.MANAGER); role.setName("QA"); role.setActive(true); em.persist(role);
        var user = new com.ondemandmonitoring.user.domain.User();
        user.setFullName("QA"); user.setEmail("workspace@example.test"); user.setRole(role); user.setIsActive(true); persist(user);
        when(resolver.getCurrentUser()).thenReturn(user);
        var service = new com.ondemandmonitoring.service.domain.Service(); service.setName("QA"); persist(service);
        var time = new com.ondemandmonitoring.warehouse.domain.PreferredTime();
        time.setCode(com.ondemandmonitoring.warehouse.enums.PreferredTimeCode.MORNING); time.setName("QA");
        time.setStartTime(LocalTime.of(8, 0)); time.setEndTime(LocalTime.of(9, 0)); persist(time);
        var order = new com.ondemandmonitoring.order.domain.Order();
        order.setTitle("QA"); order.setCustomer(user); order.setService(service); order.setPreferredTime(time);
        order.setDataRetentionDays(7); order.setOrderStatus(com.ondemandmonitoring.order.enums.OrderStatus.APPROVED);
        order.setExecutionPlan(null); order.setFlightCompliance(null); order.setFlightAreaReview(null); persist(order);
        var mission = new Mission(); mission.setMissionCode("QA-WORKSPACE"); mission.setOrder(order);
        mission.setStatus(MissionStatus.PENDING_REVIEW); persist(mission); missionId = mission.getId();
        var model = new com.ondemandmonitoring.device.domain.DeviceModel(); model.setCode("QA"); model.setModelName("QA"); persist(model);
        var device = new com.ondemandmonitoring.device.domain.Device(); device.setSerialNumber("QA"); device.setName("QA");
        device.setDeviceModel(model); device.setStatus(com.ondemandmonitoring.device.enums.DeviceStatus.values()[0]); persist(device);
        assignment = new MissionDeviceAssignment(); assignment.setMission(mission); assignment.setDevice(device); persist(assignment);
        mediaId = media().getId(); secondMediaId = media().getId();
        var result = new MissionResult(); result.setMission(mission); result.setStatus(MissionResultStatus.COMPLETED);
        result.setApprovalStatus(MissionResultApprovalStatus.DRAFT); result.setCompletedAt(Instant.now()); result.setMediaCount(0);
        persist(result); resultId = result.getId(); em.flush();
    }

    MediaAsset media() {
        var asset = new MediaAsset(); asset.setMission(em.getReference(Mission.class, missionId));
        asset.setDeviceAssignment(em.getReference(MissionDeviceAssignment.class, assignment.getId()));
        asset.setType("IMAGE"); asset.setStorageProvider("S3"); asset.setOriginalFileName("qa.jpg");
        asset.setContentType("image/jpeg"); asset.setFileSize(100L); asset.setCapturedAt(Instant.now());
        asset.setS3Bucket("qa"); asset.setS3Key("qa/" + UUID.randomUUID()); asset.setS3Url("s3://qa/" + asset.getS3Key());
        asset.setMediaStatus(MediaStatus.AVAILABLE); persist(asset); return asset;
    }

    WorkspaceVersion version() { return tx.execute(status -> versions.get(missionId)); }

    @Test void actualSchemaSqlIsStableAndNativeMediaWritesInvalidateTokens() {
        var before = version(); assertEquals(before, version());
        assertEquals(64, before.checklist().length());
        tx.executeWithoutResult(status -> jdbc.update("update drone_media set original_file_name=? where id=?", "native.jpg", mediaId));
        var after = version();
        assertNotEquals(before.media(), after.media()); assertNotEquals(before.checklist(), after.checklist());
        // Empty result selection falls back to every mission asset.
        assertNotEquals(before.result(), after.result()); assertEquals(before.corrections(), after.corrections());
        assertEquals(after, version());
    }

    @Test void collectionAttachmentFlushPublishesOnlyAfterCommitAndRollbackRestoresToken() {
        tx.executeWithoutResult(status -> em.find(MissionResult.class, resultId).getMediaFiles().clear());
        delivered.clear(); var before = version();
        tx.executeWithoutResult(status -> {
            var result = em.find(MissionResult.class, resultId);
            result.getMediaFiles().add(em.getReference(MediaAsset.class, mediaId)); em.flush();
            assertTrue(delivered.isEmpty(), "Flush must not publish an uncommitted event");
        });
        assertEquals(1, delivered.size()); assertEquals(missionId, delivered.getFirst().missionId());
        assertTrue(delivered.getFirst().components().contains("result"));
        var attached = version(); assertNotEquals(before.result(), attached.result());
        delivered.clear();
        tx.executeWithoutResult(status -> {
            em.find(MissionResult.class, resultId).getMediaFiles().clear(); em.flush();
            assertTrue(delivered.isEmpty()); status.setRollbackOnly();
        });
        assertTrue(delivered.isEmpty()); assertEquals(attached, version());
        tx.executeWithoutResult(status -> jdbc.update("update drone_media set original_file_name=? where id=?", "unselected.jpg", secondMediaId));
        assertEquals(attached.result(), version().result(), "Explicit bundle excludes other mission media");
    }

    @Test void resultApprovalCommitTimeFlushPublishesAndRollbackDoesNot() {
        var before = version();
        tx.executeWithoutResult(status -> {
            em.find(MissionResult.class, resultId).setApprovalStatus(MissionResultApprovalStatus.APPROVED);
            assertTrue(delivered.isEmpty()); // No explicit flush: listener runs during commit.
        });
        assertEquals(1, delivered.size()); assertTrue(delivered.getFirst().components().containsAll(Set.of("result", "permissions")));
        var approved = version(); assertNotEquals(before.result(), approved.result());
        assertNotEquals(before.permissions(), approved.permissions()); delivered.clear();
        tx.executeWithoutResult(status -> {
            em.find(MissionResult.class, resultId).setApprovalStatus(MissionResultApprovalStatus.REJECTED);
            em.flush(); assertTrue(delivered.isEmpty()); status.setRollbackOnly();
        });
        assertTrue(delivered.isEmpty()); assertEquals(approved, version());
    }

    @Test void optionalQualityTablesMayBeAbsentAndLeaseOnlyWritesKeepTokenStable() throws Exception {
        // All DDL rolls back; other tests retain exactly the canonical fixture schema.
        tx.executeWithoutResult(status -> {
            jdbc.execute("drop table if exists image_quality_event, image_quality_review, image_quality_check cascade");
            assertEquals(version(), version());
            try {
                jdbc.execute(new ClassPathResource("seeddata/migrations/add_image_quality.sql").getContentAsString(StandardCharsets.UTF_8));
            } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
            UUID id = UUID.randomUUID();
            jdbc.update("insert into image_quality_check(id,media_id,mission_id,requested_by,request_key,customer_request,profile) values (?,?,?,?,?,false,'DETAIL')",
                    id, mediaId, missionId, "qa", UUID.randomUUID());
            var pending = version();
            jdbc.update("update image_quality_check set lease=?,lease_at=now() where id=?", UUID.randomUUID(), id);
            assertEquals(pending, version());
            jdbc.update("update image_quality_check set status='COMPLETED',decision='PASS' where id=?", id);
            var completed = version(); assertNotEquals(pending.checklist(), completed.checklist());
            jdbc.update("insert into image_quality_review(id,check_id,reviewer_id,decision) values (?,?,'qa','FAIL')", UUID.randomUUID(), id);
            assertNotEquals(completed.checklist(), version().checklist());
            jdbc.execute("drop table image_quality_review"); assertNotNull(version());
            status.setRollbackOnly();
        });
    }

    @Test void notifierRecipientsRespectRolesAssignmentHistoryAndPreviousViewerHints() {
        tx.executeWithoutResult(status -> {
            var roles = new HashMap<com.ondemandmonitoring.role.enumeration.RoleCode, com.ondemandmonitoring.role.domain.Role>();
            for (var code : List.of(com.ondemandmonitoring.role.enumeration.RoleCode.MANAGER,
                    com.ondemandmonitoring.role.enumeration.RoleCode.ADMIN,
                    com.ondemandmonitoring.role.enumeration.RoleCode.STAFF,
                    com.ondemandmonitoring.role.enumeration.RoleCode.CUSTOMER)) {
                var existing = em.createQuery("from Role where code=:code", com.ondemandmonitoring.role.domain.Role.class)
                        .setParameter("code", code).getResultList();
                var role = existing.isEmpty() ? new com.ondemandmonitoring.role.domain.Role() : existing.getFirst();
                if (existing.isEmpty()) { role.setCode(code); role.setName(code.name()); role.setActive(true); em.persist(role); }
                roles.put(code, role);
            }
            String revokedId = null;
            for (String label : List.of("manager", "admin", "current", "history", "unrelated", "customer", "disabled", "revoked")) {
                var code = switch (label) {
                    case "manager" -> com.ondemandmonitoring.role.enumeration.RoleCode.MANAGER;
                    case "admin" -> com.ondemandmonitoring.role.enumeration.RoleCode.ADMIN;
                    case "customer" -> com.ondemandmonitoring.role.enumeration.RoleCode.CUSTOMER;
                    default -> com.ondemandmonitoring.role.enumeration.RoleCode.STAFF;
                };
                var user = new com.ondemandmonitoring.user.domain.User();
                user.setFullName(label); user.setEmail(label + "@recipients.test"); user.setRole(roles.get(code));
                user.setIsActive(!label.equals("disabled") && !label.equals("revoked")); persist(user);
                var identity = new com.ondemandmonitoring.user.domain.UserIdentity(); identity.setUser(user);
                identity.setProvider(com.ondemandmonitoring.user.enumeration.IdentityProvider.LOCAL);
                identity.setCognitoUsername(label); identity.setCognitoSub("subject-" + label); persist(identity);
                if (Set.of("current", "history", "disabled").contains(label)) {
                    var staff = new MissionStaffAssignment(); staff.setStaff(user);
                    staff.setMission(em.getReference(Mission.class, missionId)); staff.setIsCurrent(!label.equals("history"));
                    if (label.equals("history")) { staff.setReleasedAt(Instant.now()); staff.setReleaseReason("QA history"); }
                    persist(staff);
                }
                if (label.equals("revoked")) revokedId = user.getId();
            }
            em.flush();
            var messaging = mock(org.springframework.messaging.simp.SimpMessagingTemplate.class);
            var notifier = new MissionWorkspaceNotifier(new NamedParameterJdbcTemplate(jdbc), messaging);
            notifier.notify(new MissionWorkspaceEvents.Changed(missionId, Set.of("permissions"), Set.of(revokedId)));
            var subjects = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(messaging, times(5)).convertAndSendToUser(subjects.capture(), eq("/queue/mission-events"), any(Object.class));
            assertEquals(Set.of("subject-manager", "subject-admin", "subject-current", "subject-history", "subject-revoked"),
                    new HashSet<>(subjects.getAllValues()));
            status.setRollbackOnly();
        });
    }

    @Test void childInspectionEditsInvalidatePermissionsWithoutChangingParentRows() {
        String[] ids = tx.execute(status -> {
            var pre = new com.ondemandmonitoring.devicecheck.domain.PersistedPreDeviceCheck();
            pre.setMission(em.getReference(Mission.class, missionId)); pre.setTotalChecks(1); pre.setStartedAt(Instant.now()); persist(pre);
            var preItem = new com.ondemandmonitoring.devicecheck.domain.PersistedPreDeviceCheckItem();
            preItem.setPreDeviceCheck(pre); preItem.setCheckType("QA"); preItem.setCheckName("QA");
            preItem.setDescription("QA"); preItem.setCategory("QA");
            preItem.setSource(com.ondemandmonitoring.devicecheck.enums.PreflightCheckSource.values()[0]);
            preItem.setCheckLevel(com.ondemandmonitoring.devicecheck.enums.PreDeviceCheckLevel.INFO); persist(preItem);
            var post = new com.ondemandmonitoring.devicecheck.domain.PersistedPostDeviceCheck();
            post.setMission(em.getReference(Mission.class, missionId)); post.setTotalChecks(1); post.setStartedAt(Instant.now()); persist(post);
            var postItem = new com.ondemandmonitoring.devicecheck.domain.PersistedPostDeviceCheckItem();
            postItem.setPostDeviceCheck(post); postItem.setCheckType("QA"); postItem.setCheckName("QA");
            postItem.setCheckLevel(com.ondemandmonitoring.devicecheck.enums.DeviceCheckLevel.INFO); persist(postItem); em.flush();
            return new String[]{pre.getId(), preItem.getId(), post.getId(), postItem.getId()};
        });
        var preParent = jdbc.queryForMap("select * from persisted_pre_device_check where id=?", ids[0]);
        var postParent = jdbc.queryForMap("select * from persisted_post_device_check where id=?", ids[2]);
        var before = version(); delivered.clear();
        tx.executeWithoutResult(status -> em.find(com.ondemandmonitoring.devicecheck.domain.PersistedPreDeviceCheckItem.class, ids[1]).setMessage("Updated preflight note"));
        var changedPre = version();
        assertNotEquals(before.permissions(), changedPre.permissions());
        assertEquals(preParent, jdbc.queryForMap("select * from persisted_pre_device_check where id=?", ids[0]));
        assertEquals(1, delivered.size()); assertEquals(Set.of("permissions"), delivered.getFirst().components());
        delivered.clear();
        tx.executeWithoutResult(status -> em.find(com.ondemandmonitoring.devicecheck.domain.PersistedPostDeviceCheckItem.class, ids[3]).setMessage("Updated postflight note"));
        var changedPost = version();
        assertNotEquals(changedPre.permissions(), changedPost.permissions());
        assertEquals(postParent, jdbc.queryForMap("select * from persisted_post_device_check where id=?", ids[2]));
        assertEquals(1, delivered.size()); assertEquals(Set.of("permissions"), delivered.getFirst().components());
        assertEquals(before.checklist(), changedPost.checklist()); assertEquals(before.result(), changedPost.result());
        assertEquals(changedPost, version());
    }
}
