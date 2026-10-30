package com.ondemandmonitoring.order.repository;

import com.ondemandmonitoring.order.domain.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.ondemandmonitoring.order.enums.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;

@Repository
public interface OrderRepository extends JpaRepository<Order, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") String id);

    boolean existsByOrderCode(String orderCode);

    List<Order> findByOrderStatusOrderByCreatedAtAsc(OrderStatus status);

    List<Order> findByCustomer_IdOrderByCreatedAtDesc(String customerId);

    List<Order> findByCustomer_IdAndOrderStatusOrderByCreatedAtDesc(String customerId, OrderStatus status);

    long countByOrderStatus(OrderStatus status);

    @EntityGraph(attributePaths = {"customer", "service"})
    Page<Order> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @EntityGraph(attributePaths = {"customer", "service"})
    Page<Order> findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(
            java.time.Instant from, java.time.Instant to, Pageable pageable);

    @EntityGraph(attributePaths = {"customer", "service"})
    Page<Order> findByOrderStatusOrderByCreatedAtAsc(OrderStatus status, Pageable pageable);

    @Query("""
            select count(o) from Order o
            where o.orderStatus not in :terminalStatuses
              and exists (select i.id from OrderChecklistItem i where i.order = o and i.checklistRevision = o.checklistRevision)
              and not exists (select i.id from OrderChecklistItem i where i.order = o and i.checklistRevision = o.checklistRevision and i.pricingStatus in :blockingStatuses)
              and not exists (select q.id from Quote q where q.order = o and q.checklistRevision = o.checklistRevision and q.status in :activeQuoteStatuses)
            """)
    long countQuoteReadyOrders(
            @Param("terminalStatuses") java.util.Collection<OrderStatus> terminalStatuses,
            @Param("blockingStatuses") java.util.Collection<com.ondemandmonitoring.order.enums.ChecklistPricingStatus> blockingStatuses,
            @Param("activeQuoteStatuses") java.util.Collection<com.ondemandmonitoring.finance.enums.QuoteStatus> activeQuoteStatuses);

    @EntityGraph(attributePaths = {"customer", "service"})
    @Query("""
            select o from Order o
            where o.orderStatus not in :terminalStatuses
              and exists (select i.id from OrderChecklistItem i where i.order = o and i.checklistRevision = o.checklistRevision)
              and not exists (select i.id from OrderChecklistItem i where i.order = o and i.checklistRevision = o.checklistRevision and i.pricingStatus in :blockingStatuses)
              and not exists (select q.id from Quote q where q.order = o and q.checklistRevision = o.checklistRevision and q.status in :activeQuoteStatuses)
            order by o.createdAt asc
            """)
    Page<Order> findQuoteReadyOrders(
            @Param("terminalStatuses") java.util.Collection<OrderStatus> terminalStatuses,
            @Param("blockingStatuses") java.util.Collection<com.ondemandmonitoring.order.enums.ChecklistPricingStatus> blockingStatuses,
            @Param("activeQuoteStatuses") java.util.Collection<com.ondemandmonitoring.finance.enums.QuoteStatus> activeQuoteStatuses,
            Pageable pageable);
}
