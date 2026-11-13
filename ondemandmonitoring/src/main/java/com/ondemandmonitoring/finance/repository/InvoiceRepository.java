package com.ondemandmonitoring.finance.repository;

import com.ondemandmonitoring.finance.domain.Invoice;
import com.ondemandmonitoring.finance.enums.InvoiceStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import java.util.List;

public interface InvoiceRepository extends JpaRepository<Invoice, String> {
    Optional<Invoice> findByOrderId(String orderId);
    Optional<Invoice> findByQuoteId(String quoteId);

    @Query("select distinct i.order.id from Invoice i where i.order.id > :afterOrderId "
            + "and i.depositStatus = 'DEPOSIT_PAID' and i.status not in ('CANCELLED', 'REFUNDED', 'OVERDUE') "
            + "and i.order.executionPlan.status = 'FINALIZED' "
            + "and exists (select m.id from Mission m where m.order = i.order and m.status = 'PLANNING' and m.purpose = 'ORIGINAL') "
            + "order by i.order.id")
    List<String> findOrderIdsAwaitingMissionActivationAfter(@Param("afterOrderId") String afterOrderId,
            Pageable pageable);

    @Query("select i.order.id from Invoice i where i.id=:id")
    Optional<String> findOrderIdById(@Param("id") String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invoice i where i.id=:id")
    Optional<Invoice> findByIdForUpdate(@Param("id") String id);

    @Query("""
            select i.id from Invoice i
            where i.depositExpiresAt is not null and i.depositExpiresAt <= :now
              and i.order.orderStatus = 'APPROVED'
              and (i.depositStatus = 'WAITING_DEPOSIT'
                   or (i.depositStatus is null and i.paidAmount < i.depositAmount))
              and i.paidAmount < i.depositAmount
              and i.status in ('ISSUED','PARTIALLY_PAID')
            order by i.depositExpiresAt, i.id
            """)
    List<String> findExpiredDepositInvoiceIds(@Param("now") Instant now,
            Pageable pageable);

    @Query("""
            select count(i) from Invoice i
            where i.status in :openStatuses
              and (i.depositStatus = 'WAITING_DEPOSIT'
                   or (i.depositStatus is null and i.paidAmount < i.depositAmount))
            """)
    long countWaitingDeposit(@Param("openStatuses") Collection<InvoiceStatus> openStatuses);

    @Query("""
            select count(distinct i.id) from Invoice i
            where exists (select p.id from Payment p where p.invoice = i and p.paymentType = 'DEPOSIT' and p.status = 'FAILED')
              and not exists (select p.id from Payment p where p.invoice = i and p.paymentType = 'DEPOSIT' and p.status = 'SUCCESS')
            """)
    long countDepositPaymentIssues();

    @EntityGraph(attributePaths = "order")
    @Query("""
            select distinct i from Invoice i
            where exists (select p.id from Payment p where p.invoice = i and p.paymentType = 'DEPOSIT' and p.status = 'FAILED')
              and not exists (select p.id from Payment p where p.invoice = i and p.paymentType = 'DEPOSIT' and p.status = 'SUCCESS')
            order by i.updatedAt desc
            """)
    List<Invoice> findDepositPaymentIssues(Pageable pageable);
}
