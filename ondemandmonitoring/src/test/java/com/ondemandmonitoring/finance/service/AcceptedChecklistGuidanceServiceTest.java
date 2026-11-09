package com.ondemandmonitoring.finance.service;

import com.ondemandmonitoring.finance.domain.Quote;
import com.ondemandmonitoring.finance.dto.ChecklistGuidanceSnapshot;
import com.ondemandmonitoring.finance.enums.QuoteStatus;
import com.ondemandmonitoring.finance.repository.QuoteRepository;
import com.ondemandmonitoring.finance.service.impl.AcceptedChecklistGuidanceService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcceptedChecklistGuidanceServiceTest {
    private final QuoteRepository quotes = mock(QuoteRepository.class);
    private final AcceptedChecklistGuidanceService service = new AcceptedChecklistGuidanceService(quotes);

    @Test void readsOnlyTheLatestAcceptedQuoteInOneQuery() {
        var snapshot = new ChecklistGuidanceSnapshot(1, List.of());
        var quote = new Quote(); quote.setChecklistGuidanceSnapshot(snapshot);
        when(quotes.findFirstByOrderIdAndStatusInOrderByQuoteVersionDesc("o", List.of(QuoteStatus.ACCEPTED_BY_CUSTOMER)))
                .thenReturn(Optional.of(quote));
        assertSame(snapshot, service.findForOrder("o"));
        verify(quotes).findFirstByOrderIdAndStatusInOrderByQuoteVersionDesc("o", List.of(QuoteStatus.ACCEPTED_BY_CUSTOMER));
        verifyNoMoreInteractions(quotes);
    }

    @Test void noAcceptedQuoteReturnsNullWithoutReadingManagerDrafts() {
        when(quotes.findFirstByOrderIdAndStatusInOrderByQuoteVersionDesc("o", List.of(QuoteStatus.ACCEPTED_BY_CUSTOMER)))
                .thenReturn(Optional.empty());
        assertNull(service.findForOrder("o"));
        verify(quotes).findFirstByOrderIdAndStatusInOrderByQuoteVersionDesc("o", List.of(QuoteStatus.ACCEPTED_BY_CUSTOMER));
        verifyNoMoreInteractions(quotes);
    }

    @Test void acceptedLegacyQuoteReturnsNullSnapshot() {
        when(quotes.findFirstByOrderIdAndStatusInOrderByQuoteVersionDesc("o", List.of(QuoteStatus.ACCEPTED_BY_CUSTOMER)))
                .thenReturn(Optional.of(new Quote()));
        assertNull(service.findForOrder("o"));
    }
}
