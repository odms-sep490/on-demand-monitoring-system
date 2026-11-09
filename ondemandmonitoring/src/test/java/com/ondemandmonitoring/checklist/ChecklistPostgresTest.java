package com.ondemandmonitoring.checklist;

import com.ondemandmonitoring.checklist.dto.request.*;
import com.ondemandmonitoring.checklist.mapper.*;
import com.ondemandmonitoring.checklist.repository.*;
import com.ondemandmonitoring.checklist.service.*;
import com.ondemandmonitoring.checklist.service.impl.*;
import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.service.domain.Service;
import com.ondemandmonitoring.service.repository.ServiceRepository;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.dto.request.OrderCreateRequest;
import com.ondemandmonitoring.order.dto.request.OrderChecklistItemRequest;
import com.ondemandmonitoring.order.dto.request.OrderDeliverableRequest;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.order.repository.OrderChecklistItemRepository;
import com.ondemandmonitoring.order.mapper.OrderMapper;
import com.ondemandmonitoring.order.mapper.OrderChecklistItemMapper;
import com.ondemandmonitoring.order.service.IOrderService;
import com.ondemandmonitoring.order.service.IOrderChecklistSnapshotService;
import com.ondemandmonitoring.order.service.impl.OrderService;
import com.ondemandmonitoring.order.service.impl.OrderChecklistSnapshotService;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.warehouse.domain.PreferredTime;
import com.ondemandmonitoring.warehouse.enums.PreferredTimeCode;
import com.ondemandmonitoring.service.domain.DeliverableType;
import com.ondemandmonitoring.service.repository.DeliverableTypeRepository;
import com.ondemandmonitoring.service.repository.ServiceDeliverableRepository;
import com.ondemandmonitoring.warehouse.repository.PreferredTimeRepository;
import com.ondemandmonitoring.mission.repository.MissionResultRepository;
import jakarta.persistence.EntityManagerFactory;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mapstruct.factory.Mappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.*;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt-in: Hibernate creates tables only in a random schema of a dedicated local test database. */
@SpringJUnitConfig(ChecklistPostgresTest.Config.class)
@EnabledIfEnvironmentVariable(named = "ODMS_CHECKLIST_TEST_JDBC_URL",
        matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/odms_checklist_verification")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ChecklistPostgresTest {
    static final String SCHEMA = "checklist_verify_" + UUID.randomUUID().toString().replace("-", "");
    @Autowired IChecklistService catalog;
    @Autowired IServiceChecklistService assignments;
    @Autowired ServiceRepository services;
    @Autowired ServiceChecklistRepository links;
    @Autowired ChecklistDefinitionRepository definitions;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired DataSource dataSource;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired IOrderService orderService;
    @Autowired IOrderChecklistSnapshotService snapshotService;
    @Autowired OrderRepository orders;
    @Autowired OrderChecklistItemRepository snapshots;
    @Autowired AuthenticatedUserResolver resolver;
    @Autowired PreferredTimeRepository times;
    @Autowired DeliverableTypeRepository deliverables;
    @Autowired ServiceDeliverableRepository serviceDeliverables;
    TransactionTemplate transaction;

    @BeforeEach void prepare() {
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("TRUNCATE orders, missions, drone_media, resource_time_locks, mission_device_assignments CASCADE");
        bootstrapCurrentSchema();
        transaction = new TransactionTemplate(transactionManager);
        reset(resolver, times, deliverables, serviceDeliverables);
        transaction.executeWithoutResult(status -> {
            snapshots.deleteAllInBatch(); orders.deleteAll();
            orders.flush();
            links.deleteAllInBatch(); definitions.deleteAllInBatch(); services.deleteAllInBatch();
        });
        new JdbcTemplate(dataSource).execute("TRUNCATE users, roles, preferred_times, deliverable_types CASCADE");
        transaction.executeWithoutResult(status ->
                new com.ondemandmonitoring.pricing.config.PricingPolicyInitializer(
                        new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory))
                                .getRepository(com.ondemandmonitoring.pricing.repository.PricingPolicyRepository.class)).run(null));
    }

    void bootstrapCurrentSchema() {
        CurrentSchemaPostgres.bootstrap(dataSource, SCHEMA);
    }

    void verifyCurrentSchema() {
        CurrentSchemaPostgres.verify(dataSource, SCHEMA);
    }

    @AfterAll void cleanup() {
        if (!SCHEMA.matches("checklist_verify_[a-f0-9]{32}")) throw new IllegalStateException("Unsafe test schema");
        new JdbcTemplate(dataSource).execute("DROP SCHEMA " + SCHEMA + " CASCADE");
    }

    String service(String name) {
        return transaction.execute(status -> services.saveAndFlush(
                Service.builder().name(name).isActive(true).build()).getId());
    }

    String checklist(String content) {
        var request = new ChecklistRequest(); request.setContent(content);
        request.setBasePrice(new java.math.BigDecimal("100000"));
        return catalog.create(request).getId();
    }

    ChecklistAssignmentRequest assignment(String id, int order) {
        var request = new ChecklistAssignmentRequest(); request.setChecklistId(id); request.setDisplayOrder(order);
        return request;
    }

    @Test void hibernateSchemaAndCatalogQueriesWork() {
        String id = checklist("  Kiểm tra  lối thoát ");
        assertEquals("Kiểm tra lối thoát", catalog.getById(id).getContent());
        assertEquals(1, catalog.getAll("LỐI", true, PageRequest.of(0, 20)).getTotalItems());
        assertEquals(1, catalog.getAll(null, null, PageRequest.of(0, 20)).getTotalItems());
        assertEquals(0, catalog.getAll("%", null, PageRequest.of(0, 20)).getTotalItems());
        var request = new ChecklistRequest(); request.setContent("New requirement");
        assertEquals("New requirement", catalog.update(id, request).getContent());
    }

    @Test void manyToManyOrderingAndUnassignAreIndependent() {
        String s1 = service("A"), s2 = service("B"), c1 = checklist("One"), c2 = checklist("Two");
        assignments.assign(s1, assignment(c1, 3));
        assignments.assign(s2, assignment(c1, 0));
        assignments.assign(s1, assignment(c2, 0));
        assertEquals(List.of(c2, c1), assignments.getByService(s1, true).stream().map(r -> r.getChecklistId()).toList());
        assertEquals(2, assignments.getByChecklist(c1).size());
        assignments.unassign(s1, c1);
        assertEquals(1, assignments.getByChecklist(c1).size());
        assertNotNull(catalog.getById(c1));
        assertEquals(1, assignments.getByService(s2, true).size());
    }

    @Test void lifecycleKeepsLinksAndReactivationRestoresTemplate() {
        String s = service("A"), c = checklist("One");
        assignments.assign(s, assignment(c, 0));
        catalog.updateStatus(c, false);
        assertTrue(assignments.getByService(s, true).isEmpty());
        assertEquals(1, assignments.getByService(s, false).size());
        catalog.updateStatus(c, true);
        assertEquals(1, assignments.getByService(s, true).size());
        transaction.executeWithoutResult(status -> {
            var row = services.findByIdForUpdate(s).orElseThrow(); row.setIsActive(false); services.saveAndFlush(row);
        });
        assertThrows(ApiException.class, () -> assignments.getByService(s, true));
        assertEquals(1, assignments.getByService(s, false).size());
        assertEquals(1, links.count());
    }

    @Test void databaseRejectsDuplicatePairAndInvalidForeignKey() {
        String s = service("A"), c = checklist("One");
        assignments.assign(s, assignment(c, 0));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String sql = "INSERT INTO service_checklists(id, service_id, checklist_id, display_order, created_at, version) VALUES (?, ?, ?, 0, now(), 0)";
        var duplicate = assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update(sql, UUID.randomUUID().toString(), s, c));
        assertTrue(duplicate.getMostSpecificCause().getMessage().contains("uk_service_checklist"));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update(sql, UUID.randomUUID().toString(), s, "missing"));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("DELETE FROM checklist_definitions WHERE id = ?", c));
        assertEquals(1, links.count());
    }

    @Test void concurrentAssignmentsProduceExactlyOneRelationship() throws Exception {
        String s = service("A"), c = checklist("One");
        List<Object> results = race(() -> assignments.assign(s, assignment(c, 0)));
        assertEquals(1, results.stream().filter(r -> !(r instanceof Throwable)).count());
        assertEquals(1, results.stream().filter(r -> r instanceof ApiException exception
                && exception.getErrorCode() == ErrorCode.SERVICE_CHECKLIST_ALREADY_EXISTS).count());
        assertEquals(1, links.count());
    }

    @Test void concurrentCatalogCreateIsProtectedByDatabaseUniqueConstraint() throws Exception {
        List<Object> results = race(() -> checklist("Same content"));
        assertEquals(1, results.stream().filter(r -> !(r instanceof Throwable)).count());
        assertEquals(1, results.stream().filter(r -> r instanceof DataIntegrityViolationException
                || r instanceof ApiException exception && exception.getErrorCode() == ErrorCode.CHECKLIST_ALREADY_EXISTS).count());
        assertEquals(1, definitions.count());
    }

    @Test void unicodeLowercaseExpansionFitsNormalizedColumn() {
        String id = checklist("\u0130".repeat(500));
        assertEquals(500, catalog.getById(id).getContent().length());
    }

    @Test void databaseRejectsNegativeOrderAndQueryAvoidsNPlusOne() {
        String s = service("A");
        for (int i = 0; i < 8; i++) assignments.assign(s, assignment(checklist("Item " + i), i));
        var jdbc = new JdbcTemplate(dataSource);
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE service_checklists SET display_order = -1 WHERE service_id = ?", s));
        var statistics = entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        statistics.clear();
        assertEquals(8, assignments.getByService(s, true).size());
        assertEquals(2, statistics.getPrepareStatementCount());
    }

    @Test void assignmentWaitsForChecklistDeactivationAndThenRejectsIt() throws Exception {
        String s = service("A"), c = checklist("One");
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> deactivation = pool.submit(() -> transaction.executeWithoutResult(status -> {
                var row = definitions.findByIdForUpdate(c).orElseThrow();
                row.setIsActive(false); definitions.saveAndFlush(row); locked.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test lock timeout");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt(); throw new IllegalStateException(exception);
                }
            }));
            assertTrue(locked.await(5, TimeUnit.SECONDS));
            Future<?> assign = pool.submit(() -> assignments.assign(s, assignment(c, 0)));
            release.countDown(); deactivation.get(10, TimeUnit.SECONDS);
            var failure = assertThrows(ExecutionException.class, () -> assign.get(10, TimeUnit.SECONDS));
            assertEquals(ErrorCode.CHECKLIST_INACTIVE, ((ApiException) failure.getCause()).getErrorCode());
            assertEquals(0, links.count());
        } finally {
            release.countDown();
        }
    }


    OrderCreateRequest orderRequest(String serviceId) {
        boolean explicitEmpty = assignments.getByService(serviceId, true).isEmpty();
        if (explicitEmpty) {
            // An empty execution scope is valid, but its catalog service must still be priced.
            assignments.assign(serviceId, assignment(checklist("Priced catalog fixture " + serviceId), 0));
        }
        transaction.executeWithoutResult(status -> {
            var em = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
            var role = Role.builder().code(RoleCode.CUSTOMER).name("Customer").active(true).systemRole(true).build();
            em.persist(role);
            var user = User.builder().fullName("Snapshot customer").email("snapshot@test.local").role(role).build();
            em.persist(user);
            var time = PreferredTime.builder().code(PreferredTimeCode.MORNING).name("Morning")
                    .startTime(java.time.LocalTime.of(7, 0)).endTime(java.time.LocalTime.of(11, 0)).build();
            em.persist(time);
            var type = DeliverableType.builder().name("Photos").isActive(true).defaultFormat("JPG").build();
            em.persist(type); em.flush();
            when(resolver.getCurrentUser()).thenReturn(user);
            when(times.findById(time.getId())).thenReturn(Optional.of(time));
            when(deliverables.findById(type.getId())).thenReturn(Optional.of(type));
            when(serviceDeliverables.existsByServiceIdAndDeliverableTypeId(serviceId, type.getId())).thenReturn(true);
        });
        var timeId = new JdbcTemplate(dataSource).queryForObject("select id from preferred_times", String.class);
        var typeId = new JdbcTemplate(dataSource).queryForObject("select id from deliverable_types", String.class);
        return OrderCreateRequest.builder().title("Snapshot order").termsAccepted(true).dataRetentionDays(90).serviceId(serviceId).preferredTimeId(timeId)
                .checklistItems(explicitEmpty ? List.of() : null)
                .monitoringAuthorizationConfirmed(true)
                .monitoringRelationship(com.ondemandmonitoring.order.enums.MonitoringRelationship.OWNER)
                .preferredDateFrom(java.time.LocalDate.now().plusDays(7)).preferredDateTo(java.time.LocalDate.now().plusDays(9))
                .longitude(106.7005).latitude(10.7765)
                .coverageArea(Map.of("type", "Polygon", "coordinates", List.of(List.of(
                        List.of(106.7000, 10.7760), List.of(106.7010, 10.7760),
                        List.of(106.7010, 10.7770), List.of(106.7000, 10.7770), List.of(106.7000, 10.7760)))))
                .deliverables(List.of(OrderDeliverableRequest.builder().deliverableTypeId(typeId)
                        .requirement(Map.of("resolution", "4K")).build())).build();
    }

    OrderChecklistItemRequest selected(String id, String override) {
        var item = new OrderChecklistItemRequest();
        item.setSourceChecklistId(id); item.setContentOverride(override);
        if (id != null) item.setExpectedChecklistVersion(catalog.getById(id).getVersion());
        return item;
    }

    void approveReviewedOrder(String orderId) {
        orderService.reviewFlightArea(orderId, new com.ondemandmonitoring.order.dto.request.FlightAreaReviewRequest(
                com.ondemandmonitoring.order.enums.LegalReviewStatus.NOT_REQUIRED, true, null, "Reviewed test area"));
        orderService.approveOrder(orderId);
    }

    @Test void orderCapturesDefaultsAndCatalogChangesNeverAlterHistory() {
        String s = service("A"), c = checklist("Original");
        assignments.assign(s, assignment(c, 0));
        var result = orderService.createOrder(orderRequest(s));
        assertNotNull(result.getChecklistSnapshotAt());
        assertEquals("Original", result.getChecklistItems().getFirst().getContent());
        assertEquals(2, result.getChecklistItems().getFirst().getEvidencePolicyVersion());
        assertEquals(1, result.getChecklistItems().getFirst().getMinimumEvidenceCount());
        assertEquals(com.ondemandmonitoring.order.enums.RequiredEvidenceType.IMAGE_OR_VIDEO,
                result.getChecklistItems().getFirst().getRequiredEvidenceType());
        String stableId = result.getChecklistItems().getFirst().getId();
        var edit = new ChecklistRequest(); edit.setContent("Changed");
        catalog.update(c, edit);
        catalog.updateStatus(c, false);
        assignments.unassign(s, c);
        var historical = orderService.getOrderById(result.getId()).getChecklistItems().getFirst();
        assertEquals("Original", historical.getContent()); assertEquals(stableId, historical.getId());
        assertEquals(1, orderService.getPendingOrders().getFirst().getChecklistItems().size());
        assertEquals(1, orderService.getMyOrders(null).getFirst().getChecklistItems().size());
        approveReviewedOrder(result.getId());
        assertEquals(stableId, orderService.getApprovedOrders().getFirst().getChecklistItems().getFirst().getId());
    }

    @Test void mixedSnapshotSurvivesCatalogEditDeactivationAndUnassignment() {
        String s = service("History"), c1 = checklist("Check emergency exits"), c2 = checklist("Check PPE");
        assignments.assign(s, assignment(c1, 0)); assignments.assign(s, assignment(c2, 1));
        var request = orderRequest(s);
        request.setChecklistItems(List.of(selected(c1, "Check emergency exits on floors 1-3"),
                selected(c2, null), selected(null, "Check temporary safety barriers")));
        var created = orderService.createOrder(request);
        var before = created.getChecklistItems();
        var persistedSnapshotAt = orderService.getOrderById(created.getId()).getChecklistSnapshotAt();
        var edit = new ChecklistRequest(); edit.setContent("New catalog requirement");
        catalog.update(c1, edit); catalog.updateStatus(c2, false);
        assignments.unassign(s, c1); assignments.unassign(s, c2);
        var reloaded = orderService.getOrderById(created.getId());
        assertEquals(persistedSnapshotAt, reloaded.getChecklistSnapshotAt());
        assertEquals(before.stream().map(item -> item.getId()).toList(),
                reloaded.getChecklistItems().stream().map(item -> item.getId()).toList());
        assertEquals(List.of("Check emergency exits on floors 1-3", "Check PPE", "Check temporary safety barriers"),
                reloaded.getChecklistItems().stream().map(item -> item.getContent()).toList());
        assertEquals(c1, reloaded.getChecklistItems().get(0).getSourceChecklistId());
        assertEquals(c2, reloaded.getChecklistItems().get(1).getSourceChecklistId());
        assertNull(reloaded.getChecklistItems().get(2).getSourceChecklistId());
        assertEquals(com.ondemandmonitoring.order.enums.OrderChecklistSourceType.SERVICE_TEMPLATE,
                reloaded.getChecklistItems().get(0).getSourceType());
        assertEquals(com.ondemandmonitoring.order.enums.OrderChecklistSourceType.CUSTOMER_CUSTOM,
                reloaded.getChecklistItems().get(2).getSourceType());
    }

    @Test void orderSupportsSubsetEditedCustomAndExplicitEmptySnapshots() {
        String s = service("A"), c1 = checklist("Original"), c2 = checklist("Omitted");
        assignments.assign(s, assignment(c1, 0)); assignments.assign(s, assignment(c2, 1));
        var request = orderRequest(s);
        request.setChecklistItems(List.of(selected(c1, "Customer edited"), selected(null, "Customer custom")));
        var result = orderService.createOrder(request);
        assertEquals(List.of("Customer edited", "Customer custom"),
                result.getChecklistItems().stream().map(item -> item.getContent()).toList());
        assertEquals("Original", catalog.getById(c1).getContent());
        request.setChecklistItems(List.of());
        var empty = orderService.createOrder(request);
        assertTrue(empty.getChecklistItems().isEmpty());
        assertNotNull(orderService.getOrderById(empty.getId()).getChecklistSnapshotAt());
    }

    @Test void invalidItemRollsBackOrderAndDeliverables() {
        String s = service("A"), c = checklist("Valid");
        assignments.assign(s, assignment(c, 0));
        var request = orderRequest(s);
        request.setChecklistItems(List.of(selected(c, null), selected(null, " ")));
        assertThrows(ApiException.class, () -> orderService.createOrder(request));
        assertEquals(0, orders.count()); assertEquals(0, snapshots.count());
        assertEquals(0, new JdbcTemplate(dataSource).queryForObject("select count(*) from order_deliverables", Integer.class));
    }

    @Test void snapshotPersistenceFailureRollsBackPreviouslyInsertedItemsAndOrder() {
        String s = service("A");
        var request = orderRequest(s);
        request.setChecklistItems(List.of(selected(null, "Valid"), selected(null, "FAIL")));
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("ALTER TABLE order_checklist_items ADD CONSTRAINT fixture_reject_content CHECK (content <> 'FAIL')");
        try {
            assertThrows(DataIntegrityViolationException.class, () -> orderService.createOrder(request));
            assertEquals(0, orders.count()); assertEquals(0, snapshots.count());
            assertEquals(0, jdbc.queryForObject("select count(*) from order_deliverables", Integer.class));
        } finally {
            jdbc.execute("ALTER TABLE order_checklist_items DROP CONSTRAINT fixture_reject_content");
        }
    }

    @Test void sourceValidationAllowsCatalogAdditionsButRejectsInactiveDuplicateAndStaleTemplates() {
        String s = service("A"), foreign = checklist("Foreign"), c = checklist("Valid");
        assignments.assign(s, assignment(c, 0));
        var request = orderRequest(s);
        request.setChecklistItems(List.of(selected(foreign, null)));
        var added = orderService.createOrder(request);
        assertEquals(foreign, added.getChecklistItems().getFirst().getSourceChecklistId());
        transaction.executeWithoutResult(status -> { snapshots.deleteAllInBatch(); orders.deleteAll(); orders.flush(); });
        request.setChecklistItems(List.of(selected(c, null), selected(c, null)));
        assertThrows(ApiException.class, () -> orderService.createOrder(request));
        request.setChecklistItems(List.of(selected(c, null)));
        catalog.updateStatus(c, false);
        assertThrows(ApiException.class, () -> orderService.createOrder(request));
        catalog.updateStatus(c, true);
        var stale = selected(c, null); stale.setExpectedChecklistVersion(0L);
        request.setChecklistItems(List.of(stale));
        assertThrows(ApiException.class, () -> orderService.createOrder(request));
        assertEquals(0, orders.count());
    }

    @Test void historicalReadUsesOneBatchQueryAndNoCatalogContentJoin() {
        String s = service("A"), c = checklist("Original");
        assignments.assign(s, assignment(c, 0));
        var request = orderRequest(s);
        var first = orderService.createOrder(request);
        var second = orderService.createOrder(request);
        var statistics = entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        statistics.clear();
        var items = snapshotService.getByOrders(List.of(first.getId(), second.getId()));
        assertEquals(2, items.size()); assertEquals(1, statistics.getPrepareStatementCount());
    }

    @Test void templateEditBeforeCatalogLockIsDetectedAndWholeOrderRollsBack() throws Exception {
        String s = service("A"), c = checklist("Original");
        assignments.assign(s, assignment(c, 0));
        var request = orderRequest(s); request.setChecklistItems(List.of(selected(c, null)));
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> editor = pool.submit(() -> transaction.executeWithoutResult(status -> {
                var row = definitions.findByIdForUpdate(c).orElseThrow();
                row.setContent("Concurrent edit"); row.setNormalizedContent("concurrent edit");
                definitions.saveAndFlush(row); locked.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test timeout");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt(); throw new IllegalStateException(exception);
                }
            }));
            assertTrue(locked.await(5, TimeUnit.SECONDS));
            Future<?> customer = pool.submit(() -> orderService.createOrder(request));
            release.countDown(); editor.get(10, TimeUnit.SECONDS);
            var exception = assertThrows(ExecutionException.class, () -> customer.get(10, TimeUnit.SECONDS));
            assertEquals(ErrorCode.ORDER_CHECKLIST_TEMPLATE_CHANGED, ((ApiException) exception.getCause()).getErrorCode());
            assertEquals(0, orders.count()); assertEquals(0, snapshots.count());
        } finally { release.countDown(); }
    }

    @Test void snapshotCaptureRequiresTransactionAndCannotBeRepeated() {
        String s = service("A");
        var request = orderRequest(s);
        var created = orderService.createOrder(request);
        Order detached = orders.findById(created.getId()).orElseThrow();
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
                () -> snapshotService.capture(detached, List.of()));
        assertThrows(ApiException.class, () -> transaction.execute(status -> snapshotService.capture(detached, List.of())));
    }

    @Test void snapshotDatabaseConstraintsProtectSourceAndShape() {
        String s = service("A"), c = checklist("Original");
        assignments.assign(s, assignment(c, 0));
        var created = orderService.createOrder(orderRequest(s));
        var jdbc = new JdbcTemplate(dataSource);
        String sql = "INSERT INTO order_checklist_items(id, order_id, source_checklist_id, content, display_order, source_type, created_at, version, review_status) VALUES (?, ?, ?, 'Copied', 0, ?, now(), 0, 'PENDING')";
        var duplicate = assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(sql,
                UUID.randomUUID().toString(), created.getId(), c, "SERVICE_TEMPLATE"));
        assertTrue(duplicate.getMostSpecificCause().getMessage().contains("uk_order_checklist_source"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(sql,
                UUID.randomUUID().toString(), created.getId(), "missing", "SERVICE_TEMPLATE"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(sql,
                UUID.randomUUID().toString(), created.getId(), c, "CUSTOMER_CUSTOM"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(sql,
                UUID.randomUUID().toString(), created.getId(), null, "SERVICE_TEMPLATE"));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("DELETE FROM checklist_definitions WHERE id = ?", c));
        assertEquals(1, snapshots.count());
    }

    @Test void legacyOrderWithoutSnapshotIsNotBackfilledFromCurrentTemplate() {
        String s = service("A"), c = checklist("Current template");
        assignments.assign(s, assignment(c, 0));
        var request = orderRequest(s); request.setChecklistItems(List.of());
        var created = orderService.createOrder(request);
        new JdbcTemplate(dataSource).update("UPDATE orders SET checklist_snapshot_at = null WHERE id = ?", created.getId());
        var legacy = orderService.getOrderById(created.getId());
        assertNull(legacy.getChecklistSnapshotAt());
        assertTrue(legacy.getChecklistItems().isEmpty());
    }
    List<Object> race(Callable<?> action) throws Exception {
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Callable<Object> call = () -> {
                ready.countDown(); start.await();
                try { return action.call(); } catch (RuntimeException exception) { return exception; }
            };
            Future<Object> first = pool.submit(call), second = pool.submit(call);
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
            return List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        }
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    @EnableJpaAuditing
    @EnableJpaRepositories(basePackageClasses = {ChecklistDefinitionRepository.class, OrderRepository.class})
    static class Config {
        @Bean com.ondemandmonitoring.devicecheck.repository.PersistedPreDeviceCheckRepository preflightRepository() {
            return mock(com.ondemandmonitoring.devicecheck.repository.PersistedPreDeviceCheckRepository.class);
        }
        @Bean com.ondemandmonitoring.delivery.service.IDeliveryWorkflowService deliveryWorkflow() {
            return mock(com.ondemandmonitoring.delivery.service.IDeliveryWorkflowService.class);
        }
        @Bean DataSource dataSource() {
            String url = System.getenv("ODMS_CHECKLIST_TEST_JDBC_URL");
            String user = System.getenv().getOrDefault("ODMS_CHECKLIST_TEST_DB_USER", "checklist_test");
            String password = System.getenv().getOrDefault("ODMS_CHECKLIST_TEST_DB_PASSWORD", "checklist_test_only");
            var base = new DriverManagerDataSource(url, user, password);
            new JdbcTemplate(base).execute("CREATE SCHEMA " + SCHEMA);
            return new DriverManagerDataSource(url + "?currentSchema=" + SCHEMA + ",public", user, password);
        }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan("com.ondemandmonitoring");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create",
                    "hibernate.generate_statistics", "true",
                    "hibernate.default_schema", SCHEMA, "hibernate.jdbc.time_zone", "UTC"));
            return factory;
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }
        @Bean ServiceRepository services(EntityManagerFactory factory) {
            return new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(factory))
                    .getRepository(ServiceRepository.class);
        }
        @Bean com.ondemandmonitoring.pricing.service.ServiceDefaultPriceService defaultPrices(ServiceRepository services,
                ServiceChecklistRepository links, com.ondemandmonitoring.pricing.repository.PricingPolicyRepository pricingPolicies) {
            var calculator = new com.ondemandmonitoring.pricing.service.ChecklistPriceCalculator();
            return new com.ondemandmonitoring.pricing.service.ServiceDefaultPriceService(services, links,
                    new com.ondemandmonitoring.pricing.service.ServicePriceResolver(links, pricingPolicies, calculator));
        }
        @Bean IChecklistService catalog(ChecklistDefinitionRepository repository,
                com.ondemandmonitoring.pricing.service.ServiceDefaultPriceService defaultPrices) {
            var catalog = new ChecklistServiceImpl(repository, Mappers.getMapper(ChecklistMapper.class));
            catalog.setDefaultPrices(defaultPrices);
            return catalog;
        }
        @Bean IServiceChecklistService assignments(ServiceRepository services,
                ChecklistDefinitionRepository checklists, ServiceChecklistRepository links,
                com.ondemandmonitoring.pricing.service.ServiceDefaultPriceService defaultPrices) {
            var assignments = new ServiceChecklistServiceImpl(services, checklists, links,
                    Mappers.getMapper(ServiceChecklistMapper.class));
            assignments.setDefaultPrices(defaultPrices);
            return assignments;
        }
        @Bean AuthenticatedUserResolver resolver() { return mock(AuthenticatedUserResolver.class); }
        @Bean com.ondemandmonitoring.pricing.service.PricingEngine pricingEngine(
                com.ondemandmonitoring.pricing.repository.PricingPolicyRepository policies) {
            return new com.ondemandmonitoring.pricing.service.RuleBasedPricingEngine(policies,
                    mock(com.ondemandmonitoring.pricing.repository.CustomWorkAssessmentRepository.class),
                    new com.ondemandmonitoring.pricing.service.ChecklistPriceCalculator());
        }
        @Bean PreferredTimeRepository times() { return mock(PreferredTimeRepository.class); }
        @Bean DeliverableTypeRepository deliverables() { return mock(DeliverableTypeRepository.class); }
        @Bean ServiceDeliverableRepository serviceDeliverables() { return mock(ServiceDeliverableRepository.class); }
        @Bean MissionResultRepository results() { return mock(MissionResultRepository.class); }
        @Bean com.ondemandmonitoring.pricing.repository.PricingPolicyRepository pricingPolicies(EntityManagerFactory factory) {
            return new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(factory))
                    .getRepository(com.ondemandmonitoring.pricing.repository.PricingPolicyRepository.class);
        }
        @Bean IOrderChecklistSnapshotService snapshots(ServiceRepository services,
                ChecklistDefinitionRepository definitions, ServiceChecklistRepository assignments,
                OrderChecklistItemRepository snapshots, AuthenticatedUserResolver resolver,
                com.ondemandmonitoring.pricing.repository.PricingPolicyRepository pricingPolicies) {
            return new OrderChecklistSnapshotService(services, definitions, assignments, snapshots,
                    Mappers.getMapper(OrderChecklistItemMapper.class), resolver, pricingPolicies,
                    new com.ondemandmonitoring.pricing.service.ChecklistPriceCalculator(),
                    new com.ondemandmonitoring.pricing.service.ServicePriceResolver(assignments, pricingPolicies,
                            new com.ondemandmonitoring.pricing.service.ChecklistPriceCalculator()));
        }
        @Bean IOrderService orderService(OrderRepository orders, ServiceRepository services,
                DeliverableTypeRepository deliverables, ServiceDeliverableRepository links,
                PreferredTimeRepository times, AuthenticatedUserResolver resolver, MissionResultRepository results,
                IOrderChecklistSnapshotService snapshots,
                com.ondemandmonitoring.review.service.OrderReviewRecordInitializer reviewRecords) {
            return new OrderService(orders, services, deliverables, links, times, resolver,
                    mock(com.ondemandmonitoring.user.repository.CustomerProfileRepository.class),
                    Mappers.getMapper(OrderMapper.class), snapshots, reviewRecords,
                    mock(com.ondemandmonitoring.finance.repository.QuoteRepository.class),
                    mock(com.ondemandmonitoring.finance.repository.InvoiceRepository.class));
        }

        @Bean com.ondemandmonitoring.review.service.OrderReviewRecordInitializer reviewRecords() {
            return mock(com.ondemandmonitoring.review.service.OrderReviewRecordInitializer.class);
        }
    }
}
