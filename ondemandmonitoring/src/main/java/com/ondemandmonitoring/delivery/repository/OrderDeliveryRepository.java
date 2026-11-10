package com.ondemandmonitoring.delivery.repository;

import com.ondemandmonitoring.delivery.domain.OrderDelivery;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence operations for the delivery state associated with an order. */
public interface OrderDeliveryRepository extends JpaRepository<OrderDelivery, String> {
    @Query("select d from OrderDelivery d where d.order.id=:orderId and d.missionId is null")
    Optional<OrderDelivery> findByOrderId(@Param("orderId") String orderId);
    List<OrderDelivery> findByOrderIdIn(Iterable<String> orderIds);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from OrderDelivery d where d.order.id=:orderId and d.missionId is null")
    Optional<OrderDelivery> findByOrderIdForUpdate(@Param("orderId") String orderId);

    @Query("select d from OrderDelivery d where d.order.id=:orderId and d.missionId=:missionId")
    Optional<OrderDelivery> findScoped(@Param("orderId") String orderId, @Param("missionId") String missionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from OrderDelivery d where d.order.id=:orderId and d.missionId=:missionId")
    Optional<OrderDelivery> findScopedForUpdate(@Param("orderId") String orderId, @Param("missionId") String missionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from OrderDelivery d where d.id=:id")
    Optional<OrderDelivery> findByIdForUpdate(@Param("id") String id);

    @Query("select distinct d from OrderDelivery d, ResultReviewRoundEvidence e where e.roundItem.round.id=d.currentReviewRoundId and e.mediaAsset.id=:mediaId")
    List<OrderDelivery> findCurrentForMedia(@Param("mediaId") String mediaId);
}
