package com.ondemandmonitoring.media.cleanup;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ondemandmonitoring.media.service.IMediaObjectStorage;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.*;

/**
 * Real PostgreSQL transactions/concurrency in random schemas; never touches application tables or
 * S3.
 */
@EnabledIfEnvironmentVariable(named = "ODMS_OBJECT_CLEANUP_PG", matches = "true")
class ObjectCleanupPostgresTest {
    JdbcTemplate admin, db;
    String schema;
    ObjectCleanupProperties properties;
    ObjectCleanupOutbox outbox;
    TransactionTemplate tx;
    DataSourceTransactionManager manager;

    @BeforeEach
    void setup() throws Exception {
        String url = System.getenv("ODMS_TEST_JDBC_URL"),
                user = System.getenv("ODMS_TEST_DB_USER"),
                password = System.getenv("ODMS_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_object_cleanup_" + UUID.randomUUID().toString().replace("-", "");
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
        assertTrue(start >= 0);
        db.execute(
                bootstrap
                        .substring(start, bootstrap.indexOf("COMMIT;", start))
                        .replace("public.", ""));
        manager = new DataSourceTransactionManager(source);
        tx = new TransactionTemplate(manager);
        properties = new ObjectCleanupProperties();
        properties.setMaxAttempts(2);
        outbox = new ObjectCleanupOutbox(db, manager, properties);
    }

    @AfterEach
    void cleanup() {
        if (schema != null) {
            assertTrue(schema.matches("qa_object_cleanup_[a-f0-9]{32}"));
            admin.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    String enqueue(String key) {
        return tx.execute(t -> outbox.enqueue("bucket", key, "TEST", "owner"));
    }

    String status(String id) {
        return db.queryForObject(
                "SELECT status FROM object_cleanup_outbox WHERE id=?", String.class, id);
    }

    void due(String id) {
        db.update(
                "UPDATE object_cleanup_outbox SET next_attempt_at=now()-interval '1"
                        + " second',lease_until=now()-interval '1 second' WHERE id=?",
                id);
    }

    int attempts(String id) {
        return db.queryForObject(
                "SELECT attempts FROM object_cleanup_outbox WHERE id=?", Integer.class, id);
    }

    @Test
    void rollbackDiscardsIntentAndCommitSurvivesRestart() {
        assertThrows(
                IllegalStateException.class,
                () -> outbox.enqueue("bucket", "key", "TEST", "owner"));
        tx.executeWithoutResult(
                t -> {
                    outbox.enqueue("bucket", "rollback", "TEST", "owner");
                    t.setRollbackOnly();
                });
        assertEquals(0, outbox.list("PENDING", 0, 20).total());
        String id = enqueue("committed");
        var storage = mock(IMediaObjectStorage.class);
        ObjectCleanupGuard guard =
                new ObjectCleanupGuard() {
                    public String type() {
                        return "TEST";
                    }

                    public Decision inspect(String bucket, String key, String owner) {
                        return Decision.DELETE;
                    }
                };
        var restarted = new ObjectCleanupOutbox(db, manager, properties);
        assertEquals(
                1, new ObjectCleanupWorker(restarted, storage, properties, List.of(guard)).poll());
        assertEquals("COMPLETED", status(id));
        verify(storage).delete("bucket", "committed");
    }

    @Test
    void uploadReservationSurvivesRollbackAndCanBeReactivatedAfterReferenceRetires() {
        var ids = new ArrayList<String>();
        tx.executeWithoutResult(
                t -> {
                    String id = outbox.reserveUpload("bucket", "new", "TEST", "owner");
                    ids.add(id);
                    outbox.cancelReservation(id);
                    t.setRollbackOnly();
                });
        String id = ids.getFirst();
        assertEquals("PENDING", status(id));
        assertTrue(outbox.claimNext().isEmpty());
        tx.executeWithoutResult(t -> outbox.cancelReservation(id));
        assertEquals("CANCELLED", status(id));
        assertEquals(id, enqueue("new"));
        assertEquals("PENDING", status(id));
        assertEquals(id, enqueue("new"));
        assertEquals(1, outbox.list("PENDING", 0, 20).total());
    }

    @Test
    void expiredLeaseRecoversAndStaleWorkerCannotAcknowledgeNewClaim() {
        String id = enqueue("leased");
        var first = outbox.claimNext().orElseThrow();
        assertTrue(outbox.claimNext().isEmpty());
        due(id);
        var second = outbox.claimNext().orElseThrow();
        assertNotEquals(first.token(), second.token());
        outbox.complete(first);
        outbox.fail(first, new IllegalStateException());
        assertEquals("PROCESSING", status(id));
        outbox.complete(second);
        assertEquals("COMPLETED", status(id));
    }

    @Test
    void crashAfterStorageDeletionReplaysIdempotentlyAfterLeaseExpires() {
        String id = enqueue("crash-after-delete");
        var storage = mock(IMediaObjectStorage.class);
        var task = outbox.claimNext().orElseThrow();
        storage.delete(
                task.bucket(), task.key()); // Process terminates before acknowledging the task.
        due(id);
        ObjectCleanupGuard guard =
                new ObjectCleanupGuard() {
                    public String type() {
                        return "TEST";
                    }

                    public Decision inspect(String bucket, String key, String owner) {
                        return Decision.DELETE;
                    }
                };
        var restarted = new ObjectCleanupOutbox(db, manager, properties);
        assertEquals(
                1, new ObjectCleanupWorker(restarted, storage, properties, List.of(guard)).poll());
        verify(storage, times(2)).delete("bucket", "crash-after-delete");
        assertEquals("COMPLETED", status(id));
    }

    @Test
    void failuresBackOffBecomeDeadAndAllowManualRetryWithoutLeakingDetails() {
        String id = enqueue("failed");
        var first = outbox.claimNext().orElseThrow();
        outbox.fail(first, new IllegalStateException("secret signed URL"));
        assertEquals("PENDING", status(id));
        assertTrue(outbox.claimNext().isEmpty());
        due(id);
        outbox.fail(outbox.claimNext().orElseThrow(), new IllegalArgumentException("secret"));
        assertEquals("DEAD", status(id));
        assertEquals(
                "IllegalArgumentException",
                outbox.list("DEAD", 0, 20).items().getFirst().lastError());
        assertTrue(outbox.retry(id));
        assertFalse(outbox.retry(id));
        assertEquals(0, attempts(id));
    }

    @Test
    void repeatedDomainReconciliationPreservesRetryBackoffAndDiagnostic() {
        String id=enqueue("retry-backoff");
        outbox.fail(outbox.claimNext().orElseThrow(),new IllegalStateException("storage"));
        var deadline=db.queryForObject("SELECT next_attempt_at FROM object_cleanup_outbox WHERE id=?",java.sql.Timestamp.class,id);
        assertEquals(id,enqueue("retry-backoff"));
        assertEquals(deadline,db.queryForObject("SELECT next_attempt_at FROM object_cleanup_outbox WHERE id=?",java.sql.Timestamp.class,id));
        assertEquals("IllegalStateException",outbox.list("PENDING",0,20).items().getFirst().lastError());
        assertEquals(1,attempts(id));
        assertTrue(outbox.claimNext().isEmpty());
    }

    @Test
    void crashedFinalAttemptAndLoweredRetryLimitBecomeDead() {
        String id = enqueue("crash");
        outbox.claimNext().orElseThrow();
        due(id);
        outbox.claimNext().orElseThrow();
        due(id);
        assertTrue(outbox.claimNext().isEmpty());
        assertEquals("DEAD", status(id));
        String pending = enqueue("limit");
        db.update("UPDATE object_cleanup_outbox SET attempts=2 WHERE id=?", pending);
        assertTrue(outbox.claimNext().isEmpty());
        assertEquals("DEAD", status(pending));
    }

    @Test
    void ownerDeferralPreservesBudgetAndReferenceCancelsTask() {
        String id = enqueue("busy");
        outbox.defer(outbox.claimNext().orElseThrow());
        assertEquals(0, attempts(id));
        assertEquals("PENDING", status(id));
        due(id);
        outbox.cancel(outbox.claimNext().orElseThrow());
        assertEquals("CANCELLED", status(id));
    }

    @Test
    void concurrentWorkersClaimDifferentTasks() throws Exception {
        String first = enqueue("one"), second = enqueue("two");
        var pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            Callable<ObjectCleanupOutbox.Claim> claim =
                    () -> {
                        start.await();
                        return outbox.claimNext().orElseThrow();
                    };
            var a = pool.submit(claim);
            var b = pool.submit(claim);
            start.countDown();
            assertEquals(
                    Set.of(first, second),
                    Set.of(a.get(10, TimeUnit.SECONDS).id(), b.get(10, TimeUnit.SECONDS).id()));
            assertTrue(outbox.claimNext().isEmpty());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void sharedObjectsKeepIndependentOwnerIntentsAndKeysRespectByteLimit() {
        enqueue("same");
        String otherOwner = tx.execute(t -> outbox.enqueue("bucket", "same", "TEST", "different"));
        assertNotNull(otherOwner);
        assertEquals(2, outbox.list("PENDING", 0, 20).total());
        String multi = Character.toString(0x754c);
        assertThrows(IllegalArgumentException.class, () -> enqueue(multi.repeat(342)));
        assertDoesNotThrow(() -> enqueue(multi.repeat(341)));
    }

    @Test
    void retentionOnlyPurgesOldTerminalTasksAndFiltersAreBounded() {
        String done = enqueue("done");
        outbox.complete(outbox.claimNext().orElseThrow());
        String pending = enqueue("pending");
        db.update("UPDATE object_cleanup_outbox SET completed_at=now()-interval '31 days'");
        assertEquals(1, outbox.purgeCompleted());
        assertEquals("PENDING", status(pending));
        assertThrows(IllegalArgumentException.class, () -> outbox.list("unknown", 0, 20));
        assertThrows(IllegalArgumentException.class, () -> outbox.list("PENDING", -1, 20));
        assertThrows(IllegalArgumentException.class, () -> outbox.list("PENDING", 0, 101));
    }
}
