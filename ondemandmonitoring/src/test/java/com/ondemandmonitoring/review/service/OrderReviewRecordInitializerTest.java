package com.ondemandmonitoring.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.review.domain.OrderReviewRecord;
import com.ondemandmonitoring.review.enums.CriterionResult;
import com.ondemandmonitoring.review.enums.ReviewCriterionCode;
import com.ondemandmonitoring.review.enums.ReviewCriterionType;
import com.ondemandmonitoring.review.repository.OrderReviewRecordRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OrderReviewRecordInitializerTest {

    @Test
    void createsEveryMissingManualCriterionAsPending() {
        OrderRepository orders = mock(OrderRepository.class);
        OrderReviewRecordRepository records = mock(OrderReviewRecordRepository.class);
        Order order = new Order();
        order.setId("order-1");
        OrderReviewRecordInitializer initializer = new OrderReviewRecordInitializer(orders, records);

        int created = initializer.initialize(order);

        long manualCount = java.util.Arrays.stream(ReviewCriterionCode.values())
                .filter(ReviewCriterionCode::isActive)
                .filter(code -> code.getType() == ReviewCriterionType.MANUAL)
                .count();
        assertThat(created).isEqualTo(manualCount);
        ArgumentCaptor<OrderReviewRecord> saved = ArgumentCaptor.forClass(OrderReviewRecord.class);
        verify(records, org.mockito.Mockito.times((int) manualCount)).save(saved.capture());
        assertThat(saved.getAllValues()).allSatisfy(record -> {
            assertThat(record.getOrder()).isSameAs(order);
            assertThat(record.getType()).isEqualTo(ReviewCriterionType.MANUAL);
            assertThat(record.getResult()).isEqualTo(CriterionResult.PENDING);
        });
    }

    @Test
    void keepsAnExistingCriterionInsteadOfDuplicatingIt() {
        OrderRepository orders = mock(OrderRepository.class);
        OrderReviewRecordRepository records = mock(OrderReviewRecordRepository.class);
        Order order = new Order();
        order.setId("order-1");
        when(records.existsByOrderIdAndCode("order-1", ReviewCriterionCode.SITE_ACCESS))
                .thenReturn(true);
        OrderReviewRecordInitializer initializer = new OrderReviewRecordInitializer(orders, records);

        initializer.initialize(order);

        long missingManualCount = java.util.Arrays.stream(ReviewCriterionCode.values())
                .filter(ReviewCriterionCode::isActive)
                .filter(code -> code.getType() == ReviewCriterionType.MANUAL)
                .count() - 1;
        ArgumentCaptor<OrderReviewRecord> saved = ArgumentCaptor.forClass(OrderReviewRecord.class);
        verify(records, org.mockito.Mockito.times((int) missingManualCount)).save(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(OrderReviewRecord::getCode)
                .doesNotContain(ReviewCriterionCode.SITE_ACCESS);
    }
}
