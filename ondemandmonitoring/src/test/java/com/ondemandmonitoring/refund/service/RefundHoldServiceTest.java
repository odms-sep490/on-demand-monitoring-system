package com.ondemandmonitoring.refund.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.refund.repository.RefundRepository;
import com.ondemandmonitoring.refund.enums.RefundStatus;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RefundHoldServiceTest {
    @Mock private RefundRepository refunds;

    @Test
    void blocksMissionWorkflowForRefundsThatStillHoldTheOrder() {
        RefundHoldService service = new RefundHoldService(refunds);
        when(refunds.existsByOrderIdAndStatusIn("order-1",
                List.of(RefundStatus.REQUESTED, RefundStatus.PROCESSING, RefundStatus.COMPLETED))).thenReturn(true);

        assertTrue(service.isOrderOnRefundHold("order-1"));
        assertThrows(ApiException.class, () -> service.requireOrderNotOnRefundHold("order-1"));
    }
}
