package com.ondemandmonitoring.media.cleanup;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ondemandmonitoring.media.service.IMediaObjectStorage;
import com.ondemandmonitoring.media.service.impl.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.*;

/**
 * Real PostgreSQL locks and shared worker; minimal domain tables isolate storage lifecycle tests.
 */
@EnabledIfEnvironmentVariable(named = "ODMS_OBJECT_CLEANUP_PG", matches = "true")
class MediaObjectCleanupPostgresTest {
    JdbcTemplate admin, db;
    String schema;
    ObjectCleanupOutbox outbox;
    MediaObjectCleanup cleanup;
    MediaObjectCleanupGuard guard;
    ObjectCleanupWorker worker;
    TransactionTemplate tx;
    IMediaObjectStorage storage;

    @BeforeEach
    void setup() throws Exception {
        String url = System.getenv("ODMS_TEST_JDBC_URL"),
                user = System.getenv("ODMS_TEST_DB_USER"),
                password = System.getenv("ODMS_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_media_outbox_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        String isolated =
                url.replaceAll("(?i)([?&])currentSchema=[^&]*", "$1")
                        .replace("?&", "?")
                        .replace("&&", "&")
                        .replaceAll("[?&]$", "");
        var source = new DriverManagerDataSource(isolated, user, password);
        var settings = new Properties();
        settings.setProperty("currentSchema", schema);
        source.setConnectionProperties(settings);
        db = new JdbcTemplate(source);
        assertEquals(schema, db.queryForObject("SHOW search_path", String.class));
        String bootstrap;
        try (var stream =
                getClass()
                        .getResourceAsStream(
                                "/seeddata/migrations/bootstrap_after_hibernate.sql")) {
            bootstrap =
                    new String(
                            Objects.requireNonNull(stream).readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8);
        }
        int start = bootstrap.indexOf("CREATE TABLE IF NOT EXISTS public.object_cleanup_outbox");
        db.execute(
                bootstrap
                        .substring(start, bootstrap.indexOf("COMMIT;", start))
                        .replace("public.", ""));
        db.execute(
                "CREATE TABLE drone_media(id varchar(255) PRIMARY KEY,s3_bucket text,s3_key"
                    + " text,storage_status text,media_status text)");
        db.execute(
                "CREATE TABLE media_derivatives(media_id varchar(255) PRIMARY KEY,thumbnail_key"
                    + " text,preview_key text)");
        db.execute(
                "CREATE TABLE media_upload_attempts(media_id varchar(255),storage_key text,status"
                    + " text,attempt_number int)");
        var manager = new DataSourceTransactionManager(source);
        tx = new TransactionTemplate(manager);
        var properties = new ObjectCleanupProperties();
        outbox = new ObjectCleanupOutbox(db, manager, properties);
        cleanup = new MediaObjectCleanup(outbox);
        guard = new MediaObjectCleanupGuard(db);
        storage = mock(IMediaObjectStorage.class);
        worker = new ObjectCleanupWorker(outbox, storage, properties, List.of(guard));
    }

    @AfterEach
    void close() {
        if (schema != null) {
            assertTrue(schema.matches("qa_media_outbox_[a-f0-9]{32}"));
            admin.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    void asset(String id, String key, String storageStatus) {
        db.update(
                "INSERT INTO drone_media VALUES(?,'bucket',?,?,'AVAILABLE')",
                id,
                key,
                storageStatus);
    }

    String enqueue(String key, String owner) {
        return tx.execute(t -> cleanup.enqueue("bucket", key, owner));
    }

    @Test
    void currentOriginalAndDerivativeReferencesPreventDeletion() {
        asset("one", "original", "AVAILABLE");
        db.update("INSERT INTO media_derivatives VALUES('one','thumbnail','preview')");
        for (String key : List.of("original", "thumbnail", "preview")) {
            String task = enqueue(key, "retired-owner");
            worker.poll();
            assertEquals("CANCELLED", outbox.status(task));
        }
        verifyNoInteractions(storage);
    }

    @Test
    void committedRetirementDeletesEveryKeyAndAcknowledgesOnlyCompletedTasks() {
        asset("one", "original", "AVAILABLE");
        var ids = new ArrayList<String>();
        tx.executeWithoutResult(
                t -> {
                    db.update(
                            "UPDATE drone_media SET storage_status='DELETE_PENDING' WHERE"
                                + " id='one'");
                    ids.addAll(
                            cleanup.enqueue(
                                    "bucket", List.of("original", "thumbnail", "preview"), "one"));
                    assertFalse(cleanup.completed(ids));
                    verifyNoInteractions(storage);
                });
        assertEquals(3, worker.poll());
        assertTrue(cleanup.completed(ids));
        assertFalse(cleanup.failed(ids));
        for (String key : List.of("original", "thumbnail", "preview"))
            verify(storage).delete("bucket", key);
    }

    @Test
    void retirementRollbackKeepsReferenceAndDiscardsAllDeletionIntents() {
        asset("one", "original", "AVAILABLE");
        tx.executeWithoutResult(
                t -> {
                    db.update(
                            "UPDATE drone_media SET storage_status='DELETE_PENDING' WHERE"
                                + " id='one'");
                    cleanup.enqueue("bucket", "original", "one");
                    t.setRollbackOnly();
                });
        assertEquals(0, worker.poll());
        verifyNoInteractions(storage);
        assertEquals(
                "AVAILABLE",
                db.queryForObject("SELECT storage_status FROM drone_media", String.class));
    }

    @Test
    void stagingIsProtectedUntilSuccessfulCopyAndLateEventsHaveIndependentIntents() {
        asset("one", "final/immutable", "AVAILABLE");
        db.update("INSERT INTO media_upload_attempts VALUES('one','staging/object','UPLOADED',1)");
        String initial = enqueue("staging/object", "event-1");
        worker.poll();
        assertEquals("CANCELLED", outbox.status(initial));
        db.update("UPDATE media_upload_attempts SET status='SUCCEEDED'");
        enqueue("staging/object", "event-1");
        worker.poll();
        assertEquals("COMPLETED", outbox.status(initial));
        String late = enqueue("staging/object", "event-2");
        worker.poll();
        assertEquals("COMPLETED", outbox.status(late));
        verify(storage, times(2)).delete("bucket", "staging/object");
    }

    @Test
    void reservationLockProtectsUncommittedUploadsEvenWhenAlreadyDue() throws Exception {
        String id = cleanup.reserveUpload("bucket", "new-object", "owner");
        db.update(
                "UPDATE object_cleanup_outbox SET next_attempt_at=now()-interval '1 second' WHERE"
                    + " id=?",
                id);
        var pool = Executors.newSingleThreadExecutor();
        try {
            tx.executeWithoutResult(
                    t -> {
                        cleanup.protectReservation(id);
                        try {
                            assertTrue(
                                    pool.submit(() -> outbox.claimNext().isEmpty())
                                            .get(5, TimeUnit.SECONDS));
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                        asset("owner", "new-object", "AVAILABLE");
                        cleanup.cancelReservation(id);
                    });
            assertEquals("CANCELLED", outbox.status(id));
            assertEquals(0, worker.poll());
            verifyNoInteractions(storage);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void rollbackAfterUploadLeavesDurableReservationForRestartedWorker() {
        String id = cleanup.reserveUpload("bucket", "abandoned", "owner");
        tx.executeWithoutResult(
                t -> {
                    cleanup.protectReservation(id);
                    asset("owner", "abandoned", "AVAILABLE");
                    cleanup.cancelReservation(id);
                    t.setRollbackOnly();
                });
        assertEquals("PENDING", outbox.status(id));
        db.update(
                "UPDATE object_cleanup_outbox SET next_attempt_at=now()-interval '1 second' WHERE"
                    + " id=?",
                id);
        assertEquals(1, worker.poll());
        assertEquals("COMPLETED", outbox.status(id));
        verify(storage).delete("bucket", "abandoned");
    }

    @Test
    void lockedDomainRowsDeferWithoutSpendingRetryBudget() throws Exception {
        asset("one", "original", "DELETE_PENDING");
        String id = enqueue("original", "one");
        var pool = Executors.newSingleThreadExecutor();
        try {
            tx.executeWithoutResult(
                    t -> {
                        db.queryForList("SELECT id FROM drone_media WHERE id='one' FOR UPDATE");
                        try {
                            assertEquals(
                                    1, pool.submit(() -> worker.poll()).get(5, TimeUnit.SECONDS));
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
            assertEquals("PENDING", outbox.status(id));
            assertEquals(
                    0,
                    db.queryForObject(
                            "SELECT attempts FROM object_cleanup_outbox WHERE id=?",
                            Integer.class,
                            id));
            verifyNoInteractions(storage);
        } finally {
            pool.shutdownNow();
        }
    }
}
