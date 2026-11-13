package com.ondemandmonitoring.finance.repository;

import com.ondemandmonitoring.finance.domain.MissionPaymentMilestone;
import com.ondemandmonitoring.finance.enums.MissionPaymentMilestoneStatus;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MissionPaymentMilestoneRepository extends JpaRepository<MissionPaymentMilestone, String> {
    interface MissionChecklistPriceTotal {
        String getMissionId();
        BigDecimal getGrossAmount();
    }

    @Query("select a.missionId as missionId, sum(qi.amount) as grossAmount "
            + "from QuoteItem qi, MissionChecklistAssignment a "
            + "where qi.quote.id = :quoteId and qi.orderChecklistItem is not null "
            + "and a.orderId = :orderId and a.orderChecklistItemId = qi.orderChecklistItem.id "
            + "and a.removedAt is null and a.missionId in :missionIds group by a.missionId")
    List<MissionChecklistPriceTotal> sumChecklistPricesByMission(@Param("quoteId") String quoteId,
            @Param("orderId") String orderId, @Param("missionIds") Collection<String> missionIds);

    @Query("select count(qi.id) from QuoteItem qi where qi.quote.id = :quoteId "
            + "and qi.orderChecklistItem is not null and qi.amount <> 0 "
            + "and not exists (select a.id from MissionChecklistAssignment a "
            + "where a.orderId = :orderId and a.orderChecklistItemId = qi.orderChecklistItem.id "
            + "and a.removedAt is null and a.missionId in :missionIds)")
    long countPricedChecklistItemsWithoutBillableMission(@Param("quoteId") String quoteId,
            @Param("orderId") String orderId, @Param("missionIds") Collection<String> missionIds);

    @Query("select m.id from MissionPaymentMilestone m where m.paymentExpiresAt is not null "
            + "and m.paymentExpiresAt <= :now and m.status in :statuses "
            + "and not exists (select p.id from Payment p where p.missionPaymentMilestone = m "
            + "and p.status in ('PENDING', 'PROCESSING')) order by m.paymentExpiresAt, m.id")
    List<String> findExpiredPaymentMilestoneIds(@Param("now") java.time.Instant now,
            @Param("statuses") Collection<MissionPaymentMilestoneStatus> statuses,
            org.springframework.data.domain.Pageable pageable);

    @Query("select m.invoice.id from MissionPaymentMilestone m where m.id = :id")
    Optional<String> findInvoiceIdById(@Param("id") String id);

    @EntityGraph(attributePaths = {"mission", "invoice", "pricedBy"})
    @Query("select m from MissionPaymentMilestone m where m.invoice.id = :invoiceId "
            + "order by m.mission.scheduledStartAt, m.mission.id")
    List<MissionPaymentMilestone> findAllByInvoiceIdForDisplay(@Param("invoiceId") String invoiceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from MissionPaymentMilestone m where m.id = :id")
    Optional<MissionPaymentMilestone> findByIdForUpdate(@Param("id") String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from MissionPaymentMilestone m where m.mission.id = :missionId")
    Optional<MissionPaymentMilestone> findByMissionIdForUpdate(@Param("missionId") String missionId);

    Optional<MissionPaymentMilestone> findByMissionId(String missionId);

    boolean existsByInvoiceIdAndStatusIn(String invoiceId, Collection<MissionPaymentMilestoneStatus> statuses);

    @Query("select coalesce(sum(m.amount), 0) from MissionPaymentMilestone m "
            + "where m.invoice.id = :invoiceId and m.status in :statuses")
    BigDecimal sumAmountByInvoiceIdAndStatusIn(@Param("invoiceId") String invoiceId,
            @Param("statuses") Collection<MissionPaymentMilestoneStatus> statuses);

    @Query("select count(m) from MissionPaymentMilestone m where m.invoice.id = :invoiceId")
    long countByInvoiceId(@Param("invoiceId") String invoiceId);

    @Query("select coalesce(sum(m.amount), 0) from MissionPaymentMilestone m where m.status in :statuses")
    BigDecimal sumAmountByStatusIn(@Param("statuses") Collection<MissionPaymentMilestoneStatus> statuses);

    @Query("select count(m) from MissionPaymentMilestone m where m.status in :statuses")
    long countByStatusIn(@Param("statuses") Collection<MissionPaymentMilestoneStatus> statuses);

}
