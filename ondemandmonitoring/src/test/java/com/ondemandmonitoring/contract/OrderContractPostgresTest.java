package com.ondemandmonitoring.contract;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.contract.domain.*;
import com.ondemandmonitoring.contract.dto.*;
import com.ondemandmonitoring.contract.enums.*;
import com.ondemandmonitoring.contract.mapper.OrderContractMapper;
import com.ondemandmonitoring.contract.repository.*;
import com.ondemandmonitoring.contract.service.*;
import com.ondemandmonitoring.contract.service.impl.OrderContractServiceImpl;
import com.ondemandmonitoring.finance.domain.*;
import com.ondemandmonitoring.finance.dto.CommercialExpirationSettingsResponse;
import com.ondemandmonitoring.finance.enums.*;
import com.ondemandmonitoring.finance.repository.InvoiceRepository;
import com.ondemandmonitoring.finance.service.impl.CommercialExpirationSettingsService;
import com.ondemandmonitoring.media.service.IMediaObjectStorage;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.enums.OrderStatus;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import com.ondemandmonitoring.warehouse.domain.PreferredTime;
import com.ondemandmonitoring.warehouse.enums.PreferredTimeCode;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mapstruct.factory.Mappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

@SpringJUnitConfig(OrderContractPostgresTest.Config.class)
@EnabledIfEnvironmentVariable(
    named = "ODMS_CONTRACT_TEST_JDBC_URL",
    matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/odms_contract_verification")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderContractPostgresTest {
  static final String SCHEMA = "contract_verify_" + UUID.randomUUID().toString().replace("-", "");
  static final Instant NOW = Instant.parse("2026-11-14T04:00:00Z");
  static final Map<String, Long> OBJECTS = new ConcurrentHashMap<>();
  @Autowired DataSource dataSource;
  @Autowired PlatformTransactionManager transactions;
  @Autowired EntityManagerFactory emf;
  @Autowired IOrderContractService contracts;
  @Autowired IContractCancellationService cancellations;
  @Autowired ContractSubmissionCleanupService cleanup;
  @Autowired IMediaObjectStorage contextObjects;
  @Autowired AuthenticatedUserResolver users;
  String orderId, invoiceId;
  User customer, manager;
  JdbcTemplate jdbc;
  TransactionTemplate tx;

  @BeforeEach
  void setup() {
    jdbc = new JdbcTemplate(dataSource);
    tx = new TransactionTemplate(transactions);
    jdbc.execute(
        "TRUNCATE contract_submissions, order_contracts, invoices, quotes, orders, users, roles,"
            + " services, preferred_times CASCADE");
    OBJECTS.clear();
    reset(users);
    tx.executeWithoutResult(
        status -> {
          EntityManager em = SharedEntityManagerCreator.createSharedEntityManager(emf);
          Role role = new Role();
          role.setCode(RoleCode.CUSTOMER);
          role.setName("Customer");
          role.setActive(true);
          em.persist(role);
          customer = new User();
          customer.setFullName("Contract test customer");
          customer.setEmail("contract@example.test");
          customer.setRole(role);
          em.persist(customer);
          com.ondemandmonitoring.service.domain.Service service =
              new com.ondemandmonitoring.service.domain.Service();
          service.setName("Contract service");
          em.persist(service);
          PreferredTime time = new PreferredTime();
          time.setCode(PreferredTimeCode.MORNING);
          time.setName("Morning");
          time.setStartTime(LocalTime.of(7, 0));
          time.setEndTime(LocalTime.of(11, 0));
          em.persist(time);
          Order order = new Order();
          order.setTitle("Contract test order");
          order.setCustomer(customer);
          order.setService(service);
          order.setPreferredTime(time);
          order.setDataRetentionDays(30);
          order.setOrderStatus(OrderStatus.APPROVED);
          order.setExecutionPlan(null);
          order.setFlightCompliance(null);
          order.setFlightAreaReview(null);
          em.persist(order);
          Quote quote = new Quote();
          quote.setOrder(order);
          quote.setQuoteVersion(1);
          quote.setStatus(QuoteStatus.ACCEPTED_BY_CUSTOMER);
          quote.setTotalAmount(BigDecimal.valueOf(1000));
          quote.setPackagePrice(BigDecimal.valueOf(1000));
          quote.setAdditionalAmount(BigDecimal.ZERO);
          quote.setDiscountAmount(BigDecimal.ZERO);
          quote.setAdjustmentAmount(BigDecimal.ZERO);
          em.persist(quote);
          Invoice invoice = new Invoice();
          invoice.setInvoiceNumber("INV-CONTRACT-TEST");
          invoice.setOrder(order);
          invoice.setQuote(quote);
          invoice.setTotalAmount(BigDecimal.valueOf(1000));
          invoice.setDepositAmount(BigDecimal.valueOf(300));
          invoice.setPaidAmount(BigDecimal.ZERO);
          invoice.setRemainingAmount(BigDecimal.valueOf(1000));
          invoice.setStatus(InvoiceStatus.ISSUED);
          invoice.setIssuedAt(NOW);
          invoice.setContractRequired(true);
          em.persist(invoice);
          em.flush();
          orderId = order.getId();
          invoiceId = invoice.getId();
        });
    manager = OrderContractServiceTest.user("test-manager", RoleCode.MANAGER);
    actor(manager);
  }

  void actor(User actor) {
    when(users.getCurrentUser()).thenReturn(actor);
    when(users.getCurrentUserId()).thenReturn(actor.getId());
  }

  ContractResponse issue() {
    String quoteId =
        jdbc.queryForObject("select quote_id from invoices where id=?", String.class, invoiceId);
    return contracts.issue(orderId, quoteId, "CTR-TEST", ContractTestFiles.pdf());
  }

  @Test
  void hibernateCreatesActualPostgresContractTypesConstraintsAndHistory() {
    var issued = issue();
    actor(customer);
    var pending = contracts.submit(orderId, issued.version(), ContractTestFiles.pdf());
    assertThat(pending.status()).isEqualTo(ContractStatus.PENDING_VERIFICATION);
    assertThat(
            jdbc.queryForObject(
                "select data_type from information_schema.columns where table_schema=? and"
                    + " table_name='invoices' and column_name='contract_required'",
                String.class,
                SCHEMA))
        .isEqualTo("boolean");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from pg_constraint where conrelid='order_contracts'::regclass and"
                    + " contype='u' and conkey=ARRAY[(select attnum from pg_attribute where"
                    + " attrelid='order_contracts'::regclass and"
                    + " attname='invoice_id')]::smallint[]",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from pg_constraint where conrelid='contract_submissions'::regclass"
                    + " and conname='ck_contract_submission_state'",
                Integer.class))
        .isEqualTo(1);
    actor(manager);
    contracts.requestChanges(
        orderId,
        new ContractReviewRequest(
            pending.version(), pending.submissions().getFirst().id(), "Missing signature"));
    actor(customer);
    var second =
        contracts.submit(orderId, contracts.get(orderId).version(), ContractTestFiles.pdf());
    assertThat(second.submissions()).hasSize(2);
    assertThat(second.submissions().get(1).reviewNote()).isEqualTo("Missing signature");
  }

  @Test
  void concurrentUploadsCreateExactlyOnePendingSubmission() throws Exception {
    var issued = issue();
    actor(customer);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<Boolean>> results = new ArrayList<>();
      for (int i = 0; i < 2; i++)
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  try {
                    contracts.submit(orderId, issued.version(), ContractTestFiles.pdf());
                    return true;
                  } catch (ApiException expected) {
                    return false;
                  }
                }));
      start.countDown();
      int success = 0;
      for (var result : results) if (result.get(30, TimeUnit.SECONDS)) success++;
      assertThat(success).isEqualTo(1);
      assertThat(jdbc.queryForObject("select count(*) from contract_submissions", Integer.class))
          .isEqualTo(1);
      assertThat(OBJECTS).hasSize(2);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void concurrentFinalConfirmationsAreIdempotentAndAtomic() throws Exception {
    var issued = issue();
    actor(customer);
    var pending = contracts.submit(orderId, issued.version(), ContractTestFiles.pdf());
    actor(manager);
    var request =
        new ContractReviewRequest(pending.version(), pending.submissions().getFirst().id(), null);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      var first =
          pool.submit(
              () -> {
                start.await();
                return contracts.confirm(orderId, request);
              });
      var second =
          pool.submit(
              () -> {
                start.await();
                return contracts.confirm(orderId, request);
              });
      start.countDown();
      assertThat(first.get(30, TimeUnit.SECONDS).status()).isEqualTo(ContractStatus.CONFIRMED);
      assertThat(second.get(30, TimeUnit.SECONDS).status()).isEqualTo(ContractStatus.CONFIRMED);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from contract_submissions where status='CONFIRMED'",
                  Integer.class))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "select contract_confirmed_at is not null and deposit_expires_at ="
                      + " contract_confirmed_at + interval '12 hours' from invoices where id=?",
                  Boolean.class,
                  invoiceId))
          .isTrue();
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void rolledBackUploadDeletesOnlyItsOwnPrivateObject() {
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      issue();
                      throw new IllegalStateException("Force rollback");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(OBJECTS).isEmpty();
    assertThat(jdbc.queryForObject("select count(*) from order_contracts", Integer.class)).isZero();
  }

  @Test
  void rolledBackConfirmationDoesNotUnlockTheInvoice() {
    var issued = issue();
    actor(customer);
    var pending = contracts.submit(orderId, issued.version(), ContractTestFiles.pdf());
    actor(manager);
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      contracts.confirm(
                          orderId,
                          new ContractReviewRequest(
                              pending.version(), pending.submissions().getFirst().id(), null));
                      throw new IllegalStateException("Force rollback");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            jdbc.queryForObject(
                "select contract_confirmed_at is null and contract_submitted_at is not null and deposit_expires_at = contract_submitted_at + interval '12 hours' from invoices"
                    + " where id=?",
                Boolean.class,
                invoiceId))
        .isTrue();
    assertThat(contracts.get(orderId).status()).isEqualTo(ContractStatus.PENDING_VERIFICATION);
    assertThat(OBJECTS).hasSize(2);
  }

  @Test
  void preparesQuoteDocumentBeforeInvoiceAndBindsItOnAcceptance() {
    String quoteId = tx.execute(status -> {
      var em = emf.createEntityManager();
      try { return jdbc.queryForObject("select quote_id from invoices where id=?", String.class, invoiceId); }
      finally { em.close(); }
    });
    jdbc.update("delete from invoices where id=?", invoiceId);
    jdbc.update("update quotes set status='DRAFT' where id=?", quoteId);
    actor(manager);
    var prepared = contracts.prepare(orderId, quoteId, 0, "PREPARED-" + UUID.randomUUID(), ContractTestFiles.pdf());
    assertThat(prepared.invoiceId()).isNull();
    assertThat(jdbc.queryForObject("select count(*) from invoices where order_id=?", Integer.class, orderId)).isZero();
    actor(customer);
    assertThatThrownBy(() -> contracts.getForQuote(orderId, quoteId)).isInstanceOf(ApiException.class);
    jdbc.update("update quotes set status='APPROVED' where id=?", quoteId);
    assertThat(contracts.getForQuote(orderId, quoteId).document()).isNotNull();
    assertThatThrownBy(() -> contracts.submit(orderId, prepared.version(), ContractTestFiles.pdf())).isInstanceOf(ApiException.class);
    tx.executeWithoutResult(status -> {
      var em = org.springframework.orm.jpa.EntityManagerFactoryUtils.getTransactionalEntityManager(emf);
      var quote = em.find(Quote.class, quoteId);
      quote.setStatus(QuoteStatus.ACCEPTED_BY_CUSTOMER);
      Invoice accepted = new Invoice(); accepted.setOrder(quote.getOrder()); accepted.setQuote(quote);
      accepted.setInvoiceNumber("INV-PREPARED-" + UUID.randomUUID());
      accepted.setTotalAmount(new BigDecimal("1000")); accepted.setDepositAmount(new BigDecimal("300"));
      accepted.setRemainingAmount(new BigDecimal("1000")); accepted.setPaidAmount(BigDecimal.ZERO);
      accepted.setStatus(InvoiceStatus.ISSUED); accepted.setDepositStatus(DepositStatus.WAITING_DEPOSIT);
      accepted.setIssuedAt(NOW); accepted.setContractRequired(true); em.persist(accepted); em.flush();
      new QuoteContractPolicy(new org.springframework.data.jpa.repository.support.JpaRepositoryFactory(em).getRepository(OrderContractRepository.class),
          new ContractDocumentStorage(contextObjects)).attachInvoice(accepted);
    });
    var after = contracts.get(orderId);
    assertThat(after.id()).isEqualTo(prepared.id());
    assertThat(after.document().sha256()).isEqualTo(prepared.document().sha256());
    assertThat(after.invoiceId()).isNotNull();
    assertThat(OBJECTS).hasSize(1);
  }

  void paidDeposit() {
    tx.executeWithoutResult(status -> {
      var em=SharedEntityManagerCreator.createSharedEntityManager(emf);
      Role role=new Role(); role.setCode(RoleCode.MANAGER); role.setName("Manager"); role.setActive(true); em.persist(role);
      manager=new User(); manager.setFullName("Cancellation manager"); manager.setEmail("manager@example.test"); manager.setRole(role); em.persist(manager);
      var invoice=em.find(Invoice.class,invoiceId); invoice.setPaidAmount(BigDecimal.valueOf(120)); invoice.setRemainingAmount(BigDecimal.valueOf(880)); invoice.setStatus(InvoiceStatus.PARTIALLY_PAID);
      Payment payment=new Payment(); payment.setInvoice(invoice); payment.setPaymentType(PaymentType.DEPOSIT); payment.setProvider(PaymentProvider.VNPAY); payment.setStatus(PaymentStatus.SUCCESS); payment.setAmount(BigDecimal.valueOf(120)); payment.setCurrency("VND"); payment.setPaidAt(NOW); payment.setTransactionReference("123456789012"); payment.setPaymentCode(123456789012L); em.persist(payment);
    });
    actor(manager);
  }

  @Test
  void contractCancellationPersistsARealRefundAndReplaysWithoutDuplicatingMoney() {
    var issued=issue(); paidDeposit();
    var command=new ContractCancellationRequest(UUID.randomUUID(),issued.version(),CancellationResponsibility.SYSTEM,"The service cannot proceed","Recorded agreement and responsibility",false);
    var first=cancellations.request(orderId,command);
    var replay=cancellations.request(orderId,command);
    assertThat(first.refundId()).isNotBlank(); assertThat(replay.id()).isEqualTo(first.id());
    assertThat(jdbc.queryForObject("select count(*) from deposit_refunds",Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select refundable_amount from deposit_refunds",BigDecimal.class)).isEqualByComparingTo("120");
    assertThat(jdbc.queryForObject("select status from deposit_refunds",String.class)).isEqualTo("REQUESTED");
    assertThat(jdbc.queryForObject("select paid_amount from invoices where id=?",BigDecimal.class,invoiceId)).isEqualByComparingTo("120");
    assertThat(jdbc.queryForObject("select order_status from orders where id=?",String.class,orderId)).isEqualTo("CANCELLED");
  }

  @Test
  void aTransactionRollbackDoesNotLeaveARefundOrCancelledContract() {
    var issued=issue(); paidDeposit();
    assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
      cancellations.request(orderId,new ContractCancellationRequest(UUID.randomUUID(),issued.version(),CancellationResponsibility.SYSTEM,"The service cannot proceed","Recorded agreement and responsibility",false));
      throw new IllegalStateException("Force financial rollback");
    })).isInstanceOf(IllegalStateException.class);
    assertThat(jdbc.queryForObject("select count(*) from deposit_refunds",Integer.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from contract_cancellations",Integer.class)).isZero();
    assertThat(contracts.get(orderId).status()).isEqualTo(ContractStatus.AWAITING_SIGNATURE);
  }

  @Test
  void concurrentCancellationCreatesOnlyOneCaseAndOneRefund() throws Exception {
    var issued=issue(); paidDeposit();
    var command=new ContractCancellationRequest(UUID.randomUUID(),issued.version(),CancellationResponsibility.SYSTEM,"The service cannot proceed","Recorded agreement and responsibility",false);
    var pool=Executors.newFixedThreadPool(2); var start=new CountDownLatch(1);
    try {
      var first=pool.submit(() -> { start.await(); return cancellations.request(orderId,command); });
      var second=pool.submit(() -> { start.await(); return cancellations.request(orderId,command); });
      start.countDown(); assertThat(first.get(30,TimeUnit.SECONDS).id()).isEqualTo(second.get(30,TimeUnit.SECONDS).id());
      assertThat(jdbc.queryForObject("select count(*) from deposit_refunds",Integer.class)).isEqualTo(1);
      assertThat(jdbc.queryForObject("select count(*) from contract_cancellations",Integer.class)).isEqualTo(1);
    } finally {pool.shutdownNow();}
  }

  @Test
  void unresolvedCancellationCanBeDecidedLaterWithoutLosingTheOriginalRequest() {
    var issued=issue(); paidDeposit();
    var pending=cancellations.request(orderId,new ContractCancellationRequest(UUID.randomUUID(),issued.version(),CancellationResponsibility.UNRESOLVED,"The parties cannot proceed",null,false));
    assertThat(pending.status()).isEqualTo(ContractCancellationStatus.REVIEW_REQUIRED);
    assertThat(jdbc.queryForObject("select count(*) from deposit_refunds",Integer.class)).isZero();
    var decided=cancellations.decide(orderId,new ContractCancellationDecision(pending.version(),CancellationResponsibility.CUSTOMER,"Documented clause and customer responsibility",true));
    assertThat(decided.status()).isEqualTo(ContractCancellationStatus.DEPOSIT_RETAINED);
    assertThat(jdbc.queryForObject("select requested_responsibility from contract_cancellations",String.class)).isEqualTo("UNRESOLVED");
    assertThat(jdbc.queryForObject("select count(*) from deposit_refunds",Integer.class)).isZero();
    assertThat(jdbc.queryForObject("select paid_amount from invoices where id=?",BigDecimal.class,invoiceId)).isEqualByComparingTo("120");
    assertThat(jdbc.queryForObject("select status from invoices where id=?",String.class,invoiceId)).isEqualTo("CANCELLED");
  }

  @Test
  void paidContractCorrectionKeepsDepositAndFirstSubmissionDeadline() {
    var issued=issue(); actor(customer);
    var pending=contracts.submit(orderId,issued.version(),ContractTestFiles.pdf());
    paidDeposit();
    var changes=contracts.requestChanges(orderId,new ContractReviewRequest(pending.version(),pending.submissions().getFirst().id(),"Please include all signed pages"));
    actor(customer); var resubmitted=contracts.submit(orderId,changes.version(),ContractTestFiles.pdf());
    actor(manager); contracts.confirm(orderId,new ContractReviewRequest(resubmitted.version(),resubmitted.submissions().getFirst().id(),null));
    assertThat(jdbc.queryForObject("select paid_amount from invoices where id=?",BigDecimal.class,invoiceId)).isEqualByComparingTo("120");
    assertThat(jdbc.queryForObject("select deposit_expires_at = contract_submitted_at + interval '12 hours' from invoices where id=?",Boolean.class,invoiceId)).isTrue();
    assertThat(jdbc.queryForObject("select count(*) from contract_submissions",Integer.class)).isEqualTo(2);
  }

  @Test
  void replacementCommitsBeforeCleanupAndStaleManagerReviewCannotConfirmOldFile() {
    var issued=issue(); actor(customer);
    var first=contracts.submit(orderId,issued.version(),ContractTestFiles.pdf());
    paidDeposit();actor(customer);
    var replacement=contracts.submit(orderId,first.version(),ContractTestFiles.pdf());
    assertThat(replacement.version()).isGreaterThan(first.version());
    assertThat(OBJECTS).hasSize(3);
    String oldId=first.submissions().getFirst().id();
    assertThat(jdbc.queryForObject("select status from contract_submissions where id=?",String.class,oldId)).isEqualTo("SUPERSEDED");
    assertThat(jdbc.queryForObject("select reviewed_at is null and superseded_by=? and document_deleted_at is null from contract_submissions where id=?",Boolean.class,customer.getId(),oldId)).isTrue();
    assertThatThrownBy(()->contracts.download(orderId,oldId)).isInstanceOf(ApiException.class);
    assertThat(cleanup.cleanup()).isEqualTo(1);assertThat(cleanup.cleanup()).isZero();
    assertThat(OBJECTS).hasSize(2);
    assertThat(jdbc.queryForObject("select document_deleted_at is not null from contract_submissions where id=?",Boolean.class,oldId)).isTrue();
    actor(manager);
    assertThatThrownBy(()->contracts.confirm(orderId,new ContractReviewRequest(first.version(),oldId,null))).isInstanceOf(ApiException.class);
    assertThat(contracts.confirm(orderId,new ContractReviewRequest(replacement.version(),replacement.submissions().getFirst().id(),null)).status()).isEqualTo(ContractStatus.CONFIRMED);
    assertThat(jdbc.queryForObject("select paid_amount from invoices where id=?",BigDecimal.class,invoiceId)).isEqualByComparingTo("120");
  }

  @Test
  void rolledBackReplacementNeverQueuesOrDeletesTheOriginalPendingFile() {
    var issued=issue();actor(customer);
    var first=contracts.submit(orderId,issued.version(),ContractTestFiles.pdf());
    var pool=Executors.newSingleThreadExecutor();
    try {
      assertThatThrownBy(()->tx.executeWithoutResult(status->{
        contracts.submit(orderId,first.version(),ContractTestFiles.pdf());
        try { assertThat(pool.submit(()->cleanup.cleanup()).get(10,TimeUnit.SECONDS)).isZero(); }
        catch(Exception failure){throw new IllegalStateException(failure);}
        throw new IllegalStateException("Force replacement rollback");
      })).isInstanceOf(IllegalStateException.class);
    } finally {pool.shutdownNow();}
    assertThat(OBJECTS).hasSize(2);assertThat(cleanup.cleanup()).isZero();
    assertThat(jdbc.queryForObject("select count(*) from contract_submissions",Integer.class)).isEqualTo(1);
    assertThat(contracts.get(orderId).version()).isEqualTo(first.version());
    assertThat(contracts.get(orderId).submissions().getFirst().status()).isEqualTo(ContractSubmissionStatus.PENDING);
  }

  @Test
  void concurrentReplacementsCannotBothUseTheSamePendingVersion() throws Exception {
    var issued=issue();actor(customer);var first=contracts.submit(orderId,issued.version(),ContractTestFiles.pdf());
    var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
    try {
      List<Future<Boolean>> results=new ArrayList<>();
      for(int i=0;i<2;i++)results.add(pool.submit(()->{start.await();try{contracts.submit(orderId,first.version(),ContractTestFiles.pdf());return true;}catch(ApiException conflict){return false;}}));
      start.countDown();int success=0;for(var result:results)if(result.get(30,TimeUnit.SECONDS))success++;
      assertThat(success).isEqualTo(1);
      assertThat(jdbc.queryForObject("select count(*) from contract_submissions where status='PENDING'",Integer.class)).isEqualTo(1);
      assertThat(jdbc.queryForObject("select count(*) from contract_submissions where status='SUPERSEDED'",Integer.class)).isEqualTo(1);
      assertThat(OBJECTS).hasSize(3);
    }finally{pool.shutdownNow();}
  }

  @Test
  void concurrentCleanupWorkersDeleteTheOldObjectOnlyOnce() throws Exception {
    var issued=issue();actor(customer);var first=contracts.submit(orderId,issued.version(),ContractTestFiles.pdf());
    contracts.submit(orderId,first.version(),ContractTestFiles.pdf());
    var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
    try{
      var a=pool.submit(()->{start.await();return cleanup.cleanup();});var b=pool.submit(()->{start.await();return cleanup.cleanup();});
      start.countDown();assertThat(a.get(30,TimeUnit.SECONDS)+b.get(30,TimeUnit.SECONDS)).isEqualTo(1);
      assertThat(OBJECTS).hasSize(2);
    }finally{pool.shutdownNow();}
  }

  @Configuration
  @EnableTransactionManagement
  @EnableJpaAuditing
  @EnableJpaRepositories(
      basePackageClasses = {
        OrderContractRepository.class,
        InvoiceRepository.class,
        OrderRepository.class,
        com.ondemandmonitoring.refund.repository.RefundRepository.class
      })
  static class Config {
    @Bean
    DataSource dataSource() {
      String url = System.getenv("ODMS_CONTRACT_TEST_JDBC_URL");
      if (url.contains(":5433/")) throw new IllegalStateException("Dev database is prohibited");
      String user = System.getenv("ODMS_CONTRACT_TEST_DB_USER"),
          password = System.getenv("ODMS_CONTRACT_TEST_DB_PASSWORD");
      var base = new DriverManagerDataSource(url, user, password);
      var jdbc = new JdbcTemplate(base);
      jdbc.execute("CREATE EXTENSION IF NOT EXISTS postgis");
      jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
      jdbc.execute("CREATE SCHEMA " + SCHEMA);
      return new DriverManagerDataSource(
          url + "?currentSchema=" + SCHEMA + ",public", user, password);
    }

    @Bean
    LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
      var factory = new LocalContainerEntityManagerFactoryBean();
      factory.setDataSource(dataSource);
      factory.setPackagesToScan("com.ondemandmonitoring");
      factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
      factory.setJpaPropertyMap(
          Map.of(
              "hibernate.hbm2ddl.auto",
              "create",
              "hibernate.hbm2ddl.halt_on_error",
              "true",
              "hibernate.default_schema",
              SCHEMA,
              "hibernate.jdbc.time_zone",
              "UTC"));
      return factory;
    }

    @Bean
    PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
      return new JpaTransactionManager(factory);
    }

    @Bean
    AuthenticatedUserResolver users() {
      return mock(AuthenticatedUserResolver.class);
    }

    @Bean
    OrderContractMapper mapper() {
      return Mappers.getMapper(OrderContractMapper.class);
    }

    @Bean
    CommercialExpirationSettingsService expiration() {
      var mock = mock(CommercialExpirationSettingsService.class);
      when(mock.current())
          .thenReturn(new CommercialExpirationSettingsResponse(48, 12, "admin", NOW));
      return mock;
    }

    @Bean
    IMediaObjectStorage objects() {
      var storage = mock(IMediaObjectStorage.class);
      when(storage.put(anyString(), anyString(), anyLong(), any(), anyString()))
          .thenAnswer(
              call -> {
                String key = call.getArgument(0);
                Long size = call.getArgument(2);
                OBJECTS.put(key, size);
                return new IMediaObjectStorage.StoredObject("isolated-fake-bucket", key, null);
              });
      when(storage.inspect(anyString(), anyString()))
          .thenAnswer(
              call -> {
                Long size = OBJECTS.get(call.getArgument(1));
                if (size == null) throw new IllegalStateException("Missing test object");
                return new IMediaObjectStorage.StoredObjectInfo(size, "application/pdf", Map.of());
              });
      doAnswer(
              call -> {
                OBJECTS.remove(call.getArgument(1));
                return null;
              })
          .when(storage)
          .deleteQuietly(anyString(), anyString());
      doAnswer(call->{OBJECTS.remove(call.getArgument(1));return null;}).when(storage).delete(anyString(),anyString());
      return storage;
    }

    @Bean ContractSubmissionCleanupService cleanup(ContractSubmissionRepository submissions,OrderContractRepository contracts,IMediaObjectStorage storage,PlatformTransactionManager transactions) {
      return new ContractSubmissionCleanupService(submissions,contracts,storage,transactions,Clock.fixed(NOW.plusSeconds(120),ZoneOffset.UTC));
    }

    @Bean
    ContractDocumentStorage documentStorage(IMediaObjectStorage storage) {
      return new ContractDocumentStorage(storage);
    }

    @Bean
    com.ondemandmonitoring.refund.service.RefundService refunds(
        com.ondemandmonitoring.refund.repository.RefundRepository refunds,
        com.ondemandmonitoring.refund.repository.RefundTransferAttemptRepository attempts,
        com.ondemandmonitoring.refund.repository.RefundReceiptRepository receipts,
        OrderRepository orders,InvoiceRepository invoices,com.ondemandmonitoring.finance.repository.PaymentRepository payments,
        AuthenticatedUserResolver users,IMediaObjectStorage storage,org.springframework.context.ApplicationEventPublisher events,
        ContractCancellationRepository cancellations) {
      return new com.ondemandmonitoring.refund.service.RefundService(refunds,attempts,receipts,orders,invoices,payments,
        mock(com.ondemandmonitoring.finance.service.impl.MissionPaymentMilestoneService.class),mock(com.ondemandmonitoring.mission.repository.MissionRepository.class),users,storage,events,cancellations);
    }

    @Bean
    IContractCancellationService cancellations(OrderRepository orders,InvoiceRepository invoices,OrderContractRepository contracts,
        ContractCancellationRepository cancellations,com.ondemandmonitoring.finance.repository.PaymentRepository payments,
        com.ondemandmonitoring.refund.service.RefundService refunds,AuthenticatedUserResolver users) {
      return new com.ondemandmonitoring.contract.service.impl.ContractCancellationServiceImpl(orders,invoices,contracts,cancellations,payments,
        mock(com.ondemandmonitoring.mission.repository.MissionRepository.class),refunds,users,Clock.fixed(NOW,ZoneOffset.UTC));
    }

    @Bean
    IOrderContractService contracts(
        OrderRepository orders,
        InvoiceRepository invoices,
        OrderContractRepository contracts,
        ContractSubmissionRepository submissions,
        AuthenticatedUserResolver users,
        ContractDocumentStorage storage,
        OrderContractMapper mapper,
        CommercialExpirationSettingsService expiration,
        com.ondemandmonitoring.finance.repository.QuoteRepository contextQuotes) {
      return new OrderContractServiceImpl(
          orders,
          invoices,
          contracts,
          submissions,
          users,
          storage,
          mapper,
          expiration,
          Clock.fixed(NOW, ZoneOffset.UTC),
          contextQuotes);
    }
  }
}
