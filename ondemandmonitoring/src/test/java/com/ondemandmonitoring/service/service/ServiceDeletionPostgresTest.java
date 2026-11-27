package com.ondemandmonitoring.service.service;

import com.ondemandmonitoring.service.domain.*;
import com.ondemandmonitoring.service.domain.Service;
import com.ondemandmonitoring.service.repository.*;
import com.ondemandmonitoring.service.config.ServiceCatalogSeedDataInitializer;
import com.ondemandmonitoring.service.mapper.ServiceMapper;
import com.ondemandmonitoring.service.service.impl.*;
import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.media.service.IMediaObjectStorage;
import com.ondemandmonitoring.media.cleanup.*;
import org.springframework.mock.web.MockMultipartFile;
import com.ondemandmonitoring.checklist.domain.*;
import com.ondemandmonitoring.missiontemplate.domain.*;
import jakarta.persistence.EntityManagerFactory;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mapstruct.factory.Mappers;
import org.springframework.context.annotation.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt-in: real repositories, entity DDL, FKs and transactions in a dedicated random schema only. */
@EnabledIfEnvironmentVariable(named = "ODMS_SERVICE_DELETE_PG", matches = "true")
class ServiceDeletionPostgresTest {
    @TempDir Path temp;
    AnnotationConfigApplicationContext context;
    JdbcTemplate admin, db;
    String schema;
    ServiceRepository services;
    ServiceServiceImpl service;
    ServiceCatalogSeedDataInitializer seed;
    TransactionTemplate tx;
    IMediaObjectStorage storage;
    ObjectCleanupOutbox outbox;
    ServiceImageServiceImpl images;
    ServiceImageCleanupGuard guard;

