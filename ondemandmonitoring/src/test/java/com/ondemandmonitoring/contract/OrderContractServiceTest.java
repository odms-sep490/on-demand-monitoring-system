package com.ondemandmonitoring.contract;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.contract.domain.*;
import com.ondemandmonitoring.contract.dto.*;
import com.ondemandmonitoring.contract.enums.*;
import com.ondemandmonitoring.contract.mapper.OrderContractMapper;
import com.ondemandmonitoring.contract.repository.*;
import com.ondemandmonitoring.contract.service.*;
import com.ondemandmonitoring.contract.service.impl.OrderContractServiceImpl;
import com.ondemandmonitoring.finance.domain.*;
import com.ondemandmonitoring.finance.dto.CommercialExpirationSettingsResponse;
import com.ondemandmonitoring.finance.enums.*;
import com.ondemandmonitoring.finance.repository.InvoiceRepository;
import com.ondemandmonitoring.finance.service.impl.CommercialExpirationSettingsService;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.enums.OrderStatus;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mapstruct.factory.Mappers;

class OrderContractServiceTest {
  OrderRepository orders = mock(OrderRepository.class);
  InvoiceRepository invoices = mock(InvoiceRepository.class);
  OrderContractRepository contracts = mock(OrderContractRepository.class);
  ContractSubmissionRepository submissions = mock(ContractSubmissionRepository.class);
  AuthenticatedUserResolver users = mock(AuthenticatedUserResolver.class);
  ContractDocumentStorage storage = mock(ContractDocumentStorage.class);
  CommercialExpirationSettingsService expiration = mock(CommercialExpirationSettingsService.class);
  Instant now = Instant.parse("2026-11-14T03:00:00Z");
  OrderContractServiceImpl service =
      new OrderContractServiceImpl(
          orders,
          invoices,
          contracts,
          submissions,
          users,
          storage,
          Mappers.getMapper(OrderContractMapper.class),
          expiration,
          Clock.fixed(now, ZoneOffset.UTC));
  Order order;
  Invoice invoice;
  OrderContract contract;
  User owner, manager;
  List<ContractSubmission> history;

  static User user(String id, RoleCode code) {
    User u = new User();
    u.setId(id);
    Role role = new Role();
    role.setCode(code);
    u.setRole(role);
    return u;
  }

  static ContractDocument document() {
    ContractDocument d = new ContractDocument();
    d.setBucket("private-test");
    d.setKey("contract-key");
    d.setFileName("contract.pdf");
    d.setSha256("a".repeat(64));
    d.setByteSize(123);
    return d;
  }

  void actor(User user) {
    when(users.getCurrentUser()).thenReturn(user);
    when(users.getCurrentUserId()).thenReturn(user.getId());
  }

  @BeforeEach
  void setup() {
    owner = user("customer", RoleCode.CUSTOMER);
    manager = user("manager", RoleCode.MANAGER);
    actor(owner);
    order = new Order();
    order.setId("order");
    order.setCustomer(owner);
    order.setOrderStatus(OrderStatus.APPROVED);
    Quote quote = new Quote();
    quote.setId("quote");
    quote.setQuoteVersion(2);
    quote.setStatus(QuoteStatus.ACCEPTED_BY_CUSTOMER);
    quote.setOrder(order);
    invoice = new Invoice();
    invoice.setId("invoice");
    invoice.setOrder(order);
    invoice.setQuote(quote);
    invoice.setContractRequired(true);
    invoice.setPaidAmount(BigDecimal.ZERO);
    invoice.setStatus(InvoiceStatus.ISSUED);
    contract = new OrderContract();
    contract.setId("contract");
    contract.setVersion(1L);
    contract.setInvoice(invoice);
    contract.setQuoteVersion(2);
    contract.setContractNumber("CTR-01");
    contract.setStatus(ContractStatus.AWAITING_SIGNATURE);
    contract.setDocument(document());
    history = new ArrayList<>();
    when(orders.findById("order")).thenReturn(Optional.of(order));
    when(orders.findByIdForUpdate("order")).thenReturn(Optional.of(order));
    when(invoices.findByOrderId("order")).thenReturn(Optional.of(invoice));
    when(invoices.findByIdForUpdate("invoice")).thenReturn(Optional.of(invoice));
    when(contracts.findByInvoiceId("invoice")).thenReturn(Optional.of(contract));
    when(submissions.findAllByContractIdOrderBySubmissionNumberDesc("contract"))
        .thenAnswer(
            call ->
                history.stream()
                    .sorted(
                        Comparator.comparingInt(ContractSubmission::getSubmissionNumber).reversed())
                    .toList());
    when(submissions.saveAndFlush(any()))
        .thenAnswer(
            call -> {
              ContractSubmission s = call.getArgument(0);
              if (s.getId() == null) {
                s.setId("s" + (history.size() + 1));
                history.add(s);
              }
              return s;
            });
    when(contracts.saveAndFlush(any()))
        .thenAnswer(
            call -> {
              OrderContract c = call.getArgument(0);
              if (c.getId() == null) c.setId("contract");
              c.setVersion(c.getVersion() == null ? 0 : c.getVersion() + 1);
              return c;
            });
    when(storage.store(eq("order"), any())).thenReturn(document());
    when(expiration.current())
        .thenReturn(new CommercialExpirationSettingsResponse(48, 12, "admin", now));
  }

