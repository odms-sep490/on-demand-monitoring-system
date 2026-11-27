package com.ondemandmonitoring.devicecheck.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.ondemandmonitoring.devicecheck.domain.PersistedPreDeviceCheck;
import com.ondemandmonitoring.devicecheck.repository.PersistedPreDeviceCheckRepository;
import com.ondemandmonitoring.devicecheck.service.impl.MediaProbeServiceImpl;
import com.ondemandmonitoring.devicecheck.service.impl.ProbeObjectCleanupGuard;
import com.ondemandmonitoring.media.cleanup.*;
import com.ondemandmonitoring.media.service.IMediaObjectStorage;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.dto.response.MissionResponse;
import com.ondemandmonitoring.mission.service.IMissionService;
import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

/** Dedicated random PostgreSQL schemas; storage operations are mocked, never live AWS. */
@EnabledIfEnvironmentVariable(named = "ODMS_OBJECT_CLEANUP_PG", matches = "true")
class MediaProbeOutboxPostgresTest {
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
        schema = "qa_probe_cleanup_" + UUID.randomUUID().toString().replace("-", "");
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
            assertTrue(schema.matches("qa_probe_cleanup_[a-f0-9]{32}"));
            admin.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }


    IMediaObjectStorage storage;
    MediaProbeServiceImpl service;
    ProbeObjectCleanupGuard guard;

    void probe() {
        storage = mock(IMediaObjectStorage.class);
        when(storage.bucket()).thenReturn("bucket");
        when(storage.prefix()).thenReturn("drone-media");
        when(storage.open(any(), any())).thenAnswer(i -> new IMediaObjectStorage.StoredObjectStream(
                new ByteArrayInputStream(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9}),
                4L, "image/jpeg"));
        var runs = mock(PersistedPreDeviceCheckRepository.class);
        var missions = mock(IMissionService.class);
        var mission = new Mission();
        mission.setId("mission-1");
        var run = new PersistedPreDeviceCheck();
        run.setId("run-1");
        run.setMission(mission);
        when(runs.findById("run-1")).thenReturn(Optional.of(run));
        when(missions.getByIdResponse("mission-1")).thenReturn(
                MissionResponse.builder().id("mission-1").deviceId("device-1").build());
        service = new MediaProbeServiceImpl(runs, mock(IPersistedPreDeviceCheckService.class),
                missions, storage, outbox, manager);
        ReflectionTestUtils.setField(service, "probePrefix", "diagnostics/media-probes");
        guard = new ProbeObjectCleanupGuard();
    }

    String taskId() {
        return db.queryForObject("SELECT id FROM object_cleanup_outbox", String.class);
    }

    ObjectCleanupWorker restartedWorker() {
        return new ObjectCleanupWorker(new ObjectCleanupOutbox(db, manager, properties),
                storage, properties, List.of(guard));
    }

    void due() {
        db.update("UPDATE object_cleanup_outbox SET next_attempt_at=now()-interval '1 second'");
    }

    @Test
    void successfulSynchronousDeleteCancelsTheCandidate() {
        probe();
        assertTrue(service.verifyAutomatic("run-1").isCleanupVerified());
        assertEquals("CANCELLED", outbox.status(taskId()));
        verify(storage).delete(eq("bucket"), any());
        due();
        assertEquals(0, restartedWorker().poll());
    }

    @Test
    void failedSynchronousDeleteIsRecoveredByRestartedWorker() {
        probe();
        doThrow(new IllegalStateException("S3 delete failed")).doNothing().when(storage).delete(any(), any());
        assertThrows(RuntimeException.class, () -> service.verifyAutomatic("run-1"));
        assertEquals("PENDING", outbox.status(taskId()));
        due();
        assertEquals(1, restartedWorker().poll());
        assertEquals("COMPLETED", outbox.status(taskId()));
        verify(storage, times(2)).delete(eq("bucket"), any());
    }

    @Test
    void ambiguousPutFailureLeavesRecoverableCandidate() {
        probe();
        doThrow(new IllegalStateException("PUT timed out after writing")).when(storage)
                .put(any(), any(), anyLong(), any(), any());
        assertThrows(RuntimeException.class, () -> service.verifyAutomatic("run-1"));
        assertEquals("PENDING", outbox.status(taskId()));
        due();
        assertEquals(1, restartedWorker().poll());
        assertEquals("COMPLETED", outbox.status(taskId()));
        verify(storage).delete(eq("bucket"), any());
    }

    @Test
    void outerRollbackDoesNotLoseTheDurableIntent() {
        probe();
        tx.executeWithoutResult(t -> {
            assertTrue(service.verifyAutomatic("run-1").isCleanupVerified());
            t.setRollbackOnly();
        });
        assertEquals("PENDING", outbox.status(taskId()));
        due();
        assertEquals(1, restartedWorker().poll());
        verify(storage, times(2)).delete(eq("bucket"), any());
    }

    @Test
    void workerSkipsTaskLockedAcrossStorageIoEvenAfterGraceExpires() {
        probe();
        doAnswer(i -> {
            due();
            assertEquals(0, CompletableFuture.supplyAsync(() -> restartedWorker().poll())
                    .get(5, TimeUnit.SECONDS));
            return null;
        }).when(storage).put(any(), any(), anyLong(), any(), any());
        assertTrue(service.verifyAutomatic("run-1").isCleanupVerified());
        assertEquals("CANCELLED", outbox.status(taskId()));
        verify(storage).delete(eq("bucket"), any());
    }

    @Test
    void capturedTaskSurvivesStorageAndProbePrefixChangesAfterCrash() {
        probe();
        String oldKey = "drone-media/diagnostics/media-probes/run-1/" + UUID.randomUUID() + ".jpg";
        String old = outbox.reserveUpload("bucket", oldKey, "MEDIA_PROBE", "run-1");
        when(storage.prefix()).thenReturn("new-storage");
        ReflectionTestUtils.setField(service, "probePrefix", "new-custom-probes");
        service.verifyAutomatic("run-1");
        var uploaded = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(storage).put(uploaded.capture(), any(), anyLong(), any(), any());
        assertTrue(uploaded.getValue().startsWith("new-storage/new-custom-probes/run-1/"));
        due();
        assertEquals(1, restartedWorker().poll());
        assertEquals("COMPLETED", outbox.status(old));
        verify(storage).delete("bucket", oldKey);
    }

    @Test
    void crashAfterReservationIsRecoverableAndCannotDeleteANewExecutionKey() {
        probe();
        String oldKey = "drone-media/diagnostics/media-probes/run-1/" + UUID.randomUUID() + ".jpg";
        String old = outbox.reserveUpload("bucket", oldKey, "MEDIA_PROBE", "run-1");
        service.verifyAutomatic("run-1");
        due();
        assertEquals(1, restartedWorker().poll());
        assertEquals("COMPLETED", outbox.status(old));
        var deleted = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(storage, times(2)).delete(eq("bucket"), deleted.capture());
        assertEquals(oldKey, deleted.getAllValues().get(1));
        assertNotEquals(oldKey, deleted.getAllValues().getFirst());
    }
}