    @Configuration @EnableJpaAuditing
    static class Config {
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source, Path script) {
            var f = new LocalContainerEntityManagerFactoryBean(); f.setDataSource(source);
            f.setManagedTypes(PersistenceManagedTypes.of(Service.class.getName(), ServiceDeliverable.class.getName(),
                    DeliverableType.class.getName(), ServiceCatalogBootstrap.class.getName(), ObjectCleanupTask.class.getName(), ServiceChecklist.class.getName(),
                    ChecklistDefinition.class.getName(), ChecklistConfigAudit.class.getName(), MissionTemplate.class.getName(), MissionTemplateItem.class.getName()));
            f.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            // Generate the relevant schema to a test-local script. Never run application ddl-auto=create.
            f.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none",
                    "jakarta.persistence.schema-generation.database.action", "none",
                    "jakarta.persistence.schema-generation.scripts.action", "create",
                    "jakarta.persistence.schema-generation.scripts.create-target", script.toString()));
            return f;
        }
    }
    @BeforeEach void setup() throws Exception {
        String url = System.getenv("ODMS_TEST_JDBC_URL"); String user = System.getenv("ODMS_TEST_DB_USER");
        String password = System.getenv("ODMS_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "qa_service_delete_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        String isolated = url.replaceAll("(?i)([?&])currentSchema=[^&]*", "$1")
                .replace("?&", "?").replace("&&", "&").replaceAll("[?&]$", "");
        var source = new DriverManagerDataSource(isolated, user, password);
        var properties = new Properties(); properties.setProperty("currentSchema", schema); source.setConnectionProperties(properties);
        db = new JdbcTemplate(source);
        assertEquals(schema, db.queryForObject("SELECT current_schema()", String.class));
        assertEquals(schema, db.queryForObject("SHOW search_path", String.class));
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> source);
        Path script = temp.resolve("entity-schema.sql"); context.registerBean(Path.class, () -> script);
        context.register(Config.class); context.refresh();
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(script));
        }
        // The order boundary is a minimal fixture matching Order.service's required restrictive FK.
        db.execute("CREATE TABLE orders(id varchar(255) PRIMARY KEY, service_id varchar(255) NOT NULL REFERENCES services(id), status varchar(40) NOT NULL)");
        var emf = context.getBean(EntityManagerFactory.class);
        var manager = new JpaTransactionManager(emf); tx = new TransactionTemplate(manager);
        var repos = new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(emf));
        // Match Spring Data's production repository exception translation, including explicit flush failures.
        repos.addRepositoryProxyPostProcessor((proxy, metadata) -> proxy.addAdvice(
                new org.springframework.dao.support.PersistenceExceptionTranslationInterceptor(
                        new org.springframework.orm.jpa.vendor.HibernateJpaDialect())));
        services = repos.getRepository(ServiceRepository.class);
        storage = mock(IMediaObjectStorage.class);
        var mapper = Mappers.getMapper(ServiceMapper.class);
        outbox = new ObjectCleanupOutbox(db, manager, new ObjectCleanupProperties());
        guard = new ServiceImageCleanupGuard(db);
        images = new ServiceImageServiceImpl(services, mapper, storage, new ServiceImageValidator(), outbox, manager);
        when(storage.bucket()).thenReturn("bucket");
        service = new ServiceServiceImpl(services, mapper, images, db);
        seed = new ServiceCatalogSeedDataInitializer(services, repos.getRepository(ServiceCatalogBootstrapRepository.class),
                repos.getRepository(DeliverableTypeRepository.class), repos.getRepository(ServiceDeliverableRepository.class));
    }
    @AfterEach void cleanup() {
        if (context != null) context.close();
        if (schema != null) {
            assertTrue(schema.matches("qa_service_delete_[a-f0-9]{32}"));
            admin.execute("DROP SCHEMA " + schema + " CASCADE");
            assertEquals(0, admin.queryForObject("SELECT count(*) FROM information_schema.schemata WHERE schema_name=?", Integer.class, schema));
        }
    }
    void catalog(String id) {
        db.update("INSERT INTO services(id,code,name,is_active,base_price,checklist_defaults_initialized,version,created_at,image_s3_bucket,image_s3_key) VALUES(?,?,?,false,0,false,0,now(),'bucket',?)", id, id, id, id + "/old.jpg");
    }
    void owned(String id) {
        db.update("INSERT INTO checklist_definitions(id,content,normalized_content,is_active,version,created_at) VALUES('definition','Shared','shared',true,0,now()) ON CONFLICT DO NOTHING");
        db.update("INSERT INTO deliverable_types(id,name,is_active,version,created_at) VALUES('deliverable','Shared',true,0,now()) ON CONFLICT DO NOTHING");
        db.update("INSERT INTO service_checklists(id,service_id,checklist_id,display_order,version,created_at) VALUES(?,?, 'definition',0,0,now())", id+"-check", id);
        db.update("INSERT INTO service_deliverables(id,service_id,deliverable_type_id,version,created_at) VALUES(?,?,'deliverable',0,now())", id+"-deliverable", id);
        db.update("INSERT INTO mission_templates(id,service_id,scope_label,version,created_at) VALUES(?,?,'scope',0,now())", id+"-template", id);
        db.update("INSERT INTO mission_template_items(id,mission_template_id,checklist_definition_id,version,created_at) VALUES(?,?,'definition',0,now())", id+"-item", id+"-template");
        db.update("INSERT INTO checklist_config_audits(id,service_id,service_name,action,version,created_at) VALUES(?,?,'historical','SERVICE_CHECKLIST_ASSIGNED',0,now())", id+"-audit", id);
    }
    long count(String table) { return db.queryForObject("SELECT count(*) FROM " + table, Long.class); }
    void delete(String id) { tx.executeWithoutResult(t -> service.delete(id)); }

    @Test void unusedInactiveServiceDeletesOnlyOwnedConfigurationAndEnqueuesImageAtomically() {
        catalog("first"); owned("first"); catalog("other"); owned("other");
        tx.executeWithoutResult(t -> {
            service.delete("first"); verifyNoInteractions(storage);
            assertEquals(1, count("services"));
            assertEquals("PENDING", cleanupStatus("first/old.jpg"));
        });
        for (String table : List.of("services","service_checklists","service_deliverables","mission_templates","mission_template_items","checklist_definitions","deliverable_types")) assertEquals(1, count(table), table);
        assertEquals(2, count("checklist_config_audits"));
        assertEquals("PENDING", cleanupStatus("first/old.jpg"));
        assertEquals(1, count("object_cleanup_outbox"));
        verifyNoInteractions(storage);
    }
    @Test void usedServicesAreRejectedRegardlessOfOrderStatus() {
        catalog("used"); owned("used");
        for (String status : List.of("DRAFT","COMPLETED","CANCELLED","REJECTED")) {
            db.update("INSERT INTO orders VALUES(?,?,?)", status,"used",status);
            var error = assertThrows(ApiException.class, () -> delete("used"));
            assertEquals(ErrorCode.SERVICE_IN_USE,error.getErrorCode());
            db.update("DELETE FROM orders WHERE id=?",status);
        }
        assertEquals(1,count("services")); assertEquals(1,count("mission_template_items")); verifyNoInteractions(storage);
    }
    @Test void rollbackRestoresAllLinksAndKeepsImage() {
        catalog("rollback"); owned("rollback");
        tx.executeWithoutResult(t -> { service.delete("rollback"); t.setRollbackOnly(); });
        for (String table : List.of("services","service_checklists","service_deliverables","mission_templates","mission_template_items")) assertEquals(1,count(table),table);
        assertEquals(0, count("object_cleanup_outbox"));
        verifyNoInteractions(storage);
    }
    @Test void unrecognizedHistoricalFkBlocksDeletionAndRollsBackEarlierCleanup() {
        catalog("history"); owned("history");
        db.execute("CREATE TABLE preserved_reference(service_id varchar(255) REFERENCES services(id))");
        db.update("INSERT INTO preserved_reference VALUES('history')");
        assertEquals(ErrorCode.SERVICE_IN_USE, assertThrows(ApiException.class, () -> delete("history")).getErrorCode());
        assertEquals(1,count("mission_template_items")); assertEquals(1,count("service_checklists"));
        assertEquals(1,count("preserved_reference")); verifyNoInteractions(storage);
    }
    @Test void orderCommittedWhileDeleteWaitsWinsAndIsPreserved() throws Exception {
        catalog("race"); var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var order = workers.submit(() -> tx.execute(t -> {
                services.findByIdForUpdate("race").orElseThrow();
                db.update("INSERT INTO orders VALUES('racing-order','race','DRAFT')"); locked.countDown();
                try { assertTrue(release.await(10,TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new RuntimeException(e); }
                return true;
            }));
            assertTrue(locked.await(10,TimeUnit.SECONDS));
            var deletion = workers.submit(() -> assertThrows(ApiException.class, () -> delete("race")));
            assertThrows(TimeoutException.class, () -> deletion.get(200,TimeUnit.MILLISECONDS));
            release.countDown(); order.get(10,TimeUnit.SECONDS);
            assertEquals(ErrorCode.SERVICE_IN_USE,deletion.get(10,TimeUnit.SECONDS).getErrorCode());
            assertEquals(1,count("orders")); assertEquals(1,count("services"));
        } finally { release.countDown(); workers.shutdownNow(); }
    }
    @Test void deletedServiceCannotAcquireNewOrderReference() throws Exception {
        catalog("race"); var deleted = new CountDownLatch(1); var release = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var deletion = workers.submit(() -> tx.execute(t -> {
                service.delete("race"); deleted.countDown();
                try { assertTrue(release.await(10,TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new RuntimeException(e); }
                return true;
            }));
            assertTrue(deleted.await(10,TimeUnit.SECONDS));
            var order = workers.submit(() -> assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                    () -> db.update("INSERT INTO orders VALUES('late','race','DRAFT')")));
            assertThrows(TimeoutException.class, () -> order.get(200,TimeUnit.MILLISECONDS));
            release.countDown(); deletion.get(10,TimeUnit.SECONDS); order.get(10,TimeUnit.SECONDS);
            assertEquals(0,count("services")); assertEquals(0,count("orders"));
        } finally { release.countDown(); workers.shutdownNow(); }
    }
    @Test void seedRestartPreservesActivationCustomRowsAndFinalServiceDeletion() {
        tx.executeWithoutResult(t -> seed.run(null)); assertEquals(4,count("services"));
        String id = db.queryForObject("SELECT id FROM services WHERE code='CONSTRUCTION_MONITORING'",String.class);
        var request = new com.ondemandmonitoring.service.dto.request.ServiceRequest(); request.setIsActive(false);
        tx.executeWithoutResult(t -> service.update(id,request));
        catalog("custom");
        tx.executeWithoutResult(t -> seed.run(null));
        assertFalse(services.findById(id).orElseThrow().getIsActive());
        request.setIsActive(true); tx.executeWithoutResult(t -> service.update(id,request));
        tx.executeWithoutResult(t -> seed.run(null)); assertTrue(services.findById(id).orElseThrow().getIsActive());
        assertTrue(services.findById("custom").isPresent());
        for (var s : services.findAll()) delete(s.getId());
        assertEquals(0,count("services")); tx.executeWithoutResult(t -> seed.run(null));
        assertEquals(0,count("services")); assertEquals(1,count("service_catalog_bootstrap"));
    }
    @Test void existingCatalogIsAdoptedWithoutMutation() {
        catalog("custom");
        tx.executeWithoutResult(t -> seed.run(null));
        assertEquals(1,count("services")); assertFalse(services.findById("custom").orElseThrow().getIsActive());
        assertEquals(0,count("deliverable_types"));
    }
    String cleanupStatus(String key) {
        return db.queryForObject("SELECT status FROM object_cleanup_outbox WHERE object_key=?", String.class, key);
    }
    MockMultipartFile png() {
        return new MockMultipartFile("file", "image.png", "image/png",
                new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});
    }
    ObjectCleanupGuard.Decision inspect(String owner, String key) {
        return tx.execute(t -> guard.inspect("bucket", key, owner));
    }
    @Test void committedReplacementCancelsCandidateAndEnqueuesOldKey() {
        catalog("image");
        tx.executeWithoutResult(t -> images.upload("image", png()));
        String key = services.findById("image").orElseThrow().getImageS3Key();
        assertEquals("CANCELLED", cleanupStatus(key));
        assertEquals("PENDING", cleanupStatus("image/old.jpg"));
        assertEquals(ObjectCleanupGuard.Decision.REFERENCED, inspect("image", key));
        assertEquals(ObjectCleanupGuard.Decision.DELETE, inspect("image", "image/old.jpg"));
        verify(storage, never()).deleteQuietly(any(), any());
    }
    @Test void rolledBackReplacementRetainsCandidateAndOldReference() {
        catalog("rollback-image");
        var key = new java.util.concurrent.atomic.AtomicReference<String>();
        tx.executeWithoutResult(t -> {
            images.upload("rollback-image", png());
            key.set(services.findById("rollback-image").orElseThrow().getImageS3Key());
            t.setRollbackOnly();
        });
        assertEquals("PENDING", cleanupStatus(key.get()));
        assertEquals(1, count("object_cleanup_outbox"));
        assertEquals("rollback-image/old.jpg", services.findById("rollback-image").orElseThrow().getImageS3Key());
        assertEquals(ObjectCleanupGuard.Decision.DELETE, inspect("rollback-image", key.get()));
        assertEquals(ObjectCleanupGuard.Decision.REFERENCED, inspect("rollback-image", "rollback-image/old.jpg"));
        verify(storage, never()).deleteQuietly(any(), any());
    }
    @Test void failedPutLeavesDurableCandidateWithoutChangingReference() {
        catalog("failed-put");
        when(storage.put(any(), any(), anyLong(), any(), any())).thenThrow(new IllegalStateException("storage unavailable"));
        assertThrows(ApiException.class, () -> tx.executeWithoutResult(t -> images.upload("failed-put", png())));
        assertEquals(1, count("object_cleanup_outbox"));
        assertEquals("PENDING", db.queryForObject("SELECT status FROM object_cleanup_outbox", String.class));
        assertEquals("failed-put/old.jpg", services.findById("failed-put").orElseThrow().getImageS3Key());
        verify(storage, never()).deleteQuietly(any(), any());
    }
    @Test void removalEnqueueRollsBackWithOwnerMetadata() {
        catalog("remove");
        tx.executeWithoutResult(t -> { images.remove("remove"); t.setRollbackOnly(); });
        assertEquals(0, count("object_cleanup_outbox"));
        assertEquals("remove/old.jpg", services.findById("remove").orElseThrow().getImageS3Key());
        tx.executeWithoutResult(t -> images.remove("remove"));
        assertEquals("PENDING", cleanupStatus("remove/old.jpg"));
        assertNull(services.findById("remove").orElseThrow().getImageS3Key());
        verify(storage, never()).deleteQuietly(any(), any());
    }
    @Test void guardProtectsSharedReferenceEvenWhenOwnerIsAbsentOrNoLongerReferencesKey() {
        catalog("shared"); catalog("owner");
        assertEquals("SERVICE_IMAGE", guard.type());
        assertEquals(ObjectCleanupGuard.Decision.REFERENCED, inspect("absent", "shared/old.jpg"));
        assertEquals(ObjectCleanupGuard.Decision.REFERENCED, inspect("owner", "shared/old.jpg"));
        assertEquals(ObjectCleanupGuard.Decision.DELETE, inspect("absent", "orphan.jpg"));
    }
    @Test void uploadPersistsCandidateBeforePutAndHoldsOwnerLockUntilRootCommit() throws Exception {
        catalog("upload-lock");
        var workers = Executors.newSingleThreadExecutor();
        try {
            when(storage.put(any(), any(), anyLong(), any(), any())).thenAnswer(call -> {
                String key = call.getArgument(0);
                workers.submit(() -> {
                    assertEquals("PENDING", cleanupStatus(key));
                    assertTrue(db.queryForObject("SELECT next_attempt_at > CURRENT_TIMESTAMP FROM object_cleanup_outbox WHERE object_key=?", Boolean.class, key));
                    assertEquals(ObjectCleanupGuard.Decision.DEFER, inspect("upload-lock", key));
                }).get(10, TimeUnit.SECONDS);
                return null;
            });
            tx.executeWithoutResult(t -> {
                images.upload("upload-lock", png());
                // The lock must survive save/candidate cancellation until the root transaction ends.
                try {
                    assertEquals(ObjectCleanupGuard.Decision.DEFER,
                            workers.submit(() -> inspect("upload-lock", "upload-lock/old.jpg")).get(10, TimeUnit.SECONDS));
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            assertEquals(ObjectCleanupGuard.Decision.DELETE, inspect("upload-lock", "upload-lock/old.jpg"));
        } finally { workers.shutdownNow(); }
    }

    @Test void normalUploadReservesOutsideOwnerTransactionThenLocksAndSavesInsideRequiredTransaction() {
        catalog("normal-upload");
        var observed = spy(outbox);
        doAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return call.callRealMethod();
        }).when(observed).reserveUpload(any(), any(), any(), any());
        doAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            return call.callRealMethod();
        }).when(observed).enqueue(any(), any(), any(), any());
        when(storage.put(any(), any(), anyLong(), any(), any())).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        });
        var normalImages = new ServiceImageServiceImpl(services, Mappers.getMapper(ServiceMapper.class),
                storage, new ServiceImageValidator(), observed, tx.getTransactionManager());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        normalImages.upload("normal-upload", png());
        verify(observed).reserveUpload(eq("bucket"), anyString(), eq("SERVICE_IMAGE"), eq("normal-upload"));
        verify(observed).enqueue("bucket", "normal-upload/old.jpg", "SERVICE_IMAGE", "normal-upload");
        String key = services.findById("normal-upload").orElseThrow().getImageS3Key();
        assertEquals("CANCELLED", cleanupStatus(key));
        assertEquals("PENDING", cleanupStatus("normal-upload/old.jpg"));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }
    @Test void sharedOwnersCanRetireSameKeyAndCleanupWaitsForLastReference() {
        catalog("shared-first"); catalog("shared-last");
        db.update("UPDATE services SET image_s3_key='shared.jpg' WHERE id IN ('shared-first','shared-last')");
        var worker = new ObjectCleanupWorker(outbox, storage, new ObjectCleanupProperties(), List.of(guard));
        delete("shared-first");
        assertEquals(1, worker.poll());
        assertEquals("CANCELLED", db.queryForObject(
                "SELECT status FROM object_cleanup_outbox WHERE owner_id='shared-first'", String.class));
        assertEquals("shared.jpg", services.findById("shared-last").orElseThrow().getImageS3Key());
        verify(storage, never()).delete(any(), any());
        delete("shared-last");
        assertEquals(2, count("object_cleanup_outbox"));
        assertEquals(1, worker.poll());
        assertEquals("COMPLETED", db.queryForObject(
                "SELECT status FROM object_cleanup_outbox WHERE owner_id='shared-last'", String.class));
        assertEquals(0, count("services"));
        verify(storage).delete("bucket", "shared.jpg");
        assertEquals(0, worker.poll());
        verify(storage, times(1)).delete("bucket", "shared.jpg");
    }

}
