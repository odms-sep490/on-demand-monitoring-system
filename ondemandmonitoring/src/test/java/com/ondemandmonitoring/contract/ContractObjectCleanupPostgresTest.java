package com.ondemandmonitoring.contract;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ondemandmonitoring.contract.service.*;
import com.ondemandmonitoring.contract.validation.ContractPdfValidator;
import com.ondemandmonitoring.media.cleanup.*;
import com.ondemandmonitoring.media.service.IMediaObjectStorage;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Real PostgreSQL rollback, locking and outbox worker; random schema, fake object storage. */
@EnabledIfEnvironmentVariable(named = "ODMS_OBJECT_CLEANUP_PG", matches = "true")
class ContractObjectCleanupPostgresTest {
  JdbcTemplate admin, db;
  String schema;
  TransactionTemplate tx;
  ObjectCleanupOutbox outbox;
  ObjectCleanupProperties properties;
  ContractObjectCleanupGuard uploads;
  ContractObjectCleanupGuard.Superseded superseded;
  IMediaObjectStorage storage;
  ObjectCleanupWorker worker;
  String key = "order-contracts/orders/order/old.pdf";
  Clock clock = Clock.fixed(Instant.parse("2026-11-27T00:00:00Z"), ZoneOffset.UTC);

  @BeforeEach
  void setup() throws Exception {
    String url = System.getenv("ODMS_TEST_JDBC_URL");
    String user = System.getenv("ODMS_TEST_DB_USER"), password = System.getenv("ODMS_TEST_DB_PASSWORD");
    admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
    schema = "qa_contract_cleanup_" + UUID.randomUUID().toString().replace("-", "");
    admin.execute("CREATE SCHEMA " + schema);
    var source = new DriverManagerDataSource(url.replaceAll("(?i)([?&])currentSchema=[^&]*", "$1")
        .replace("?&", "?").replaceAll("[?&]$", ""), user, password);
    var settings = new Properties(); settings.setProperty("currentSchema", schema);
    source.setConnectionProperties(settings);
    db = new JdbcTemplate(source);
    assertThat(db.queryForObject("SHOW search_path", String.class)).isEqualTo(schema);
    db.execute("CREATE TABLE orders(id varchar(255) PRIMARY KEY)");
    db.execute("CREATE TABLE quotes(id varchar(255) PRIMARY KEY,order_id varchar(255) REFERENCES orders)");
    db.execute("CREATE TABLE order_contracts(id varchar(255) PRIMARY KEY,quote_id varchar(255) REFERENCES quotes,storage_bucket varchar(255),storage_key varchar(1000))");
    db.execute("CREATE TABLE contract_submissions(id varchar(255) PRIMARY KEY,contract_id varchar(255) REFERENCES order_contracts,submission_number integer,status varchar(30),storage_bucket varchar(255),storage_key varchar(1000),superseded_at timestamptz,cleanup_next_attempt_at timestamptz,document_deleted_at timestamptz)");
    String bootstrap;
    try (var input = getClass().getResourceAsStream("/seeddata/migrations/bootstrap_after_hibernate.sql")) {
      bootstrap = new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
    }
    int start = bootstrap.indexOf("CREATE TABLE IF NOT EXISTS public.object_cleanup_outbox");
    assertThat(start).isGreaterThanOrEqualTo(0);
    db.execute(bootstrap.substring(start, bootstrap.indexOf("COMMIT;", start)).replace("public.", ""));
    var manager = new DataSourceTransactionManager(source);
    tx = new TransactionTemplate(manager);
    properties = new ObjectCleanupProperties(); properties.setMaxAttempts(1);
    outbox = new ObjectCleanupOutbox(db, manager, properties);
    uploads = new ContractObjectCleanupGuard(db);
    superseded = new ContractObjectCleanupGuard.Superseded(db, clock);
    storage = mock(IMediaObjectStorage.class);
    when(storage.bucket()).thenReturn("bucket");
    worker = new ObjectCleanupWorker(outbox, storage, properties, List.of(uploads, superseded));
    db.update("INSERT INTO orders VALUES ('order')");
    db.update("INSERT INTO quotes VALUES ('quote','order')");
    db.update("INSERT INTO order_contracts VALUES ('contract','quote','bucket','official.pdf')");
    db.update("INSERT INTO contract_submissions VALUES ('old','contract',1,'SUPERSEDED','bucket',?,'2026-11-26T23:00:00Z','2026-11-26T23:00:00Z',NULL)", key);
    db.update("INSERT INTO contract_submissions VALUES ('new','contract',2,'PENDING','bucket','new.pdf',NULL,NULL,NULL)");
  }

