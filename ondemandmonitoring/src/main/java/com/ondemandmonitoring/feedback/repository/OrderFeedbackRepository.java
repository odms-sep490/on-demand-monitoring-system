package com.ondemandmonitoring.feedback.repository;

import com.ondemandmonitoring.delivery.domain.OrderDelivery;
import com.ondemandmonitoring.feedback.domain.OrderFeedback;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderFeedbackRepository extends JpaRepository<OrderFeedback, String> {
    Optional<OrderFeedback> findByOrder_Id(String orderId);

    boolean existsByOrder_Id(String orderId);

    List<OrderFeedback> findByRatingLessThanEqualOrderByCreatedAtDesc(int rating, Pageable page);

    /** Accepted deliveries of the customer whose order has no feedback yet (may repeat per order). */
    @Query("select d from OrderDelivery d where d.order.customer.id = :customerId and d.customerAcceptedAt is not null "
            + "and not exists (select f.id from OrderFeedback f where f.order.id = d.order.id) "
            + "order by d.customerAcceptedAt desc")
    List<OrderDelivery> findAcceptedWithoutFeedback(@Param("customerId") String customerId, Pageable page);
}
