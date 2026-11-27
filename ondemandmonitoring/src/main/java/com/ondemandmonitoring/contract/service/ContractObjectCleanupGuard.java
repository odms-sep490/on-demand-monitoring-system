package com.ondemandmonitoring.contract.service;

import com.ondemandmonitoring.media.cleanup.ObjectCleanupGuard;
import java.time.Clock;
import java.time.Instant;
import java.sql.Timestamp;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Protects every official contract and every retained signed submission, including old quotes. */
@Component
@RequiredArgsConstructor
public class ContractObjectCleanupGuard implements ObjectCleanupGuard {
  public static final String TYPE = "CONTRACT_DOCUMENT";
  private final JdbcTemplate db;

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public Decision inspect(String bucket, String key, String orderId) {
    requireContractKey(key, orderId);
    if (orderBusy(db, orderId)) return Decision.DEFER;
    return referenced(db, bucket, key, null) ? Decision.REFERENCED : Decision.DELETE;
  }

  private static void requireContractKey(String key, String orderId) {
    if (orderId == null || key == null
        || !key.startsWith("order-contracts/orders/" + orderId + "/")
        || !key.endsWith(".pdf"))
      throw new IllegalArgumentException("Invalid contract object identity");
  }

  private static boolean orderBusy(JdbcTemplate db, String orderId) {
    var locked = db.queryForList(
        "SELECT id FROM orders WHERE id=? FOR UPDATE SKIP LOCKED", orderId);
    return locked.isEmpty() && Boolean.TRUE.equals(db.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM orders WHERE id=?)", Boolean.class, orderId));
  }

  private static boolean referenced(JdbcTemplate db, String bucket, String key, String excluded) {
    return Boolean.TRUE.equals(db.queryForObject("""
        SELECT EXISTS (SELECT 1 FROM order_contracts WHERE storage_bucket=? AND storage_key=?)
          OR EXISTS (SELECT 1 FROM contract_submissions WHERE storage_bucket=? AND storage_key=?
            AND id IS DISTINCT FROM CAST(? AS varchar))
        """, Boolean.class, bucket, key, bucket, key, excluded));
  }

  /** Only the explicitly superseded copy may be excluded from reference protection. */
  @Component
  @RequiredArgsConstructor
  public static class Superseded implements ObjectCleanupGuard {
    public static final String TYPE = "CONTRACT_SUPERSEDED_SUBMISSION";
    private final JdbcTemplate db;
    private final Clock clock;

    @Value("${app.contract.cleanup.grace-seconds:60}")
    private long graceSeconds = 60;

    @Override
    public String type() {
      return TYPE;
    }

    @Override
    public Decision inspect(String bucket, String key, String submissionId) {
      // Resolve the owner through the quote, so contracts prepared before invoicing are covered.
      var owners = db.queryForList("""
          SELECT q.order_id FROM contract_submissions s
          JOIN order_contracts c ON c.id=s.contract_id JOIN quotes q ON q.id=c.quote_id
          WHERE s.id=?
          """, String.class, submissionId);
      if (owners.isEmpty()) return Decision.REFERENCED;
      String orderId = owners.getFirst();
      requireContractKey(key, orderId);
      if (orderBusy(db, orderId)) return Decision.DEFER;
      var locked = db.queryForList(
          "SELECT id FROM contract_submissions WHERE id=? FOR UPDATE SKIP LOCKED", submissionId);
      if (locked.isEmpty()) return Decision.DEFER;
      boolean eligible = Boolean.TRUE.equals(db.queryForObject("""
          SELECT EXISTS (SELECT 1 FROM contract_submissions s WHERE s.id=?
            AND s.storage_bucket=? AND s.storage_key=? AND s.status='SUPERSEDED'
            AND s.document_deleted_at IS NULL AND s.superseded_at<=?
            AND s.cleanup_next_attempt_at IS NOT NULL
            AND EXISTS (SELECT 1 FROM contract_submissions newer
              WHERE newer.contract_id=s.contract_id AND newer.submission_number>s.submission_number))
          """, Boolean.class, submissionId, bucket, key,
          Timestamp.from(Instant.now(clock).minusSeconds(Math.max(0, graceSeconds)))));
      if (!eligible) return Decision.REFERENCED;
      return referenced(db, bucket, key, submissionId) ? Decision.REFERENCED : Decision.DELETE;
    }
  }
}
