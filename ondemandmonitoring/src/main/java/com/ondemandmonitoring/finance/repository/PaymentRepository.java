package com.ondemandmonitoring.finance.repository;

import com.ondemandmonitoring.finance.domain.Payment;
import com.ondemandmonitoring.finance.enums.PaymentStatus;
import com.ondemandmonitoring.finance.enums.PaymentType;
import com.ondemandmonitoring.finance.enums.PaymentProvider;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence adapter; locks serialize IPN/QueryDR processing for idempotency. */
public interface PaymentRepository extends JpaRepository<Payment, String> {
    List<Payment> findAllByInvoiceIdOrderByCreatedAtDesc(String invoiceId);
    Optional<Payment> findFirstByInvoiceIdAndPaymentTypeAndProviderAndStatusInOrderByCreatedAtDesc(
            String invoiceId, PaymentType type, PaymentProvider provider, Collection<PaymentStatus> statuses);
    Optional<Payment> findFirstByMissionPaymentMilestoneIdAndProviderAndStatusInOrderByCreatedAtDesc(
            String milestoneId, PaymentProvider provider, Collection<PaymentStatus> statuses);
    List<Payment> findAllByMissionPaymentMilestoneIdOrderByCreatedAtDesc(String milestoneId);
    boolean existsByMissionPaymentMilestoneId(String milestoneId);
    boolean existsByMissionPaymentMilestoneIdAndStatus(String milestoneId, PaymentStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.transactionReference=:reference")
    Optional<Payment> findByTransactionReferenceForUpdate(@Param("reference") String reference);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.invoice.id=:invoiceId order by p.id")
    List<Payment> findAllByInvoiceIdForUpdate(@Param("invoiceId") String invoiceId);

    Optional<Payment> findByTransactionReference(String reference);
    @Query("select p.invoice.order.id from Payment p where p.transactionReference=:reference")
    Optional<String> findOrderIdByTransactionReference(@Param("reference") String reference);
    boolean existsByTransactionReference(String reference);
    boolean existsByPaymentCode(Long paymentCode);
    boolean existsByInvoiceIdAndPaymentTypeAndStatus(
            String invoiceId, PaymentType paymentType, PaymentStatus status);

    @Query("select coalesce(sum(p.amount), 0) from Payment p where p.invoice.id=:invoiceId and p.status='SUCCESS' and p.paymentType<>'REFUND'")
    BigDecimal sumSuccessfulPayments(@Param("invoiceId") String invoiceId);

    @Query("select coalesce(sum(p.amount), 0) from Payment p "
            + "where p.status = com.ondemandmonitoring.finance.enums.PaymentStatus.SUCCESS "
            + "and p.paymentType in (com.ondemandmonitoring.finance.enums.PaymentType.DEPOSIT, "
            + "com.ondemandmonitoring.finance.enums.PaymentType.FINAL_PAYMENT, "
            + "com.ondemandmonitoring.finance.enums.PaymentType.MISSION_PAYMENT) and p.currency = 'VND'")
    BigDecimal sumSuccessfulServiceTransactions();

    @Query("select coalesce(sum(p.amount), 0) from Payment p where p.invoice.id = :invoiceId "
            + "and p.status in (com.ondemandmonitoring.finance.enums.PaymentStatus.PENDING, "
            + "com.ondemandmonitoring.finance.enums.PaymentStatus.PROCESSING)")
    BigDecimal sumActivePaymentAmountsByInvoiceId(@Param("invoiceId") String invoiceId);

    long countByPaymentTypeAndStatus(PaymentType paymentType, PaymentStatus status);

    @EntityGraph(attributePaths = {"invoice", "invoice.order"})
    List<Payment> findByStatusOrderByPaidAtDesc(PaymentStatus status, Pageable pageable);

}
