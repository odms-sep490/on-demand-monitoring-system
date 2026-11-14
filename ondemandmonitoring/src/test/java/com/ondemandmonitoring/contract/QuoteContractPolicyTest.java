package com.ondemandmonitoring.contract;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.contract.domain.*;
import com.ondemandmonitoring.contract.enums.*;
import com.ondemandmonitoring.contract.repository.*;
import com.ondemandmonitoring.contract.service.*;
import com.ondemandmonitoring.finance.domain.*;
import java.util.Optional;
import org.junit.jupiter.api.*;

class QuoteContractPolicyTest {
  OrderContractRepository repository = mock(OrderContractRepository.class);
  ContractDocumentStorage storage = mock(ContractDocumentStorage.class);
  QuoteContractPolicy policy = new QuoteContractPolicy(repository, storage);
  Quote quote;
  OrderContract contract;

  @BeforeEach
  void setup() {
    quote = new Quote();
    quote.setId("q");
    quote.setQuoteVersion(3);
    contract = new OrderContract();
    contract.setQuote(quote);
    contract.setQuoteVersion(3);
    contract.setStatus(ContractStatus.AWAITING_SIGNATURE);
    contract.setDocument(OrderContractServiceTest.document());
    when(repository.findByQuoteId("q")).thenReturn(Optional.of(contract));
  }

  @Test
  void missingDocumentBlocksSendingOrAccepting() {
    when(repository.findByQuoteId("q")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> policy.requirePrepared(quote)).isInstanceOf(ApiException.class);
    verifyNoInteractions(storage);
  }

  @Test
  void staleDocumentCannotBeAttachedToANewQuoteVersion() {
    quote.setQuoteVersion(4);
    assertThatThrownBy(() -> policy.requirePrepared(quote)).isInstanceOf(ApiException.class);
    verifyNoInteractions(storage);
  }

  @Test
  void storageFailureCannotBeTreatedAsPrepared() {
    doThrow(new ApiException(ErrorCode.CONTRACT_STORAGE_UNAVAILABLE))
        .when(storage)
        .requireStored(contract.getDocument());
    assertThatThrownBy(() -> policy.requirePrepared(quote)).isInstanceOf(ApiException.class);
    verify(repository, never()).saveAndFlush(any());
  }

  @Test
  void acceptanceBindsTheSamePreparedDocumentWithoutReupload() {
    Invoice invoice = new Invoice();
    invoice.setId("i");
    invoice.setQuote(quote);
    policy.attachInvoice(invoice);
    assertThat(contract.getInvoice()).isSameAs(invoice);
    verify(repository).saveAndFlush(contract);
    verify(storage, never()).store(any(), any());
  }

  @Test
  void cannotRebindAnExistingContractToAnotherInvoice() {
    Invoice old = new Invoice();
    old.setId("old");
    contract.setInvoice(old);
    Invoice next = new Invoice();
    next.setId("next");
    next.setQuote(quote);
    assertThatThrownBy(() -> policy.attachInvoice(next)).isInstanceOf(ApiException.class);
    verify(repository, never()).saveAndFlush(any());
  }
}