  @AfterEach
  void cleanup() {
    if (schema != null) {
      assertThat(schema).matches("qa_contract_cleanup_[a-f0-9]{32}");
      admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }
  }

  ObjectCleanupGuard.Decision inspectUpload(String candidate) {
    return tx.execute(t -> uploads.inspect("bucket", candidate, "order"));
  }

  ObjectCleanupGuard.Decision inspectSuperseded() {
    return tx.execute(t -> superseded.inspect("bucket", key, "old"));
  }

  void due() {
    db.update("UPDATE object_cleanup_outbox SET next_attempt_at=now()-interval '1 second' WHERE status='PENDING'");
  }

  @Test
  void failedReferenceSaveLeavesDurableReservationAndWorkerRecoversAfterRestart() {
    var documents = new ContractDocumentStorage(storage, outbox);
    tx.executeWithoutResult(t -> {
      var document = documents.store("order", ContractPdfValidator.validate(ContractTestFiles.pdf()));
      assertThat(document.getBucket()).isEqualTo("bucket");
      assertThat(db.queryForObject("SELECT status FROM object_cleanup_outbox", String.class)).isEqualTo("CANCELLED");
      t.setRollbackOnly();
    });
    assertThat(db.queryForObject("SELECT status FROM object_cleanup_outbox", String.class)).isEqualTo("PENDING");
    due();
    var restartedOutbox = new ObjectCleanupOutbox(db,
        Objects.requireNonNull(tx.getTransactionManager()), properties);
    var restartedWorker = new ObjectCleanupWorker(restartedOutbox, storage, properties,
        List.of(new ContractObjectCleanupGuard(db), new ContractObjectCleanupGuard.Superseded(db, clock)));
    assertThat(restartedWorker.poll()).isEqualTo(1);
    verify(storage).delete(eq("bucket"), startsWith("order-contracts/orders/order/"));
    assertThat(db.queryForObject("SELECT status FROM object_cleanup_outbox", String.class)).isEqualTo("COMPLETED");
  }

  @Test
  void committedUploadRetainsReferenceAndCancelsItsProvisionalTask() {
    tx.executeWithoutResult(t -> {
      var document = new ContractDocumentStorage(storage, outbox).store("order", ContractPdfValidator.validate(ContractTestFiles.pdf()));
      db.update("UPDATE order_contracts SET storage_key=? WHERE id='contract'", document.getKey());
    });
    due();
    assertThat(worker.poll()).isZero();
    verify(storage, never()).delete(any(), any());
  }

  @Test
  void reservationLockProtectsInFlightUploadAfterItsGraceExpires() throws Exception {
    String task = outbox.reserveUpload("bucket", "order-contracts/orders/order/inflight.pdf", uploads.type(), "order");
    due();
    var pool = Executors.newSingleThreadExecutor();
    try {
      tx.executeWithoutResult(t -> {
        outbox.protectReservation(task);
        try { assertThat(pool.submit(worker::poll).get(10, TimeUnit.SECONDS)).isZero(); }
        catch (Exception e) { throw new IllegalStateException(e); }
        outbox.cancelReservation(task);
      });
    } finally { pool.shutdownNow(); }
    assertThat(outbox.status(task)).isEqualTo("CANCELLED");
    verify(storage, never()).delete(any(), any());
  }

  @Test
  void officialContractsOnEveryQuoteAndAllSubmissionStatusesAreProtected() {
    db.update("UPDATE order_contracts SET storage_key=?", key);
    assertThat(inspectUpload(key)).isEqualTo(ObjectCleanupGuard.Decision.REFERENCED);
    assertThat(inspectSuperseded()).isEqualTo(ObjectCleanupGuard.Decision.REFERENCED);
    db.update("UPDATE order_contracts SET storage_key='official.pdf'");
    for (String state : List.of("PENDING", "CONFIRMED", "CHANGES_REQUESTED", "SUPERSEDED")) {
      db.update("UPDATE contract_submissions SET status=? WHERE id='old'", state);
      assertThat(inspectUpload(key)).isEqualTo(ObjectCleanupGuard.Decision.REFERENCED);
    }
  }

