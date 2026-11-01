package com.ondemandmonitoring.review.service;

import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.enums.OrderStatus;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.review.domain.OrderReviewRecord;
import com.ondemandmonitoring.review.enums.CriterionResult;
import com.ondemandmonitoring.review.enums.ReviewCriterionCode;
import com.ondemandmonitoring.review.enums.ReviewCriterionType;
import com.ondemandmonitoring.review.repository.OrderReviewRecordRepository;
import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates the unanswered MANUAL review rows before a manager starts reviewing an order. */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderReviewRecordInitializer implements CommandLineRunner {

    private final OrderRepository orders;
    private final OrderReviewRecordRepository records;

    @Override
    @Transactional
    public void run(String... args) {
        long removed = records.deleteByCode(ReviewCriterionCode.PRICE_REASONABLE)
                + records.deleteByCode(ReviewCriterionCode.FLIGHT_SCALE)
                + records.deleteByCode(ReviewCriterionCode.SENSITIVE_AREA);
        if (removed > 0) {
            log.info("Removed {} obsolete order-review records", removed);
        }
        int created = orders.findByOrderStatusInOrderByCreatedAtAsc(
                        java.util.List.of(OrderStatus.PENDING, OrderStatus.UNDER_REVIEW)).stream()
                .mapToInt(this::initialize)
                .sum();
        if (created > 0) {
            log.info("Backfilled {} missing manual order-review records", created);
        }
    }

    @Transactional
    public int initialize(Order order) {
        int created = 0;
        for (ReviewCriterionCode code : Arrays.stream(ReviewCriterionCode.values())
                .filter(ReviewCriterionCode::isActive)
                .filter(value -> value.getType() == ReviewCriterionType.MANUAL)
                .toList()) {
            if (records.existsByOrderIdAndCode(order.getId(), code)) {
                continue;
            }
            OrderReviewRecord record = new OrderReviewRecord();
            record.setOrder(order);
            record.setCode(code);
            record.setType(ReviewCriterionType.MANUAL);
            record.setResult(CriterionResult.PENDING);
            records.save(record);
            created++;
        }
        return created;
    }

    /** Reopens only failed manager criteria after the customer supplies new information. */
    @Transactional
    public int reopenFailed(Order order, String customerInformation) {
        int reopened = 0;
        for (OrderReviewRecord record : records.findByOrderId(order.getId())) {
            if (record.getType() != ReviewCriterionType.MANUAL
                    || record.getResult() != CriterionResult.FAIL) {
                continue;
            }
            record.setResult(CriterionResult.PENDING);
            record.setNote("Khách bổ sung: " + customerInformation.strip());
            record.setDecidedBy(null);
            record.setDecidedAt(null);
            records.save(record);
            reopened++;
        }
        return reopened;
    }
}