  ContractReviewRequest review(String id) {
    return new ContractReviewRequest(contract.getVersion(), id, null);
  }

  void submit() {
    service.submit("order", contract.getVersion(), ContractTestFiles.pdf());
  }

  @Test
  void issuePinsAcceptedQuoteAndDoesNotStartDepositDeadline() {
    actor(manager);
    when(contracts.findByInvoiceId("invoice")).thenReturn(Optional.empty());
    ContractResponse result = service.issue("order", "quote", "CTR-2026", ContractTestFiles.pdf());
    assertThat(result.quoteId()).isEqualTo("quote");
    assertThat(result.quoteVersion()).isEqualTo(2);
    assertThat(result.status()).isEqualTo(ContractStatus.AWAITING_SIGNATURE);
    assertThat(invoice.getDepositExpiresAt()).isNull();
    var lockOrder = inOrder(orders, invoices, contracts, storage);
    lockOrder.verify(orders).findByIdForUpdate("order");
    lockOrder.verify(invoices).findByOrderId("order");
    lockOrder.verify(invoices).findByIdForUpdate("invoice");
  }

  @Test
  void customerCannotIssueOrConfirm() {
    assertThatThrownBy(() -> service.issue("order", "quote", "CTR-01", ContractTestFiles.pdf()))
        .isInstanceOf(ApiException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.ACCESS_DENIED);
    assertThatThrownBy(() -> service.confirm("order", review("s1")))
        .isInstanceOf(ApiException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.ACCESS_DENIED);
    verify(storage, never()).store(any(), any());
  }

  @Test
  void anotherCustomerCannotReadSubmitOrDownload() {
    actor(user("other", RoleCode.CUSTOMER));
    assertThatThrownBy(() -> service.get("order"))
        .isInstanceOf(ApiException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.ACCESS_DENIED);
    assertThatThrownBy(() -> service.submit("order", 1, ContractTestFiles.pdf()))
        .isInstanceOf(ApiException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.ACCESS_DENIED);
    assertThatThrownBy(() -> service.download("order", null))
        .isInstanceOf(ApiException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.ACCESS_DENIED);
  }

  @Test
  void newSubmissionIsPendingAndCannotOpenDeposit() {
    ContractResponse result = service.submit("order", 1, ContractTestFiles.pdf());
    assertThat(result.status()).isEqualTo(ContractStatus.PENDING_VERIFICATION);
    assertThat(result.submissions()).hasSize(1);
    assertThat(result.submissions().getFirst().submittedBy()).isEqualTo("customer");
    assertThat(invoice.isContractReady()).isFalse();
    assertThat(invoice.getContractConfirmedAt()).isNull();
    assertThat(invoice.getDepositExpiresAt()).isNull();
  }

  @Test
  void aPendingSubmissionCannotBeOverwritten() {
    submit();
    assertThatThrownBy(
            () -> service.submit("order", contract.getVersion(), ContractTestFiles.pdf()))
        .isInstanceOf(ApiException.class);
    assertThat(history).hasSize(1);
    verify(storage, times(1)).store(any(), any());
  }

