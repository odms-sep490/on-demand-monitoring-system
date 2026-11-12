package com.ondemandmonitoring.media.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.media.domain.*;
import com.ondemandmonitoring.media.enums.*;
import com.ondemandmonitoring.media.repository.MediaAssetRepository;
import com.ondemandmonitoring.mission.domain.*;
import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named="ODMS_MEDIA_REFACTOR_JDBC_URL", matches="jdbc:postgresql://127[.]0[.]0[.]1:(?!5433/)[0-9]+/odms_media_refactor_verification")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MediaDetailsPostgresTest {
    EntityManagerFactory emf; LocalContainerEntityManagerFactoryBean factory;
    EntityManager em; TransactionTemplate tx; JdbcTemplate jdbc; MediaAssetRepository media;
    String missionId; MissionDeviceAssignment assignment;

    @BeforeAll void setup() throws Exception {
        String url=System.getenv("ODMS_MEDIA_REFACTOR_JDBC_URL");
        if (url==null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:(?!5433/)[0-9]+/odms_media_refactor_verification")) throw new IllegalStateException("Disposable database required");
        var ds=new DriverManagerDataSource(url,"postgres","media_refactor_test_only"); jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS postgis"); jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
        factory=new LocalContainerEntityManagerFactoryBean(); factory.setDataSource(ds);
        factory.setPackagesToScan("com.ondemandmonitoring"); factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","create", "hibernate.generate_statistics","true",
                "hibernate.jdbc.time_zone","UTC", "hibernate.hbm2ddl.halt_on_error","true"));
        factory.afterPropertiesSet(); emf=factory.getObject();
        tx=new TransactionTemplate(new JpaTransactionManager(emf));
        em=SharedEntityManagerCreator.createSharedEntityManager(emf);
        var repositories=new JpaRepositoryFactory(em);
        media=repositories.getRepository(MediaAssetRepository.class);
        repositories.getRepository(com.ondemandmonitoring.mission.repository.MissionChecklistEvidenceRepository.class);
        repositories.getRepository(com.ondemandmonitoring.delivery.repository.ResultReviewRoundEvidenceRepository.class);
        String bootstrap = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/seeddata/migrations/bootstrap_after_hibernate.sql"));
        // Full canonical fresh bootstrap, then rerun before fixtures to verify idempotency.
        jdbc.execute(bootstrap);
        jdbc.execute(bootstrap);
        jdbc.execute(java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/seeddata/migrations/verify_current_schema.sql")));
        tx.executeWithoutResult(status -> seed());
    }
    @AfterAll void close() { if(factory!=null)factory.destroy(); }

    void seed() {
        var role=new com.ondemandmonitoring.role.domain.Role(); role.setCode(com.ondemandmonitoring.role.enumeration.RoleCode.values()[0]); role.setName("QA role"); em.persist(role);
        var user=new com.ondemandmonitoring.user.domain.User(); user.setFullName("QA"); user.setEmail("media-qa@example.test"); user.setRole(role); persist(user);
        var service=new com.ondemandmonitoring.service.domain.Service(); service.setName("QA service"); persist(service);
        var time=new com.ondemandmonitoring.warehouse.domain.PreferredTime(); time.setCode(com.ondemandmonitoring.warehouse.enums.PreferredTimeCode.values()[0]); time.setName("QA"); time.setStartTime(LocalTime.of(8,0)); time.setEndTime(LocalTime.of(9,0)); persist(time);
        var order=new com.ondemandmonitoring.order.domain.Order(); order.setTitle("QA"); order.setCustomer(user); order.setService(service); order.setPreferredTime(time); order.setDataRetentionDays(7); order.setOrderStatus(com.ondemandmonitoring.order.enums.OrderStatus.values()[0]); order.setExecutionPlan(null); order.setFlightCompliance(null); order.setFlightAreaReview(null); persist(order);
        var mission=new Mission(); mission.setMissionCode("QA-MEDIA"); mission.setOrder(order); mission.setStatus(com.ondemandmonitoring.mission.enums.MissionStatus.PENDING_REVIEW); mission.setScheduledStartAt(Instant.now()); mission.setScheduledEndAt(Instant.now().plusSeconds(3600)); persist(mission); missionId=mission.getId();
        var model=new com.ondemandmonitoring.device.domain.DeviceModel(); model.setCode("QA-MODEL"); model.setModelName("QA"); persist(model);
        var device=new com.ondemandmonitoring.device.domain.Device(); device.setSerialNumber("QA-SERIAL"); device.setName("QA"); device.setDeviceModel(model); device.setStatus(com.ondemandmonitoring.device.enums.DeviceStatus.values()[0]); persist(device);
        assignment=new MissionDeviceAssignment(); assignment.setMission(mission); assignment.setDevice(device); persist(assignment);
    }
    void persist(BaseEntity value) { value.setCreatedAt(Instant.now()); em.persist(value); }
    MediaAsset create(boolean capture) {
        var asset=new MediaAsset(); asset.setDeviceAssignment(em.getReference(MissionDeviceAssignment.class,assignment.getId())); asset.setMission(em.getReference(Mission.class,missionId));
        asset.setType("IMAGE"); asset.setStorageProvider("S3"); asset.setOriginalFileName("qa.jpg"); asset.setContentType("image/jpeg"); asset.setFileSize(100L); asset.setCapturedAt(Instant.now());
        asset.setS3Bucket("qa"); asset.setS3Key("qa/"+UUID.randomUUID()); asset.setS3Url("s3://qa/"+asset.getS3Key()); asset.setMediaStatus(MediaStatus.AVAILABLE); asset.setValidatedAt(Instant.now());
        if(capture) {asset.setCaptureTimeSource("STAFF_CONFIRMED"); asset.setCaptureTimeConfirmedBy("qa"); asset.setCaptureTimeConfirmationReason("flight log");}
        persist(asset); em.flush(); return asset;
    }
    @Test void currentSchemaVerifierAcceptsMediaDetailContract() throws Exception {
        String verifier = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/seeddata/migrations/verify_current_schema.sql"));
        int start = verifier.indexOf("    FOR meta IN SELECT * FROM (VALUES\n        ('media_capture_metadata'");
        // Resource files may retain Windows line endings.
        if (start < 0) { verifier = verifier.replace("\r\n", "\n"); start = verifier.indexOf("    FOR meta IN SELECT * FROM (VALUES\n        ('media_capture_metadata'"); }
        int end = verifier.indexOf("    RAISE NOTICE 'Current Order/Mission schema verification PASS'", start);
        assertThat(start).isNotNegative(); assertThat(end).isGreaterThan(start);
        jdbc.execute("DO $$ DECLARE meta record; actual text; BEGIN\n" + verifier.substring(start, end) + "END $$;");
    }
    @Test void ownsSharedIdentityAndWritesOnlyNewTables() {
        String id=tx.execute(status -> create(true).getId());
        assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema='public' and table_name='drone_media'",Integer.class)).isEqualTo(37);
        assertThat(jdbc.queryForObject("select capture_time_source from media_capture_metadata where media_id=?",String.class,id)).isEqualTo("STAFF_CONFIRMED");
        assertThat(jdbc.queryForObject("select derivative_status from media_derivatives where media_id=?",String.class,id)).isEqualTo("PENDING");
        tx.executeWithoutResult(status -> {var asset=media.findByIdForUpdate(id).orElseThrow(); asset.setDerivativeStatus(MediaDerivativeStatus.READY); asset.setThumbnailKey("thumb"); asset.setMetadataCapturedAt(asset.getCapturedAt()); em.flush();});
        assertThat(jdbc.queryForObject("select thumbnail_key from media_derivatives where media_id=?",String.class,id)).isEqualTo("thumb");
    }
    @Test void pageFetchDoesNotProducePerMediaDetailQueries() {
        tx.executeWithoutResult(status -> {for(int i=0;i<16;i++)create(true);});
        var stats=emf.unwrap(SessionFactory.class).getStatistics(); stats.clear();
        tx.executeWithoutResult(status -> {
            var page=media.findMissionCandidates(missionId,PageRequest.of(0,10));
            assertThat(page.getContent()).hasSize(10);
            page.forEach(asset -> {asset.getCaptureTimeSource(); asset.getDerivativeStatus(); asset.getThumbnailKey();});
        });
        assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(2L);
    }
    @Test void readDoesNotCreateMissingCaptureDetails() {
        String id=tx.execute(status -> create(false).getId());
        tx.executeWithoutResult(status -> assertThat(media.findById(id).orElseThrow().getMetadataCapturedAt()).isNull());
        assertThat(jdbc.queryForObject("select count(*) from media_capture_metadata where media_id=?",Integer.class,id)).isZero();
    }
    @Test void derivativeQueueUsesNewTableState() {
        String id=tx.execute(status -> create(false).getId());
        tx.executeWithoutResult(status -> assertThat(media.findDerivativeCandidates("IMAGE",Instant.now(),Instant.now(),5,PageRequest.of(0,100))).contains(id));
        tx.executeWithoutResult(status -> media.findByIdForUpdate(id).orElseThrow().setDerivativeStatus(MediaDerivativeStatus.READY));
        tx.executeWithoutResult(status -> assertThat(media.findDerivativeCandidates("IMAGE",Instant.now(),Instant.now(),5,PageRequest.of(0,100))).doesNotContain(id));
    }
    @Test void detailVersionRejectsStaleDetachedUpdates() {
        String id=tx.execute(status -> create(true).getId());
        var stale=tx.execute(status -> media.findById(id).orElseThrow().getDerivative());
        tx.executeWithoutResult(status -> {var asset=media.findByIdForUpdate(id).orElseThrow(); asset.setThumbnailKey("current"); em.flush();});
        stale.setThumbnailKey("stale");
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {em.merge(stale); em.flush();}))
                .isInstanceOf(OptimisticLockException.class);
        assertThat(jdbc.queryForObject("select thumbnail_key from media_derivatives where media_id=?",String.class,id)).isEqualTo("current");
    }
    @Test void rootLockSerializesConcurrentCreationOfCaptureDetails() throws Exception {
        String id=tx.execute(status -> create(false).getId());
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Void> update=() -> {start.await(); tx.executeWithoutResult(status -> {var asset=media.findByIdForUpdate(id).orElseThrow(); asset.setCaptureTimeSource("STAFF_CONFIRMED"); asset.setCaptureTimeConfirmedBy(Thread.currentThread().getName()); em.flush();}); return null;};
            var first=pool.submit(update); var second=pool.submit(update); start.countDown(); first.get(15,TimeUnit.SECONDS); second.get(15,TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("select count(*) from media_capture_metadata where media_id=?",Integer.class,id)).isEqualTo(1);
    }
    String createStaffAsset() {
        return tx.execute(status -> {
            var asset=create(true);
            var attempt=new MediaUploadAttempt(); attempt.setMedia(asset); attempt.setAttemptNumber(1);
            attempt.setUploadChannel(MediaUploadChannel.STAFF_FILE); attempt.setStatus(UploadAttemptStatus.SUCCEEDED);
            attempt.setStorageKey(asset.getS3Key()+"/attempt"); attempt.setExpiresAt(Instant.now().minusSeconds(3600)); persist(attempt);
            em.flush(); return asset.getId();
        });
    }
    com.ondemandmonitoring.media.service.impl.MediaRemovalServiceImpl removals(boolean failAudit) {
        var repositories=new JpaRepositoryFactory(em);
        var access=mock(com.ondemandmonitoring.mission.service.IMissionMediaAccessService.class);
        when(access.authorizeOperator(missionId)).thenReturn(new com.ondemandmonitoring.mission.dto.response.MissionMediaContext(missionId,true));
        var guard=mock(com.ondemandmonitoring.mission.service.IMissionMediaEvidenceGuard.class);
        var missions=repositories.getRepository(com.ondemandmonitoring.mission.repository.MissionRepository.class);
        doAnswer(call -> {missions.findByIdForUpdate(missionId).orElseThrow(); return null;}).when(guard).lockMission(missionId);
        var resultReferences=repositories.getRepository(com.ondemandmonitoring.mission.repository.MissionResultRepository.class);
        var evidenceReferences=repositories.getRepository(com.ondemandmonitoring.mission.repository.MissionChecklistEvidenceRepository.class);
        when(guard.hasEvidenceOrResultReferences(org.mockito.ArgumentMatchers.anyString())).thenAnswer(call ->
                resultReferences.existsByMediaFiles_Id(call.getArgument(0)) || evidenceReferences.existsByMediaAsset_IdAndDetachedAtIsNull(call.getArgument(0)));
        var user=mock(com.ondemandmonitoring.user.service.AuthenticatedUserResolver.class);when(user.getCurrentUserId()).thenReturn("qa-staff");
        com.ondemandmonitoring.media.service.IMediaAuditService audit=(asset,attempt,actor,action,detail) -> {
            if (failAudit) throw new IllegalStateException("audit unavailable");
            var row=new MediaAuditLog();row.setMedia(asset);row.setActorId(actor);row.setAction(action.name());row.setDetail(detail);persist(row);
        };
        return new com.ondemandmonitoring.media.service.impl.MediaRemovalServiceImpl(access,guard,media,
                repositories.getRepository(com.ondemandmonitoring.media.repository.MediaUploadAttemptRepository.class),
                repositories.getRepository(com.ondemandmonitoring.media.repository.ManualUploadTaskRepository.class),
                repositories.getRepository(com.ondemandmonitoring.delivery.repository.ResultReviewRoundEvidenceRepository.class),audit,user);
    }
    @Test void concurrentRemovalsCommitOneAuditAndHideTheAssetWithoutDeletingHistory() throws Exception {
        String id=createStaffAsset(); var service=removals(false);
        Long version=jdbc.queryForObject("select version from drone_media where id=?",Long.class,id);
        var request=new com.ondemandmonitoring.media.dto.request.RemoveMediaRequest(version,"wrong file");
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Void> remove=() -> {start.await();tx.executeWithoutResult(status -> service.remove(missionId,id,request));return null;};
            var first=pool.submit(remove);var second=pool.submit(remove);start.countDown();first.get(15,TimeUnit.SECONDS);second.get(15,TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("select media_status from drone_media where id=?",String.class,id)).isEqualTo("REMOVED");
        assertThat(jdbc.queryForObject("select count(*) from media_audit_logs where media_id=? and action='REMOVED'",Integer.class,id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from media_upload_attempts where media_id=?",Integer.class,id)).isEqualTo(1);
        tx.executeWithoutResult(status -> assertThat(media.findMissionCandidates(missionId,PageRequest.of(0,100)).getContent()).extracting(MediaAsset::getId).doesNotContain(id));
    }
    @Test void auditFailureRollsBackBothTheTombstoneAndCleanupRequest() {
        String id=createStaffAsset();var service=removals(true);
        Long version=jdbc.queryForObject("select version from drone_media where id=?",Long.class,id);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> service.remove(missionId,id,
                new com.ondemandmonitoring.media.dto.request.RemoveMediaRequest(version,"wrong file"))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select media_status from drone_media where id=?",String.class,id)).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("select orphan_cleanup_requested_at from drone_media where id=?",Instant.class,id)).isNull();
    }

    @Test void referenceCommittedWhileRemovalWaitsIsRecheckedUnderTheMissionLock() throws Exception {
        String id=createStaffAsset();var service=removals(false);
        Long version=jdbc.queryForObject("select version from drone_media where id=?",Long.class,id);
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var started=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var reference=pool.submit(() -> tx.executeWithoutResult(status -> {
                new JpaRepositoryFactory(em).getRepository(com.ondemandmonitoring.mission.repository.MissionRepository.class).findByIdForUpdate(missionId).orElseThrow();
                var asset=media.findByIdForUpdate(id).orElseThrow();locked.countDown();
                try {if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("reference release timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                var result=new MissionResult();result.setMission(em.getReference(Mission.class,missionId));
                result.setStatus(com.ondemandmonitoring.mission.enums.MissionResultStatus.COMPLETED);
                result.setApprovalStatus(com.ondemandmonitoring.mission.enums.MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL);
                result.setCompletedAt(Instant.now());result.setMediaCount(1);result.getMediaFiles().add(asset);persist(result);em.flush();
            }));
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
            var removal=pool.submit(() -> {started.countDown();return tx.execute(status -> service.remove(missionId,id,
                    new com.ondemandmonitoring.media.dto.request.RemoveMediaRequest(version,"wrong file")));});
            try {
                assertThat(started.await(10,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> removal.get(100,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally {release.countDown();}
            reference.get(10,TimeUnit.SECONDS);
            assertThatThrownBy(() -> removal.get(10,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(com.ondemandmonitoring.common.exception.ApiException.class);
        }
        assertThat(jdbc.queryForObject("select media_status from drone_media where id=?",String.class,id)).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("select count(*) from media_audit_logs where media_id=? and action='REMOVED'",Integer.class,id)).isZero();
    }

    @Test void freshHibernateSupportsRemovedWithoutAnUpgradeScriptAndCreatesMediaIndexes() {
        String id=tx.execute(status -> create(false).getId());
        jdbc.update("UPDATE drone_media SET media_status='REMOVED',storage_status='DELETE_PENDING' WHERE id=?",id);
        assertThat(jdbc.queryForObject("select media_status from drone_media where id=?",String.class,id)).isEqualTo("REMOVED");
        assertThat(jdbc.queryForObject("select count(*) from pg_indexes where schemaname='public' and indexname in ('ix_media_derivative_state','ix_media_upload_attempt_channel_actor','ix_drone_media_derivative_queue_v3')",Integer.class)).isEqualTo(3);
        assertThatThrownBy(() -> jdbc.update("UPDATE drone_media SET media_status='BOGUS' WHERE id=?",id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void freshBootstrapEnforcesCaptureSourceAndDerivativeChecks() {
        String id=tx.execute(status -> create(true).getId());
        assertThatThrownBy(() -> jdbc.update("UPDATE media_capture_metadata SET capture_time_source='UNKNOWN' WHERE media_id=?",id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE media_derivatives SET derivative_attempt_count=-1 WHERE media_id=?",id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE media_derivatives SET preview_size_bytes=-1 WHERE media_id=?",id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void aggregateGuardRejectsMissingDerivativeWithoutRequiringHistoricalBackfill() {
        String id=tx.execute(status -> create(false).getId());
        jdbc.update("DELETE FROM media_derivatives WHERE media_id=?",id);
        try {
            assertThatThrownBy(() -> new com.ondemandmonitoring.media.config.MediaDetailSchemaGuard(jdbc).run())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("must own a derivative row");
        } finally {
            jdbc.update("INSERT INTO media_derivatives(media_id,version,derivative_status,derivative_attempt_count) VALUES (?,0,'PENDING',0)",id);
        }
    }
    @Test void freshBootstrapRefusesRuntimeDataWithoutChangingIt() throws Exception {
        int ordersBefore=jdbc.queryForObject("select count(*) from orders",Integer.class);
        String bootstrap=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/seeddata/migrations/bootstrap_after_hibernate.sql"));
        assertThatThrownBy(() -> jdbc.execute(bootstrap)).isInstanceOf(org.springframework.dao.DataAccessException.class).hasMessageContaining("Fresh bootstrap refused");
        assertThat(jdbc.queryForObject("select count(*) from orders",Integer.class)).isEqualTo(ordersBefore);
    }

    @Test void removedContentCanBeUploadedUnderANewIdentityAndOldCleanupCannotDeleteIt() {
        String checksum=UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");
        String oldId=createStaffAsset();
        tx.executeWithoutResult(status -> {var old=media.findByIdForUpdate(oldId).orElseThrow();old.setChecksumSha256(checksum);old.setLocalMediaId("staff-old-"+UUID.randomUUID());});
        Long version=jdbc.queryForObject("select version from drone_media where id=?",Long.class,oldId);
        tx.executeWithoutResult(status -> removals(false).remove(missionId,oldId,new com.ondemandmonitoring.media.dto.request.RemoveMediaRequest(version,"selected wrong file")));
        tx.executeWithoutResult(status -> assertThat(media.existsActiveDuplicate(missionId,checksum)).isFalse());
        String newId=tx.execute(status -> {var fresh=create(true);fresh.setChecksumSha256(checksum);fresh.setLocalMediaId("staff-new-"+UUID.randomUUID());return fresh.getId();});
        String newKey=jdbc.queryForObject("select s3_key from drone_media where id=?",String.class,newId);
        tx.executeWithoutResult(status -> assertThat(media.existsActiveDuplicate(missionId,checksum)).isTrue());
        jdbc.update("UPDATE drone_media SET orphan_cleanup_requested_at=now()-interval '1 hour' WHERE id=?",oldId);
        var repositories=new JpaRepositoryFactory(em);var attemptRepository=repositories.getRepository(com.ondemandmonitoring.media.repository.MediaUploadAttemptRepository.class);
        var guard=new com.ondemandmonitoring.mission.service.impl.MissionMediaEvidenceGuardImpl(media,
                repositories.getRepository(com.ondemandmonitoring.mission.repository.MissionRepository.class),
                repositories.getRepository(com.ondemandmonitoring.mission.repository.MissionResultRepository.class),
                repositories.getRepository(com.ondemandmonitoring.mission.repository.MissionChecklistEvidenceRepository.class));
        var storage=mock(com.ondemandmonitoring.media.service.IMediaObjectStorage.class);
        var cleanup=new com.ondemandmonitoring.media.service.impl.MediaOrphanCleanupServiceImpl(media,attemptRepository,guard,storage,new JpaTransactionManager(emf));
        org.springframework.test.util.ReflectionTestUtils.setField(cleanup,"delaySeconds",0L);
        org.springframework.test.util.ReflectionTestUtils.setField(cleanup,"graceSeconds",0L);
        assertThat(cleanup.cleanup()).isGreaterThanOrEqualTo(1);
        verify(storage,never()).delete("qa",newKey);
        assertThat(jdbc.queryForObject("select media_status from drone_media where id=?",String.class,oldId)).isEqualTo("REMOVED");
        assertThat(jdbc.queryForObject("select storage_status from drone_media where id=?",String.class,oldId)).isEqualTo("DELETED");
        assertThat(jdbc.queryForObject("select media_status from drone_media where id=?",String.class,newId)).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("select count(*) from media_upload_attempts where media_id=?",Integer.class,oldId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from media_audit_logs where media_id=? and action='REMOVED'",Integer.class,oldId)).isEqualTo(1);
    }
    @Test void concurrentNewIntentsForTheSameContentCreateOnlyOneActiveMedia() throws Exception {
        String checksum=UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<String> prepare=() -> {start.await();return tx.execute(status -> {
                new JpaRepositoryFactory(em).getRepository(com.ondemandmonitoring.mission.repository.MissionRepository.class).findByIdForUpdate(missionId).orElseThrow();
                if(media.existsActiveDuplicate(missionId,checksum))return "duplicate";
                var fresh=create(true);fresh.setChecksumSha256(checksum);fresh.setLocalMediaId("staff-new-"+UUID.randomUUID());return fresh.getId();
            });};
            var first=pool.submit(prepare);var second=pool.submit(prepare);start.countDown();
            assertThat(java.util.List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS))).filteredOn(id -> id.equals("duplicate")).hasSize(1);
        }
        assertThat(jdbc.queryForObject("select count(*) from drone_media where mission_id=? and checksum_sha256=? and media_status<>'REMOVED'",Integer.class,missionId,checksum)).isEqualTo(1);
    }

}
