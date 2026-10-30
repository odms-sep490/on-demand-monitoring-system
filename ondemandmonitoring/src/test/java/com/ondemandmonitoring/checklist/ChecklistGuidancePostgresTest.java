package com.ondemandmonitoring.checklist;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.delivery.repository.OrderDeliveryRepository;
import com.ondemandmonitoring.finance.config.PaymentProperties;
import com.ondemandmonitoring.finance.dto.*;
import com.ondemandmonitoring.finance.enums.QuoteStatus;
import com.ondemandmonitoring.finance.repository.*;
import com.ondemandmonitoring.finance.service.impl.QuoteService;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.mission.service.IMissionChecklistExecutionService;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import com.ondemandmonitoring.order.dto.request.*;
import com.ondemandmonitoring.order.enums.*;
import com.ondemandmonitoring.order.mapper.OrderChecklistItemMapper;
import com.ondemandmonitoring.order.repository.*;
import com.ondemandmonitoring.order.service.impl.*;
import com.ondemandmonitoring.pricing.dto.*;
import com.ondemandmonitoring.pricing.enums.PricingCategory;
import com.ondemandmonitoring.pricing.repository.*;
import com.ondemandmonitoring.pricing.service.*;
import com.ondemandmonitoring.review.service.IOrderReadinessService;
import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mapstruct.factory.Mappers;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt-in PostgreSQL verification. No application context scanning or shared database writes. */
@SpringJUnitConfig({ChecklistPostgresTest.Config.class, ChecklistGuidancePostgresTest.GuidanceConfig.class})
@EnabledIfEnvironmentVariable(named = "ODMS_CHECKLIST_TEST_JDBC_URL",
        matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/odms_checklist_verification")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ChecklistGuidancePostgresTest {
    @Autowired ConfigurableApplicationContext context;
    @Autowired QuoteService finance;
    @Autowired QuoteRepository quotes;
    @Autowired InvoiceRepository invoices;
    @Autowired OrderChecklistUpdateService customerEdits;
    @Autowired GuidancePricing guidancePricing;
    final ChecklistPostgresTest fixture = new ChecklistPostgresTest();
    final ThreadLocal<User> actor = new ThreadLocal<>();
    User manager, customer;
    JdbcTemplate jdbc;
    String orderId, itemId;
    static final BigDecimal PRICE = new BigDecimal("100000");

    @BeforeEach void prepare() {
        context.getAutowireCapableBeanFactory().autowireBean(fixture);
        fixture.prepare();
        jdbc = new JdbcTemplate(fixture.dataSource);
        var request = fixture.orderRequest(fixture.service("Guidance verification"));
        customer = fixture.resolver.getCurrentUser();
        request.setChecklistItems(List.of(fixture.selected(null, "Inspect the customer roof")));
        orderId = fixture.orderService.createOrder(request).getId();
        itemId = fixture.snapshots.findAllByOrderIdOrderByDisplayOrderAscIdAsc(orderId).getFirst().getId();
        fixture.transaction.executeWithoutResult(tx -> {
            var em = SharedEntityManagerCreator.createSharedEntityManager(fixture.entityManagerFactory);
            var role = Role.builder().code(RoleCode.MANAGER).name("Manager").active(true).systemRole(true).build();
            em.persist(role);
            manager = User.builder().fullName("Guidance manager").email("guidance@test.local").role(role).build();
            em.persist(manager); em.flush();
            var order = fixture.orders.findById(orderId).orElseThrow();
            order.setLegalReviewStatus(LegalReviewStatus.NOT_REQUIRED);
            var item = fixture.snapshots.findById(itemId).orElseThrow();
            item.setReviewStatus(ChecklistReviewStatus.ADDITIONAL);
            item.setManagerQuotedPrice(PRICE);
        });
        // Each concurrent caller has an independent authenticated identity.
        when(fixture.resolver.getCurrentUser()).thenAnswer(call -> actor.get() == null ? manager : actor.get());
        when(fixture.resolver.getCurrentUserId()).thenAnswer(call -> fixture.resolver.getCurrentUser().getId());
        reset(guidancePricing.engine);
        String policyId = jdbc.queryForObject("select id from pricing_policies where status='ACTIVE' order by policy_version desc limit 1", String.class);
        Integer policyVersion = jdbc.queryForObject("select policy_version from pricing_policies where id=?", Integer.class, policyId);
        when(guidancePricing.engine.calculate(any(), anyList())).thenReturn(new PricingBreakdown(
                policyId, policyVersion, "VND", PRICE, true, true,
                List.of(new PricingBreakdownItem(PricingCategory.CHECKLIST, "Roof inspection", BigDecimal.ONE,
                        PRICE, PRICE, itemId))));
        assertTrue(AopUtils.isAopProxy(finance), "QuoteService must commit/roll back through its transaction proxy");
        assertTrue(AopUtils.isAopProxy(customerEdits));
    }

    @AfterEach void clearActor() { actor.remove(); }
    @AfterAll void cleanup() { fixture.cleanup(); }

    ChecklistGuidanceRequest guidance(long version, String description) {
        return new ChecklistGuidanceRequest(1L, version, description,
                List.of(" Photograph roof ", "", "Photograph roof"),
                List.of("Customer identifies roof"), List.of("Annotated roof photos"), true);
    }

    OrderChecklistItem item() { return fixture.snapshots.findById(itemId).orElseThrow(); }

    String draft() {
        return finance.saveDraft(orderId, new QuoteDraftRequest(BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, null, Map.of(itemId, PRICE))).id();
    }

    String approved() {
        finance.saveChecklistGuidance(orderId, itemId, guidance(0, "Original scope"));
        String id = draft(); finance.approve(orderId, id); return id;
    }

    <T> T as(User user, Callable<T> call) {
        User previous = actor.get(); actor.set(user);
        try { return call.call(); }
        catch (RuntimeException exception) { throw exception; }
        catch (Exception exception) { throw new IllegalStateException(exception); }
        finally { if (previous == null) actor.remove(); else actor.set(previous); }
    }

    @Test void normalizedGuidanceAndQuoteSnapshotRoundTripAndApprovedInvalidationPreservesHistory() {
        finance.saveChecklistGuidance(orderId, itemId, guidance(0, "  Original scope  "));
        var saved = item();
        assertEquals("Original scope", saved.getCustomGuidance().description());
        assertEquals(List.of("Photograph roof"), saved.getCustomGuidance().executionSteps());
        assertEquals(1L, saved.getGuidanceVersion());
        assertEquals(Long.valueOf(1), saved.getGuidanceConfirmedVersion());
        assertEquals(manager.getId(), saved.getGuidanceUpdatedBy().getId());
        assertNotNull(saved.getGuidanceUpdatedAt());
        assertEquals("object", jdbc.queryForObject("select jsonb_typeof(custom_guidance) from order_checklist_items where id=?", String.class, itemId));
        finance.saveChecklistGuidance(orderId, itemId, guidance(1, "Original scope"));
        assertEquals(saved.getGuidanceUpdatedAt(), item().getGuidanceUpdatedAt(), "Normalized no-op must keep audit time");
        assertEquals(1, item().getGuidanceVersion());
        String quoteId = draft(); finance.approve(orderId, quoteId);
        var snapshot = quotes.findById(quoteId).orElseThrow().getChecklistGuidanceSnapshot();
        assertEquals(1, snapshot.schemaVersion());
        assertEquals(itemId, snapshot.items().getFirst().itemId());
        assertEquals(1, snapshot.items().getFirst().checklistRevision());
        assertEquals(1, snapshot.items().getFirst().guidanceVersion());
        assertEquals("Original scope", snapshot.items().getFirst().description());
        assertEquals(snapshot, quotes.findGuidanceQuotesForOrders(List.of(orderId), List.of(QuoteStatus.APPROVED))
                .getFirst().getChecklistGuidanceSnapshot());
        assertEquals(snapshot, as(customer, () -> finance.getCurrentForCustomer(orderId)).checklistGuidanceSnapshot());
        String persisted = jdbc.queryForObject("select checklist_guidance_snapshot::text from quotes where id=?", String.class, quoteId);
        finance.saveChecklistGuidance(orderId, itemId, guidance(1, "Revised scope"));
        var superseded = quotes.findById(quoteId).orElseThrow();
        assertEquals(QuoteStatus.SUPERSEDED, superseded.getStatus());
        assertNull(superseded.getApprovedAt()); assertNull(superseded.getApprovedBy());
        assertNull(superseded.getPricingApprovedAt()); assertNull(superseded.getPricingApprovedBy());
        assertEquals(persisted, jdbc.queryForObject("select checklist_guidance_snapshot::text from quotes where id=?", String.class, quoteId));
        assertEquals(snapshot, superseded.getChecklistGuidanceSnapshot());
        assertEquals(2, item().getGuidanceVersion());
        error(ErrorCode.QUOTE_NOT_APPROVED, () -> as(customer, () -> finance.accept(orderId, quoteId)));
        assertEquals(0, invoices.count());
        String replacement = draft();
        assertNotEquals(quoteId, replacement);
        assertEquals("Revised scope", quotes.findById(replacement).orElseThrow().getChecklistGuidanceSnapshot().items().getFirst().description());
    }

    @Test void guidanceSaveWaitsForCustomerRevisionAndRejectsStaleRevisionWithoutChangingHistoricalJson() throws Exception {
        finance.saveChecklistGuidance(orderId, itemId, guidance(0, "Original scope"));
        String before = jdbc.queryForObject("select custom_guidance::text from order_checklist_items where id=?", String.class, itemId);
        var edited = new OrderChecklistUpdateItemRequest();
        edited.setExistingItemId(itemId); edited.setContentOverride("Inspect the revised roof area");
        var request = new OrderChecklistUpdateRequest(); request.setExpectedRevision(1L); request.setItems(List.of(edited));
        blockedBehindOrderLock(() -> finance.saveChecklistGuidance(orderId, itemId, guidance(1, "Losing draft")),
                () -> as(customer, () -> customerEdits.updateChecklist(orderId, request)), ErrorCode.STALE_PRICING_REVISION);
        assertEquals(2, fixture.orders.findById(orderId).orElseThrow().getChecklistRevision());
        assertEquals(before, jdbc.queryForObject("select custom_guidance::text from order_checklist_items where id=?", String.class, itemId));
        var next = fixture.snapshots.findAllByOrderIdAndChecklistRevisionOrderByDisplayOrderAscIdAsc(orderId, 2).getFirst();
        assertEquals(item().getCustomGuidance(), next.getCustomGuidance());
        assertEquals(1, next.getGuidanceVersion()); assertNull(next.getGuidanceConfirmedVersion());
    }

    @Test void guidanceSaveWaitsForActualAcceptanceAndCannotChangeAcceptedSnapshot() throws Exception {
        String quoteId = approved();
        var snapshot = quotes.findById(quoteId).orElseThrow().getChecklistGuidanceSnapshot();
        blockedBehindOrderLock(() -> finance.saveChecklistGuidance(orderId, itemId, guidance(1, "Losing draft")),
                () -> as(customer, () -> finance.accept(orderId, quoteId)), ErrorCode.CHECKLIST_GUIDANCE_EDIT_BLOCKED);
        assertEquals(QuoteStatus.ACCEPTED_BY_CUSTOMER, quotes.findById(quoteId).orElseThrow().getStatus());
        assertEquals(1, invoices.count());
        assertEquals(1, item().getGuidanceVersion());
        assertEquals(snapshot, as(customer, () -> finance.getCurrentForCustomer(orderId)).checklistGuidanceSnapshot());
    }

    @Test void acceptanceWaitsForGuidanceSaveThenRejectsSupersededApprovedQuote() throws Exception {
        String quoteId = approved();
        blockedBehindOrderLock(() -> as(customer, () -> finance.accept(orderId, quoteId)),
                () -> finance.saveChecklistGuidance(orderId, itemId, guidance(1, "Winning draft")), ErrorCode.QUOTE_NOT_APPROVED);
        assertEquals(QuoteStatus.SUPERSEDED, quotes.findById(quoteId).orElseThrow().getStatus());
        assertEquals(2, item().getGuidanceVersion()); assertEquals(0, invoices.count());
    }

    @Test void approvalWaitsForGuidanceSaveThenRejectsSupersededDraft() throws Exception {
        finance.saveChecklistGuidance(orderId, itemId, guidance(0, "Original scope"));
        String quoteId = draft();
        blockedBehindOrderLock(() -> finance.approve(orderId, quoteId),
                () -> finance.saveChecklistGuidance(orderId, itemId, guidance(1, "Winning draft")), ErrorCode.QUOTE_NOT_CURRENT);
        assertEquals(QuoteStatus.SUPERSEDED, quotes.findById(quoteId).orElseThrow().getStatus());
        assertEquals(0, invoices.count());
    }

    @Test void databaseFailureRollsBackGuidanceAuditVersionAndQuoteInvalidationTogether() {
        String quoteId = approved();
        var before = item();
        var snapshot = quotes.findById(quoteId).orElseThrow().getChecklistGuidanceSnapshot();
        jdbc.execute("alter table order_checklist_items add constraint fixture_guidance_failure check (guidance_version < 2)");
        try {
            assertThrows(DataIntegrityViolationException.class,
                    () -> finance.saveChecklistGuidance(orderId, itemId, guidance(1, "Must roll back")));
            var after = item();
            assertEquals(before.getCustomGuidance(), after.getCustomGuidance());
            assertEquals(before.getGuidanceVersion(), after.getGuidanceVersion());
            assertEquals(before.getGuidanceConfirmedVersion(), after.getGuidanceConfirmedVersion());
            assertEquals(before.getGuidanceUpdatedAt(), after.getGuidanceUpdatedAt());
            var quote = quotes.findById(quoteId).orElseThrow();
            assertEquals(QuoteStatus.APPROVED, quote.getStatus()); assertNotNull(quote.getApprovedAt());
            assertEquals(snapshot, quote.getChecklistGuidanceSnapshot()); assertEquals(0, invoices.count());
        } finally { jdbc.execute("alter table order_checklist_items drop constraint fixture_guidance_failure"); }
    }

    @Test void invoiceInsertFailureRollsBackAcceptanceAndRetainsTheSameCustomerSnapshot() {
        String quoteId = approved();
        var snapshot = quotes.findById(quoteId).orElseThrow().getChecklistGuidanceSnapshot();
        jdbc.execute("alter table invoices add constraint fixture_invoice_failure check (total_amount < 0)");
        try {
            assertThrows(DataIntegrityViolationException.class, () -> as(customer, () -> finance.accept(orderId, quoteId)));
            var quote = quotes.findById(quoteId).orElseThrow();
            assertEquals(QuoteStatus.APPROVED, quote.getStatus()); assertNull(quote.getAcceptedAt());
            assertEquals(snapshot, quote.getChecklistGuidanceSnapshot()); assertEquals(0, invoices.count());
        } finally { jdbc.execute("alter table invoices drop constraint fixture_invoice_failure"); }
        as(customer, () -> finance.accept(orderId, quoteId));
        assertEquals(1, invoices.count());
        assertEquals(snapshot, as(customer, () -> finance.getCurrentForCustomer(orderId)).checklistGuidanceSnapshot());
    }

    void error(ErrorCode code, Runnable call) {
        assertEquals(code, assertThrows(ApiException.class, call::run).getErrorCode());
    }

    /** The winner holds a real Order lock; observe the loser waiting before invoking the winner's service. */
    void blockedBehindOrderLock(Callable<?> loser, Callable<?> winner, ErrorCode expected) throws Exception {
        var pool = Executors.newSingleThreadExecutor();
        Future<?>[] pending = new Future<?>[1];
        try {
            fixture.transaction.executeWithoutResult(tx -> {
                fixture.orders.findByIdForUpdate(orderId).orElseThrow();
                int ownerPid = jdbc.queryForObject("select pg_backend_pid()", Integer.class);
                pending[0] = pool.submit(loser);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                boolean waiting = false;
                while (System.nanoTime() < deadline && !pending[0].isDone()) {
                    waiting = Boolean.TRUE.equals(jdbc.queryForObject(
                            "select exists(select 1 from pg_locks where not granted and ? = any(pg_blocking_pids(pid)))",
                            Boolean.class, ownerPid));
                    if (waiting) break;
                    try { Thread.sleep(25); }
                    catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
                }
                assertTrue(waiting, "Competing service must be waiting on the held PostgreSQL Order lock");
                as(manager, winner);
            });
            var failure = assertThrows(ExecutionException.class, () -> pending[0].get(12, TimeUnit.SECONDS));
            assertInstanceOf(ApiException.class, failure.getCause());
            assertEquals(expected, ((ApiException) failure.getCause()).getErrorCode());
        } finally {
            pool.shutdownNow(); assertTrue(pool.awaitTermination(12, TimeUnit.SECONDS));
        }
    }

    // Keep the finance stub out of Spring's PricingEngine candidates: the reused snapshot
    // fixture must retain its real engine during both order creation and customer revision.
    static final class GuidancePricing {
        final PricingEngine engine = mock(PricingEngine.class);
    }

    @Configuration
    static class GuidanceConfig {
        static <T> T repository(EntityManagerFactory factory, Class<T> type) {
            return new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(factory)).getRepository(type);
        }
        @Bean QuoteRepository guidanceQuotes(EntityManagerFactory factory) { return repository(factory, QuoteRepository.class); }
        @Bean InvoiceRepository guidanceInvoices(EntityManagerFactory factory) { return repository(factory, InvoiceRepository.class); }
        @Bean GuidancePricing guidancePricing() { return new GuidancePricing(); }
        @Bean QuoteService guidanceFinance(OrderRepository orders, OrderChecklistItemRepository items,
                QuoteRepository quotes, InvoiceRepository invoices, AuthenticatedUserResolver resolver,
                GuidancePricing pricing, PricingPolicyRepository policies) {
            return new QuoteService(orders, items, Mappers.getMapper(OrderChecklistItemMapper.class), quotes, invoices,
                    mock(MissionRepository.class), mock(IMissionChecklistExecutionService.class), resolver,
                    new PaymentProperties(new BigDecimal("0.30")), mock(OrderDeliveryRepository.class), mock(IOrderReadinessService.class),
                    pricing.engine, policies, mock(ChecklistPricingAuditRepository.class));
        }
        @Bean OrderChecklistUpdateService guidanceCustomerEdits(OrderRepository orders,
                com.ondemandmonitoring.service.repository.ServiceRepository services,
                com.ondemandmonitoring.checklist.repository.ChecklistDefinitionRepository definitions,
                com.ondemandmonitoring.checklist.repository.ServiceChecklistRepository assignments,
                OrderChecklistItemRepository items, OrderChecklistRevisionRepository revisions,
                QuoteRepository quotes, InvoiceRepository invoices, PricingPolicyRepository policies,
                com.ondemandmonitoring.order.service.IOrderChecklistSnapshotService snapshots,
                AuthenticatedUserResolver resolver, com.ondemandmonitoring.order.service.IOrderService orderService) {
            var calculator = new ChecklistPriceCalculator();
            return new OrderChecklistUpdateService(orders, services, definitions, assignments, items, revisions, quotes,
                    policies, calculator, new ServicePriceResolver(assignments, policies, calculator),
                    (OrderChecklistSnapshotService) snapshots, new OrderChecklistEditPolicy(quotes, invoices,
                    mock(MissionRepository.class), mock(com.ondemandmonitoring.mission.repository.MissionChecklistAssignmentRepository.class),
                    mock(com.ondemandmonitoring.mission.repository.MissionChecklistExecutionRepository.class)), resolver, orderService);
        }
    }
}
