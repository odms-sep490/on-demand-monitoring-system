package com.ondemandmonitoring.finance;

import com.ondemandmonitoring.finance.service.impl.SmartQuoteService;
import com.ondemandmonitoring.finance.service.IQuoteService;
import com.ondemandmonitoring.finance.repository.QuoteRepository;
import com.ondemandmonitoring.finance.domain.Quote;
import com.ondemandmonitoring.finance.dto.CustomerQuoteResponse;
import com.ondemandmonitoring.finance.enums.QuoteStatus;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SmartQuoteServiceTest {
    final IQuoteService quotes = mock(IQuoteService.class);
    final QuoteRepository repository = mock(QuoteRepository.class);
    final AuthenticatedUserResolver users = mock(AuthenticatedUserResolver.class);
    final SmartQuoteService service = new SmartQuoteService(quotes, repository, users, mock(com.ondemandmonitoring.finance.service.impl.QuoteScenarioCalculator.class));
    void setup() {
        var response = new CustomerQuoteResponse("q", "o", 1, QuoteStatus.APPROVED,
                new BigDecimal("2250000"), BigDecimal.ZERO, new BigDecimal("2250000"), true, null, null, List.of());
        when(quotes.getCurrentForCustomer("o")).thenReturn(response);
        var entity = new Quote(); entity.setQuoteVersion(1); entity.setPricingRevision(2);
        when(repository.findById("q")).thenReturn(Optional.of(entity));
    }
    @Test void unsupportedChangeHasNoInventedMoneyAndNoWrites() {
        setup(); var base = service.read("o", "q", true);
        var result = service.compare("o", "q", true, new SmartQuoteService.Compare(base.revision(),
                List.of(new SmartQuoteService.Option("A", null), new SmartQuoteService.Option("B", "Bỏ video"))));
        assertEquals(new BigDecimal("2250000"), result.options().get(0).total());
        assertEquals("NEEDS_MANUAL_QUOTE", result.options().get(1).status());
        assertNull(result.options().get(1).total()); assertNull(result.options().get(1).difference());
        verify(repository, never()).save(any());
    }
    @Test void staleRevisionRejected() {
        setup(); assertThrows(RuntimeException.class, () -> service.compare("o", "q", true,
                new SmartQuoteService.Compare("old", List.of(new SmartQuoteService.Option("A", null), new SmartQuoteService.Option("B", "change")))));
    }
    @Test void ownershipFailureIsPreserved() {
        when(quotes.getCurrentForCustomer("other")).thenThrow(new IllegalStateException("denied"));
        assertThrows(IllegalStateException.class, () -> service.read("other", "q", true));
        verifyNoInteractions(repository);
    }
    @Test void customerCannotReadAnotherQuoteRevision() {
        setup(); assertThrows(RuntimeException.class, () -> service.read("o", "other", true));
    }
    @Test void staffCannotBypassManagerWorkflow() {
        var actor = mock(com.ondemandmonitoring.user.domain.User.class);
        when(users.getCurrentUser()).thenReturn(actor);
        assertThrows(RuntimeException.class, () -> service.read("o", "q", false));
        verifyNoInteractions(quotes, repository);
    }
}