  @Test
  void staleVersionAndWrongQuoteDoNotUploadAnything() {
    assertThatThrownBy(() -> service.submit("order", 0, ContractTestFiles.pdf()))
        .isInstanceOf(ApiException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.CONTRACT_VERSION_CONFLICT);
    actor(manager);
    when(contracts.findByInvoiceId("invoice")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.issue("order", "old-quote", "CTR-01", ContractTestFiles.pdf()))
        .isInstanceOf(ApiException.class);
    verify(storage, never()).store(any(), any());
  }

  @Test
  void changesAndResubmissionPreserveTheOldFileAndReview() {
    submit();
    actor(manager);
    service.requestChanges(
        "order", new ContractReviewRequest(contract.getVersion(), "s1", "Missing signature"));
    actor(owner);
    submit();
    assertThat(history).hasSize(2);
    assertThat(history.getFirst().getStatus())
        .isEqualTo(ContractSubmissionStatus.CHANGES_REQUESTED);
    assertThat(history.getFirst().getReviewNote()).isEqualTo("Missing signature");
    assertThat(history.get(1).getSubmissionNumber()).isEqualTo(2);
    actor(manager);
    assertThatThrownBy(() -> service.confirm("order", review("s1")))
        .isInstanceOf(ApiException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.CONTRACT_STATE_CONFLICT);
  }

  @Test
  void managerConfirmationOpensDepositAndStartsItsFullDeadlineExactlyOnce() {
    submit();
    actor(manager);
    var request = review("s1");
    service.confirm("order", request);
    assertThat(invoice.isContractReady()).isTrue();
    assertThat(invoice.getDepositExpiresAt()).isEqualTo(now.plus(Duration.ofHours(12)));
    assertThat(contract.getConfirmedBy()).isEqualTo("manager");
    assertThat(history.getFirst().getStatus()).isEqualTo(ContractSubmissionStatus.CONFIRMED);
    verify(storage, times(2)).requireStored(any());
    invoice.setPaidAmount(BigDecimal.TEN);
    invoice.setStatus(InvoiceStatus.PARTIALLY_PAID);
    order.setOrderStatus(OrderStatus.IN_PROGRESS);
    service.confirm("order", request);
    assertThat(invoice.getDepositExpiresAt()).isEqualTo(now.plus(Duration.ofHours(12)));
    verify(expiration, times(1)).current();
  }

  @Test
  void cannotConfirmWithoutTheLatestSubmissionOrStoredEvidence() {
    actor(manager);
    assertThatThrownBy(() -> service.confirm("order", review("missing")))
        .isInstanceOf(ApiException.class);
    actor(owner);
    submit();
    actor(manager);
    doThrow(new ApiException(ErrorCode.CONTRACT_STORAGE_UNAVAILABLE))
        .when(storage)
        .requireStored(any());
    assertThatThrownBy(() -> service.confirm("order", review("s1")))
        .isInstanceOf(ApiException.class);
    assertThat(invoice.getContractConfirmedAt()).isNull();
    assertThat(contract.getStatus()).isEqualTo(ContractStatus.PENDING_VERIFICATION);
  }

  @Test
  void aChangesRequestNeedsANonBlankReason() {
    submit();
    actor(manager);
    assertThatThrownBy(
            () ->
                service.requestChanges(
                    "order", new ContractReviewRequest(contract.getVersion(), "s1", "  ")))
        .isInstanceOf(ApiException.class);
    assertThat(history.getFirst().getStatus()).isEqualTo(ContractSubmissionStatus.PENDING);
  }

  @Test
  void readDoesNotMutateOrExposeStoragePaths() {
    var result = service.get("order");
    assertThat(result.document().sha256()).hasSize(64);
    verify(contracts, never()).saveAndFlush(any());
    verify(invoices, never()).save(any());
    verifyNoInteractions(storage);
  }

  @Test
  void historyDownloadMustBelongToThisContract() {
    assertThatThrownBy(() -> service.download("order", "other-contract-submission"))
        .isInstanceOf(ApiException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.CONTRACT_NOT_FOUND);
    verify(storage, never()).download(any());
  }

  @Test
  void legacyInvoicesRemainExplicitlyOutsideThisNewGate() {
    invoice.setContractRequired(false);
    when(contracts.findByInvoiceId("invoice")).thenReturn(Optional.empty());
    assertThat(service.get("order").status()).isEqualTo(ContractStatus.NOT_REQUIRED);
    assertThat(invoice.isContractReady()).isTrue();
  }
}
