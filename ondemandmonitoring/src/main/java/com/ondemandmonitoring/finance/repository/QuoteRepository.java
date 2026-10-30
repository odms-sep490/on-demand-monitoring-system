package com.ondemandmonitoring.finance.repository;

import com.ondemandmonitoring.finance.domain.Quote;
import com.ondemandmonitoring.finance.enums.QuoteStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;

public interface QuoteRepository extends JpaRepository<Quote, String> {
    @Query("select distinct q from Quote q left join fetch q.items where q.order.id=:orderId order by q.quoteVersion desc")
    List<Quote> findHistory(@Param("orderId") String orderId);

    @Query("select q from Quote q where q.order.id in :orderIds and q.status in :statuses order by q.quoteVersion desc")
    List<Quote> findGuidanceQuotesForOrders(@Param("orderIds") Collection<String> orderIds,
            @Param("statuses") Collection<QuoteStatus> statuses);

    Optional<Quote> findFirstByOrderIdAndStatusInOrderByQuoteVersionDesc(String orderId, Collection<QuoteStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from Quote q left join fetch q.items where q.id=:id")
    Optional<Quote> findByIdForUpdate(@Param("id") String id);

    int countByOrderId(String orderId);

    long countByStatus(QuoteStatus status);

    @EntityGraph(attributePaths = "order")
    List<Quote> findByStatusInOrderByUpdatedAtDesc(Collection<QuoteStatus> statuses, Pageable pageable);
}
