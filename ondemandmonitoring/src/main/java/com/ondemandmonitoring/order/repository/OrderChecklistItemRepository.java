package com.ondemandmonitoring.order.repository;

import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import com.ondemandmonitoring.order.enums.ChecklistPricingStatus;
import com.ondemandmonitoring.order.enums.OrderChecklistSourceType;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderChecklistItemRepository extends JpaRepository<OrderChecklistItem, String> {
    @Query("""
            select i from OrderChecklistItem i
            where i.order.id in :orderIds and i.checklistRevision = i.order.checklistRevision
            order by i.displayOrder asc, i.id asc
            """)
    List<OrderChecklistItem> findAllByOrderIdInOrderByDisplayOrderAscIdAsc(@Param("orderIds") Collection<String> orderIds);

    @Query("""
            select i from OrderChecklistItem i
            where i.order.id = :orderId and i.checklistRevision = i.order.checklistRevision
            order by i.displayOrder asc, i.id asc
            """)
    List<OrderChecklistItem> findAllByOrderIdOrderByDisplayOrderAscIdAsc(@Param("orderId") String orderId);

    List<OrderChecklistItem> findAllByOrderIdAndChecklistRevisionOrderByDisplayOrderAscIdAsc(
            String orderId, long checklistRevision);

    /** How many current order lines were taken from each library checklist (grouped, one query per page). */
    @Query("select i.sourceChecklist.id as checklistId, count(i) as total "
            + "from OrderChecklistItem i where i.sourceChecklist.id in :ids and i.checklistRevision = i.order.checklistRevision "
            + "group by i.sourceChecklist.id")
    List<UsageCount> countBySourceChecklistIds(@Param("ids") Collection<String> ids);

    interface UsageCount {
        String getChecklistId();
        long getTotal();
    }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<OrderChecklistItem> findByIdAndOrderId(String id, String orderId);

    @Query("""
            select count(i) from OrderChecklistItem i
            where i.pricingStatus = :pricingStatus
              and i.sourceType in :sourceTypes
              and i.checklistRevision = i.order.checklistRevision
            """)
    long countByPricingStatusAndSourceTypeIn(
            @Param("pricingStatus") ChecklistPricingStatus pricingStatus,
            @Param("sourceTypes") Collection<OrderChecklistSourceType> sourceTypes);

    @Query("""
            select count(i) from OrderChecklistItem i
            where i.pricingStatus = :pricingStatus
              and i.checklistRevision = i.order.checklistRevision
            """)
    long countByPricingStatus(@Param("pricingStatus") ChecklistPricingStatus pricingStatus);

    @EntityGraph(attributePaths = {"order", "order.customer", "order.service"})
    @Query("""
            select i from OrderChecklistItem i
            where i.pricingStatus = :pricingStatus
              and i.sourceType in :sourceTypes
              and i.checklistRevision = i.order.checklistRevision
            order by i.createdAt asc
            """)
    List<OrderChecklistItem> findByPricingStatusAndSourceTypeInOrderByCreatedAtAsc(
            @Param("pricingStatus") ChecklistPricingStatus pricingStatus,
            @Param("sourceTypes") Collection<OrderChecklistSourceType> sourceTypes,
            Pageable pageable);

    @EntityGraph(attributePaths = {"order", "order.customer", "order.service"})
    @Query("""
            select i from OrderChecklistItem i
            where i.pricingStatus = :pricingStatus
              and i.checklistRevision = i.order.checklistRevision
            order by i.createdAt asc
            """)
    List<OrderChecklistItem> findByPricingStatusOrderByCreatedAtAsc(
            @Param("pricingStatus") ChecklistPricingStatus pricingStatus,
            Pageable pageable);

    @Query("""
            select i.order.id as orderId,
                   count(i) as additionalCount,
                   sum(case when i.pricingStatus = com.ondemandmonitoring.order.enums.ChecklistPricingStatus.NEEDS_MANAGER_REVIEW then 1 else 0 end) as pendingCount
            from OrderChecklistItem i
            where i.order.id in :orderIds
              and i.sourceType in :sourceTypes
              and i.checklistRevision = i.order.checklistRevision
            group by i.order.id
            """)
    List<OrderAdditionalChecklistCount> countAdditionalByOrderIds(
            @Param("orderIds") Collection<String> orderIds,
            @Param("sourceTypes") Collection<OrderChecklistSourceType> sourceTypes);

    interface OrderAdditionalChecklistCount {
        String getOrderId();
        long getAdditionalCount();
        long getPendingCount();
    }
}
