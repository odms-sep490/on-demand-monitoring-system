package com.ondemandmonitoring.refund.service;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.refund.enums.RefundStatus;
import com.ondemandmonitoring.refund.repository.RefundRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RefundHoldService implements IRefundHoldService {
    private final RefundRepository refunds;

    @Override
    @Transactional(readOnly = true)
    public boolean isOrderOnRefundHold(String orderId) {
        return refunds.existsByOrderIdAndStatusIn(orderId,
                List.of(RefundStatus.REQUESTED, RefundStatus.PROCESSING, RefundStatus.COMPLETED));
    }

    @Override
    @Transactional(readOnly = true)
    public void requireOrderNotOnRefundHold(String orderId) {
        if (isOrderOnRefundHold(orderId)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Mission planning and execution are blocked while a Deposit refund is active or completed");
        }
    }
}
