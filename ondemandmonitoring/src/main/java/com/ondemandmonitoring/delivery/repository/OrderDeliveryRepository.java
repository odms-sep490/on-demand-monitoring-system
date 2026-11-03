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
    Optional<OrderDelivery> findByOrderId(String orderId);
    List<OrderDelivery> findByOrderIdIn(Iterable<String> orderIds);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from OrderDelivery d where d.order.id=:orderId")
    Optional<OrderDelivery> findByOrderIdForUpdate(@Param("orderId") String orderId);
}
