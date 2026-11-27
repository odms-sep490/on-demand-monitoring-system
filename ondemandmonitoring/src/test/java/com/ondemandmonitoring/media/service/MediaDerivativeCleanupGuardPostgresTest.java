package com.ondemandmonitoring.media.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.ondemandmonitoring.media.cleanup.*;
import com.ondemandmonitoring.media.service.impl.MediaDerivativeCleanupGuard;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Isolated PostgreSQL tables matching the media/derivative persistence boundary; S3 is mocked. */
@EnabledIfEnvironmentVariable(named="ODMS_OBJECT_CLEANUP_PG", matches="true")
class MediaDerivativeCleanupGuardPostgresTest {
    JdbcTemplate admin, db;
    String schema;
    DataSourceTransactionManager manager;
    TransactionTemplate tx;
    ObjectCleanupOutbox outbox;
    MediaDerivativeCleanupGuard guard;
    ObjectCleanupProperties properties;
    final String key = "media/derived/media/v1/attempt-2/thumbnail.jpg";

    @BeforeEach void setup() throws Exception {
        String url = System.getenv("ODMS_TEST_JDBC_URL");
        String user = System.getenv("ODMS_TEST_DB_USER"), password=System.getenv("ODMS_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url,user,password));
        schema="qa_derivative_cleanup_"+UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA "+schema);
        var source = new DriverManagerDataSource(url.replaceAll("(?i)([?&])currentSchema=[^&]*", "$1")
                .replace("?&","?").replaceAll("[?&]$",""),user,password);
        var settings=new Properties(); settings.setProperty("currentSchema",schema); source.setConnectionProperties(settings);
        db=new JdbcTemplate(source);
        assertEquals(schema,db.queryForObject("SHOW search_path",String.class));
        db.execute("CREATE TABLE drone_media(id varchar(255) PRIMARY KEY,s3_bucket varchar(255),s3_key varchar(1000),storage_status varchar(30),media_status varchar(40))");
        db.execute("CREATE TABLE media_derivatives(media_id varchar(255) PRIMARY KEY,thumbnail_key varchar(1000),preview_key varchar(1000),derivative_status varchar(24),derivative_attempt_count integer,derivative_processed_at timestamptz,derivative_error_code varchar(80),version bigint DEFAULT 0)");
        String bootstrap;
        try(var input=getClass().getResourceAsStream("/seeddata/migrations/bootstrap_after_hibernate.sql")) {
            bootstrap=new String(Objects.requireNonNull(input).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        }
        int start=bootstrap.indexOf("CREATE TABLE IF NOT EXISTS public.object_cleanup_outbox");
        assertTrue(start>=0);
        db.execute(bootstrap.substring(start,bootstrap.indexOf("COMMIT;",start)).replace("public.",""));
        manager=new DataSourceTransactionManager(source); tx=new TransactionTemplate(manager);
        properties=new ObjectCleanupProperties(); outbox=new ObjectCleanupOutbox(db,manager,properties);
        guard=new MediaDerivativeCleanupGuard(db);
        db.update("INSERT INTO drone_media VALUES ('media','bucket','original.jpg','AVAILABLE','AVAILABLE')");
        db.update("INSERT INTO media_derivatives(media_id,derivative_status,derivative_attempt_count,derivative_processed_at) VALUES ('media','PROCESSING',2,now())");
    }

    @AfterEach void cleanup() {
        if(schema!=null) {
            assertTrue(schema.matches("qa_derivative_cleanup_[a-f0-9]{32}"));
            admin.execute("DROP SCHEMA "+schema+" CASCADE");
        }
    }
    ObjectCleanupGuard.Decision inspect(String candidate) {
        return tx.execute(t->guard.inspect("bucket",candidate,"media"));
    }
    @Test void freshCurrentAttemptIsDeferredEvenWithExpiredUploadGrace() {
        String reservation=outbox.reserveUpload("bucket",key,guard.type(),"media");
        db.update("UPDATE object_cleanup_outbox SET next_attempt_at=now()-interval '1 second' WHERE id=?",reservation);
        var storage=mock(IMediaObjectStorage.class);
        assertEquals(1,new ObjectCleanupWorker(outbox,storage,properties,List.of(guard)).poll());
        assertEquals("PENDING",outbox.status(reservation));
        verifyNoInteractions(storage);
    }
    @Test void staleCurrentAttemptIsFencedBeforeDeletion() {
        db.update("UPDATE media_derivatives SET derivative_processed_at=now()-interval '1 hour'");
        assertEquals(ObjectCleanupGuard.Decision.DELETE,inspect(key));
        assertEquals("FAILED",db.queryForObject("SELECT derivative_status FROM media_derivatives",String.class));
        assertEquals(1L,db.queryForObject("SELECT version FROM media_derivatives",Long.class));
    }
    @Test void publishedThumbnailAndPreviewAndOriginalAreAlwaysProtected() {
        db.update("UPDATE media_derivatives SET thumbnail_key=?,preview_key='preview.mp4',derivative_status='FAILED'",key);
        assertEquals(ObjectCleanupGuard.Decision.REFERENCED,inspect(key));
        assertEquals(ObjectCleanupGuard.Decision.REFERENCED,inspect("preview.mp4"));
        assertEquals(ObjectCleanupGuard.Decision.REFERENCED,inspect("original.jpg"));
    }
    @Test void anotherMediaReferenceProtectsObjectEvenWhenOwnerIsMissing() {
        db.update("UPDATE drone_media SET id='other',s3_key=?",key);
        assertEquals(ObjectCleanupGuard.Decision.REFERENCED,inspect(key));
    }
    @Test void oldAttemptIsDeletedWithoutFencingCurrentAttempt() {
        assertEquals(ObjectCleanupGuard.Decision.DELETE,inspect(key.replace("attempt-2","attempt-1")));
        assertEquals("PROCESSING",db.queryForObject("SELECT derivative_status FROM media_derivatives",String.class));
    }
    @Test void failedRemovedAndMissingOwnersCanBeCleaned() {
        db.update("UPDATE media_derivatives SET derivative_status='FAILED'");
        assertEquals(ObjectCleanupGuard.Decision.DELETE,inspect(key));
        db.update("UPDATE media_derivatives SET derivative_status='PROCESSING'");
        db.update("UPDATE drone_media SET media_status='REMOVED'");
        assertEquals(ObjectCleanupGuard.Decision.DELETE,inspect(key));
        db.update("DELETE FROM drone_media");
        assertEquals(ObjectCleanupGuard.Decision.DELETE,inspect(key));
    }
    @Test void ownerLockIsSkippedInsteadOfBlockingOrDeleting() throws Exception {
        var locked=new CountDownLatch(1); var release=new CountDownLatch(1);
        var pool=Executors.newSingleThreadExecutor();
        try {
            var holder=pool.submit(()->tx.executeWithoutResult(t->{
                db.queryForList("SELECT id FROM drone_media WHERE id='media' FOR UPDATE");
                locked.countDown();
                try { assertTrue(release.await(10,TimeUnit.SECONDS)); }
                catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }));
            assertTrue(locked.await(5,TimeUnit.SECONDS));
            assertEquals(ObjectCleanupGuard.Decision.DEFER,inspect(key));
            release.countDown(); holder.get(5,TimeUnit.SECONDS);
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void cancellationRollsBackTogetherWithPublishedReferences() {
        String a=outbox.reserveUpload("bucket",key,guard.type(),"media");
        String b=outbox.reserveUpload("bucket",key.replace("thumbnail.jpg","preview.jpg"),guard.type(),"media");
        tx.executeWithoutResult(t->{
            outbox.protectReservation(a);outbox.protectReservation(b);
            db.update("UPDATE media_derivatives SET thumbnail_key=?",key);
            outbox.cancelReservation(a);outbox.cancelReservation(b);t.setRollbackOnly();
        });
        assertEquals("PENDING",outbox.status(a));assertEquals("PENDING",outbox.status(b));
        assertNull(db.queryForObject("SELECT thumbnail_key FROM media_derivatives",String.class));
    }
    @Test void finalizedCleanupCannotBePublishedByLateWorker() {
        String id=tx.execute(t->outbox.enqueue("bucket",key,guard.type(),"media"));
        var claim=outbox.claimNext().orElseThrow();outbox.complete(claim);
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(t->outbox.protectReservation(id)));
    }
    @Test void bothReservationLocksFenceCleanupUntilPublicationCommits() throws Exception {
        String preview=key.replace("thumbnail.jpg","preview.jpg");
        String a=outbox.reserveUpload("bucket",key,guard.type(),"media");
        String b=outbox.reserveUpload("bucket",preview,guard.type(),"media");
        db.update("UPDATE object_cleanup_outbox SET next_attempt_at=now()-interval '1 second'");
        db.update("UPDATE media_derivatives SET derivative_processed_at=now()-interval '1 hour'");
        var locked=new CountDownLatch(1); var release=new CountDownLatch(1);
        var storage=mock(IMediaObjectStorage.class);
        var pool=Executors.newSingleThreadExecutor();
        try {
            var publication=pool.submit(()->tx.executeWithoutResult(t->{
                outbox.protectReservation(a);outbox.protectReservation(b);
                locked.countDown();
                try { assertTrue(release.await(10,TimeUnit.SECONDS)); }
                catch(InterruptedException e) { Thread.currentThread().interrupt();throw new IllegalStateException(e); }
                // Simulated completed PUTs followed by reference publication in the same transaction.
                db.update("UPDATE media_derivatives SET thumbnail_key=?,preview_key=?,derivative_status='READY'",key,preview);
                outbox.cancelReservation(a);outbox.cancelReservation(b);
            }));
            assertTrue(locked.await(5,TimeUnit.SECONDS));
            assertEquals(0,new ObjectCleanupWorker(outbox,storage,properties,List.of(guard)).poll());
            verifyNoInteractions(storage);
            release.countDown();publication.get(5,TimeUnit.SECONDS);
            assertEquals("CANCELLED",outbox.status(a));assertEquals("CANCELLED",outbox.status(b));
            assertEquals(0,new ObjectCleanupWorker(outbox,storage,properties,List.of(guard)).poll());
        } finally { release.countDown();pool.shutdownNow(); }
    }

    @Test void serviceHoldsBothTaskLocksThroughoutActualPutCallbacks() throws Exception {
        var media=mock(com.ondemandmonitoring.media.repository.MediaAssetRepository.class);
        var storage=mock(IMediaObjectStorage.class);
        var asset=new com.ondemandmonitoring.media.domain.MediaAsset();
        asset.setId("media");asset.setType("IMAGE");asset.setS3Bucket("bucket");asset.setS3Key("original.jpg");
        when(media.findByIdForUpdate("media")).thenReturn(Optional.of(asset));
        when(media.findDerivativeCandidates(eq("IMAGE"),any(),any(),anyInt(),any())).thenReturn(List.of("media"));
        when(storage.prefix()).thenReturn("media");
        var bytes=new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(20,10,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",bytes);
        when(storage.open("bucket","original.jpg")).thenReturn(new IMediaObjectStorage.StoredObjectStream(
                new java.io.ByteArrayInputStream(bytes.toByteArray()),(long)bytes.size(),"image/png"));
        properties.setUploadGraceSeconds(0); // Immediately eligible, so only row locks can protect PUTs.
        var deletionStorage=mock(IMediaObjectStorage.class);
        var worker=new ObjectCleanupWorker(outbox,deletionStorage,properties,List.of(guard));
        doAnswer(call->{
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(2,db.queryForObject("SELECT count(*) FROM object_cleanup_outbox WHERE status='PENDING'",Integer.class));
            assertEquals(0,worker.poll());
            return null;
        }).when(storage).put(anyString(),anyString(),anyString(),anyLong(),any(),anyString());
        var service=new com.ondemandmonitoring.media.service.impl.MediaDerivativeService(media,storage,manager,outbox);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"batchSize",1);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"maxAttempts",5);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"staleMinutes",15);
        assertEquals(1,service.processBatch());
        verify(storage,times(2)).put(anyString(),anyString(),anyString(),anyLong(),any(),anyString());
        verifyNoInteractions(deletionStorage);
        assertEquals(2,db.queryForObject("SELECT count(*) FROM object_cleanup_outbox WHERE status='CANCELLED'",Integer.class));
    }

}
