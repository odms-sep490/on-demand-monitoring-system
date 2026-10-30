package com.ondemandmonitoring.finance.service.impl;

import com.ondemandmonitoring.finance.domain.Quote;
import com.ondemandmonitoring.finance.dto.ChecklistGuidanceSnapshot;
import com.ondemandmonitoring.finance.enums.QuoteStatus;
import com.ondemandmonitoring.finance.repository.QuoteRepository;
import com.ondemandmonitoring.finance.service.IAcceptedChecklistGuidanceService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AcceptedChecklistGuidanceService implements IAcceptedChecklistGuidanceService {
    private final QuoteRepository quotes;

    @Override
    @Transactional(readOnly = true)
    public ChecklistGuidanceSnapshot findForOrder(String orderId) {
        return quotes.findFirstByOrderIdAndStatusInOrderByQuoteVersionDesc(
                        orderId, List.of(QuoteStatus.ACCEPTED_BY_CUSTOMER))
                .map(Quote::getChecklistGuidanceSnapshot).orElse(null);
    }
}