  @Test
  void onlySupersededSubmissionWithACommittedReplacementCanBeDeleted() {
    assertThat(inspectSuperseded()).isEqualTo(ObjectCleanupGuard.Decision.DELETE);
    db.update("DELETE FROM contract_submissions WHERE id='new'");
    assertThat(inspectSuperseded()).isEqualTo(ObjectCleanupGuard.Decision.REFERENCED);
  }

  @Test
  void officialDocumentOnAnotherOrderOrQuoteAlsoProtectsTheObject() {
    db.update("INSERT INTO orders VALUES ('other')");
    db.update("INSERT INTO quotes VALUES ('other-quote','other')");
    db.update("INSERT INTO order_contracts VALUES ('other-contract','other-quote','bucket',?)", key);
    assertThat(inspectUpload(key)).isEqualTo(ObjectCleanupGuard.Decision.REFERENCED);
    assertThat(inspectSuperseded()).isEqualTo(ObjectCleanupGuard.Decision.REFERENCED);
  }

  @Test
  void anotherSubmissionReferencePreventsDeletingTheSupersededObject() {
    db.update("UPDATE contract_submissions SET storage_key=? WHERE id='new'", key);
    assertThat(inspectSuperseded()).isEqualTo(ObjectCleanupGuard.Decision.REFERENCED);
  }

  @Test
  void graceAndStateChangesAreRecheckedAtExecutionTime() {
    db.update("UPDATE contract_submissions SET superseded_at='2026-11-27T00:00:00Z' WHERE id='old'");
    assertThat(inspectSuperseded()).isEqualTo(ObjectCleanupGuard.Decision.REFERENCED);
    db.update("UPDATE contract_submissions SET superseded_at='2026-11-26T23:00:00Z',status='CONFIRMED' WHERE id='old'");
    assertThat(inspectSuperseded()).isEqualTo(ObjectCleanupGuard.Decision.REFERENCED);
  }

  @Test
  void ownerLockDefersBothGuardsInsteadOfDeletingAnInFlightOrder() throws Exception {
    var pool = Executors.newSingleThreadExecutor();
    try {
      tx.executeWithoutResult(t -> {
        db.queryForList("SELECT id FROM orders WHERE id='order' FOR UPDATE");
        try {
          assertThat(pool.submit(() -> inspectUpload(key)).get(10, TimeUnit.SECONDS)).isEqualTo(ObjectCleanupGuard.Decision.DEFER);
          assertThat(pool.submit(this::inspectSuperseded).get(10, TimeUnit.SECONDS)).isEqualTo(ObjectCleanupGuard.Decision.DEFER);
        } catch (Exception e) { throw new IllegalStateException(e); }
      });
    } finally { pool.shutdownNow(); }
  }

  @Test
  void domainRowLockDefersOutboxWhileCleanupIsReconciling() throws Exception {
    var pool = Executors.newSingleThreadExecutor();
    try {
      tx.executeWithoutResult(t -> {
        db.queryForList("SELECT id FROM contract_submissions WHERE id='old' FOR UPDATE");
        try { assertThat(pool.submit(this::inspectSuperseded).get(10, TimeUnit.SECONDS)).isEqualTo(ObjectCleanupGuard.Decision.DEFER); }
        catch (Exception e) { throw new IllegalStateException(e); }
      });
    } finally { pool.shutdownNow(); }
  }

  @Test
  void failedS3DeletionRemainsDeadAndCanBeRetriedWithoutLosingTheDomainDocument() {
    String task = tx.execute(t -> outbox.enqueue("bucket", key, superseded.type(), "old"));
    doThrow(new IllegalStateException("S3 offline")).when(storage).delete(any(), any());
    assertThat(worker.poll()).isEqualTo(1);
    assertThat(outbox.status(task)).isEqualTo("DEAD");
    assertThat(db.queryForObject("SELECT document_deleted_at IS NULL FROM contract_submissions WHERE id='old'", Boolean.class)).isTrue();
    doNothing().when(storage).delete(any(), any());
    assertThat(outbox.retry(task)).isTrue();
    assertThat(worker.poll()).isEqualTo(1);
    assertThat(outbox.status(task)).isEqualTo("COMPLETED");
  }

  @Test
  void invalidPrefixAndMissingSubmissionFailClosed() {
    assertThatThrownBy(() -> inspectUpload("drone-media/protected.pdf")).isInstanceOf(IllegalArgumentException.class);
    ObjectCleanupGuard.Decision missing = tx.execute(t -> superseded.inspect("bucket", key, "missing"));
    assertThat(missing).isEqualTo(ObjectCleanupGuard.Decision.REFERENCED);
  }
}
