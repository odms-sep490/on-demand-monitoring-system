package com.ondemandmonitoring.contract.service.impl;

import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.contract.domain.*;
import com.ondemandmonitoring.contract.dto.*;
import com.ondemandmonitoring.contract.enums.*;
import com.ondemandmonitoring.contract.mapper.OrderContractMapper;
import com.ondemandmonitoring.contract.repository.*;
import com.ondemandmonitoring.contract.service.*;
import com.ondemandmonitoring.contract.validation.ContractPdfValidator;
import com.ondemandmonitoring.finance.domain.Invoice;
import com.ondemandmonitoring.finance.domain.Quote;
import com.ondemandmonitoring.finance.repository.QuoteRepository;
import com.ondemandmonitoring.finance.enums.*;
import com.ondemandmonitoring.finance.repository.InvoiceRepository;
import com.ondemandmonitoring.finance.service.impl.CommercialExpirationSettingsService;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.enums.OrderStatus;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class OrderContractServiceImpl implements IOrderContractService {
  private final OrderRepository orders;
  private final InvoiceRepository invoices;
  private final OrderContractRepository contracts;
  private final ContractSubmissionRepository submissions;
  private final AuthenticatedUserResolver users;
  private final ContractDocumentStorage storage;
  private final OrderContractMapper mapper;
  private final CommercialExpirationSettingsService expirationSettings;
  private final Clock clock;
  private final QuoteRepository quotes;

  @Override
  @Transactional(readOnly = true)
  public ContractResponse get(String orderId) {
    Order order =
        orders.findById(orderId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
    requireReader(order);
    var invoice = invoices.findByOrderId(orderId).orElse(null);
    Quote quote = invoice != null ? invoice.getQuote() : currentQuote(orderId);
    requireQuoteReader(quote);
    return response(quote, invoice, contracts.findByQuoteId(quote.getId()).orElse(null));
  }

  @Override
  @Transactional(readOnly = true)
  public ContractResponse getForQuote(String orderId, String quoteId) {
    Quote quote = quotes.findById(quoteId).orElseThrow(() -> new ApiException(ErrorCode.QUOTE_NOT_FOUND));
    if (!quote.getOrder().getId().equals(orderId)) throw denied();
    requireReader(quote.getOrder());
    requireQuoteReader(quote);
    var invoice = invoices.findByQuoteId(quoteId).orElse(null);
    return response(quote, invoice, contracts.findByQuoteId(quoteId).orElse(null));
  }

  @Override
  @Transactional
  public ContractResponse issue(String orderId, String quoteId, String number, MultipartFile file) {
    return issueDocument(orderId, quoteId, null, number, file);
  }

  @Override
  @Transactional
  public ContractResponse prepare(String orderId, String quoteId, long expectedPricingRevision, String number, MultipartFile file) {
    return issueDocument(orderId, quoteId, expectedPricingRevision, number, file);
  }

  private ContractResponse issueDocument(String orderId, String quoteId, Long expectedPricingRevision, String number, MultipartFile file) {
    requireManager();
    Order order = orders.findByIdForUpdate(orderId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
    Quote quote = quotes.findByIdForUpdate(quoteId).orElseThrow(() -> new ApiException(ErrorCode.QUOTE_NOT_FOUND));
    if (!quote.getOrder().getId().equals(orderId)
        || !Set.of(QuoteStatus.DRAFT, QuoteStatus.PENDING_REVIEW, QuoteStatus.APPROVED, QuoteStatus.ACCEPTED_BY_CUSTOMER).contains(quote.getStatus())
        || !Set.of(OrderStatus.PENDING, OrderStatus.UNDER_REVIEW, OrderStatus.APPROVED).contains(order.getOrderStatus())
        || quote.getChecklistRevision() != (order.getChecklistRevision() == 0 ? 1 : order.getChecklistRevision())
        || !currentQuote(orderId).getId().equals(quoteId)
        || contracts.findByQuoteId(quoteId).isPresent()) throw conflict();
    if (quote.getStatus() != QuoteStatus.ACCEPTED_BY_CUSTOMER
        && (expectedPricingRevision == null || expectedPricingRevision != quote.getPricingRevision()))
      throw new ApiException(ErrorCode.CONTRACT_VERSION_CONFLICT);
    Invoice invoice = invoices.findByQuoteId(quoteId).orElse(null);
    if (invoice != null) {
      invoice = invoices.findByIdForUpdate(invoice.getId()).orElseThrow(() -> new ApiException(ErrorCode.INVOICE_NOT_FOUND));
      requireEditableInvoice(invoice);
      if (invoice.getPaidAmount().signum() != 0) throw conflict();
      if (!invoice.isContractRequired()) throw conflict();
    }
    // Internal reference only. The legal contract number, if any, stays inside the uploaded PDF.
    String normalized = number == null || number.isBlank()
        ? "CTR-" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT)
        : number.trim();
    if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9/_-]{2,79}"))
      throw new ApiException(ErrorCode.INVALID_REQUEST, "Invalid contract number");
    if (contracts.existsByContractNumber(normalized)) throw conflict();
    var pdf = ContractPdfValidator.validate(file);
    OrderContract contract = new OrderContract();
    contract.setInvoice(invoice);
    contract.setQuote(quote);
    contract.setQuoteVersion(quote.getQuoteVersion());
    contract.setContractNumber(normalized);
    contract.setStatus(ContractStatus.AWAITING_SIGNATURE);
    contract.setIssuedBy(users.getCurrentUserId());
    contract.setIssuedAt(Instant.now(clock));
    contract.setDocument(storage.store(orderId, pdf));
    contracts.saveAndFlush(contract);
    return response(quote, invoice, contract);
  }

  @Override
  @Transactional
  public ContractResponse submit(String orderId, long expectedVersion, MultipartFile file) {
    Invoice invoice = lockInvoice(orderId);
    if (!invoice.getOrder().getCustomer().getId().equals(users.getCurrentUserId())) throw denied();
    OrderContract contract = requiredContract(invoice);
    requireEditableInvoice(invoice);
    requireVersion(contract, expectedVersion);
    if (contract.getStatus() != ContractStatus.AWAITING_SIGNATURE
        && contract.getStatus() != ContractStatus.CHANGES_REQUESTED
        && contract.getStatus() != ContractStatus.PENDING_VERIFICATION) throw conflict();
    var pdf = ContractPdfValidator.validate(file);
    List<ContractSubmission> history = history(contract);
    ContractSubmission previous = null;
    if (contract.getStatus() == ContractStatus.PENDING_VERIFICATION) {
      if (history.isEmpty() || history.getFirst().getStatus() != ContractSubmissionStatus.PENDING) throw conflict();
      previous = history.getFirst();
    }
    ContractSubmission submission = new ContractSubmission();
    submission.setContract(contract);
    submission.setSubmissionNumber(
        history.isEmpty() ? 1 : Math.incrementExact(history.getFirst().getSubmissionNumber()));
    submission.setStatus(ContractSubmissionStatus.PENDING);
    submission.setSubmittedBy(users.getCurrentUserId());
    submission.setSubmittedAt(Instant.now(clock));
    submission.setDocument(storage.store(orderId, pdf));
    submissions.saveAndFlush(submission);
    if (previous != null) {
      previous.setStatus(ContractSubmissionStatus.SUPERSEDED);
      previous.setSupersededAt(Instant.now(clock));
      previous.setSupersededBy(users.getCurrentUserId());
      previous.setCleanupNextAttemptAt(Instant.now(clock));
      submissions.saveAndFlush(previous);
    }
    contract.setStatus(ContractStatus.PENDING_VERIFICATION);
    contract.setSubmissionRevision(Math.incrementExact(contract.getSubmissionRevision()));
    if (invoice.getContractSubmittedAt() == null) {
      invoice.setContractSubmittedAt(Instant.now(clock));
      if (invoice.getDepositExpiresAt() == null)
        invoice.setDepositExpiresAt(Instant.now(clock).plus(expirationSettings.current().depositPaymentTimeout()));
    }
    contracts.saveAndFlush(contract);
    return response(invoice, contract);
  }

  @Override
  @Transactional
  public ContractResponse confirm(String orderId, ContractReviewRequest request) {
    requireManager();
    Invoice invoice = lockInvoice(orderId);
    OrderContract contract = requiredContract(invoice);
    ContractSubmission latest = latest(contract, request.submissionId());
    if (contract.getStatus() == ContractStatus.CONFIRMED
        && latest.getStatus() == ContractSubmissionStatus.CONFIRMED)
      return response(invoice, contract); // A replay must never extend the deposit deadline.
    requireEditableInvoice(invoice);
    requireVersion(contract, request.expectedVersion());
    if (contract.getStatus() != ContractStatus.PENDING_VERIFICATION
        || latest.getStatus() != ContractSubmissionStatus.PENDING) throw conflict();
    storage.requireStored(contract.getDocument());
    storage.requireStored(latest.getDocument());
    Instant now = Instant.now(clock);
    latest.setStatus(ContractSubmissionStatus.CONFIRMED);
    latest.setReviewedAt(now);
    latest.setReviewedBy(users.getCurrentUserId());
    latest.setReviewNote(null);
    contract.setStatus(ContractStatus.CONFIRMED);
    contract.setConfirmedAt(now);
    contract.setConfirmedBy(users.getCurrentUserId());
    invoice.setContractConfirmedAt(now);
    if (invoice.getDepositExpiresAt() == null)
      invoice.setDepositExpiresAt(now.plus(expirationSettings.current().depositPaymentTimeout()));
    submissions.saveAndFlush(latest);
    contracts.saveAndFlush(contract);
    return response(invoice, contract);
  }

  @Override
  @Transactional
  public ContractResponse requestChanges(String orderId, ContractReviewRequest request) {
    requireManager();
    Invoice invoice = lockInvoice(orderId);
    OrderContract contract = requiredContract(invoice);
    ContractSubmission latest = latest(contract, request.submissionId());
    String reason = request.reason() == null ? "" : request.reason().trim();
    if (reason.isEmpty() || reason.length() > 2000)
      throw new ApiException(ErrorCode.INVALID_REQUEST, "A review reason is required");
    if (contract.getStatus() == ContractStatus.CHANGES_REQUESTED
        && latest.getStatus() == ContractSubmissionStatus.CHANGES_REQUESTED
        && reason.equals(latest.getReviewNote())) return response(invoice, contract);
    requireEditableInvoice(invoice);
    requireVersion(contract, request.expectedVersion());
    if (contract.getStatus() != ContractStatus.PENDING_VERIFICATION
        || latest.getStatus() != ContractSubmissionStatus.PENDING) throw conflict();
    latest.setStatus(ContractSubmissionStatus.CHANGES_REQUESTED);
    latest.setReviewNote(reason);
    latest.setReviewedAt(Instant.now(clock));
    latest.setReviewedBy(users.getCurrentUserId());
    contract.setStatus(ContractStatus.CHANGES_REQUESTED);
    submissions.saveAndFlush(latest);
    contracts.saveAndFlush(contract);
    return response(invoice, contract);
  }

  @Override
  @Transactional(readOnly = true)
  public ContractDownloadResponse download(String orderId, String submissionId) {
    Invoice invoice = invoices.findByOrderId(orderId).orElse(null);
    Quote quote = invoice != null ? invoice.getQuote() : currentQuote(orderId);
    return downloadForQuote(orderId, quote.getId(), submissionId);
  }

  @Override
  @Transactional(readOnly = true)
  public ContractDownloadResponse downloadForQuote(String orderId, String quoteId, String submissionId) {
    Quote quote = quotes.findById(quoteId).orElseThrow(() -> new ApiException(ErrorCode.QUOTE_NOT_FOUND));
    if (!quote.getOrder().getId().equals(orderId)) throw denied();
    requireReader(quote.getOrder());
    requireQuoteReader(quote);
    OrderContract contract = contracts.findByQuoteId(quoteId).orElseThrow(() -> new ApiException(ErrorCode.CONTRACT_NOT_FOUND));
    ContractDocument document = contract.getDocument();
    if (submissionId != null) {
      var submission = history(contract).stream().filter(s -> s.getId().equals(submissionId)).findFirst()
          .orElseThrow(() -> new ApiException(ErrorCode.CONTRACT_NOT_FOUND));
      if (submission.getStatus() == ContractSubmissionStatus.SUPERSEDED || submission.getDocumentDeletedAt() != null)
        throw new ApiException(ErrorCode.CONTRACT_SUBMISSION_REPLACED);
      document = submission.getDocument();
    }
    return new ContractDownloadResponse(storage.download(document),
        Instant.now(clock).plusSeconds(storage.expiresInSeconds()), document.getFileName());
  }

  private Quote currentQuote(String orderId) {
    return quotes.findFirstByOrderIdAndStatusInOrderByQuoteVersionDesc(orderId,
        List.of(QuoteStatus.DRAFT, QuoteStatus.PENDING_REVIEW, QuoteStatus.APPROVED, QuoteStatus.ACCEPTED_BY_CUSTOMER, QuoteStatus.EXPIRED))
        .orElseThrow(() -> new ApiException(ErrorCode.QUOTE_NOT_FOUND));
  }

  private void requireQuoteReader(Quote quote) {
    if (!manager(users.getCurrentUser()) && !Set.of(QuoteStatus.APPROVED, QuoteStatus.ACCEPTED_BY_CUSTOMER, QuoteStatus.EXPIRED).contains(quote.getStatus()))
      throw denied();
  }

  /** All mutations serialize Order -> Invoice -> Contract/submission, matching deposit creation. */
  private Invoice lockInvoice(String orderId) {
    Order order =
        orders
            .findByIdForUpdate(orderId)
            .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
    requireReader(order);
    Invoice current =
        invoices
            .findByOrderId(orderId)
            .orElseThrow(() -> new ApiException(ErrorCode.INVOICE_NOT_FOUND));
    Invoice invoice =
        invoices
            .findByIdForUpdate(current.getId())
            .orElseThrow(() -> new ApiException(ErrorCode.INVOICE_NOT_FOUND));
    return invoice;
  }

  private void requireEditableInvoice(Invoice invoice) {
    if (invoice.getOrder().getOrderStatus() != OrderStatus.APPROVED
        || !Set.of(InvoiceStatus.ISSUED, InvoiceStatus.PARTIALLY_PAID, InvoiceStatus.PAID).contains(invoice.getStatus())
        || invoice.getQuote().getStatus() != QuoteStatus.ACCEPTED_BY_CUSTOMER) throw conflict();
  }

  private OrderContract requiredContract(Invoice invoice) {
    if (!invoice.isContractRequired()) throw conflict();
    return contracts
        .findByInvoiceId(invoice.getId())
        .orElseThrow(() -> new ApiException(ErrorCode.CONTRACT_NOT_FOUND));
  }

  private List<ContractSubmission> history(OrderContract contract) {
    return submissions.findAllByContractIdOrderBySubmissionNumberDesc(contract.getId());
  }

  private ContractSubmission latest(OrderContract contract, String id) {
    List<ContractSubmission> history = history(contract);
    if (history.isEmpty() || !Objects.equals(history.getFirst().getId(), id)) throw conflict();
    return history.getFirst();
  }

  private void requireVersion(OrderContract contract, Long expected) {
    if (expected == null || expected < 0 || !Objects.equals(contract.getVersion(), expected))
      throw new ApiException(ErrorCode.CONTRACT_VERSION_CONFLICT);
  }

  private boolean manager(User user) {
    return user.getRole() != null
        && user.getRole().getCode() != null
        && Set.of("MANAGER", "ADMIN").contains(user.getRole().getCode().name());
  }

  private void requireManager() {
    if (!manager(users.getCurrentUser())) throw denied();
  }

  private void requireReader(Order order) {
    User actor = users.getCurrentUser();
    if (!manager(actor) && !order.getCustomer().getId().equals(actor.getId())) throw denied();
  }

  private ApiException conflict() {
    return new ApiException(ErrorCode.CONTRACT_STATE_CONFLICT);
  }

  private ApiException denied() {
    return new ApiException(ErrorCode.ACCESS_DENIED);
  }

  private ContractResponse response(Invoice invoice, OrderContract contract) {
    return response(invoice.getQuote(), invoice, contract);
  }

  private ContractResponse response(Quote quote, Invoice invoice, OrderContract contract) {
    boolean required = invoice == null || invoice.isContractRequired();
    if (contract == null)
      return new ContractResponse(
          required,
          null,
          0,
          invoice == null ? null : invoice.getId(),
          quote.getId(),
          quote.getQuoteVersion(),
          null,
          required ? ContractStatus.NOT_ISSUED : ContractStatus.NOT_REQUIRED,
          null,
          null,
          null,
          null,
          null,
          List.of());
    return new ContractResponse(
        true,
        contract.getId(),
        contract.getVersion() == null ? 0 : contract.getVersion(),
        invoice == null ? null : invoice.getId(),
        quote.getId(),
        contract.getQuoteVersion(),
        contract.getContractNumber(),
        contract.getStatus(),
        contract.getIssuedAt(),
        contract.getIssuedBy(),
        contract.getConfirmedAt(),
        contract.getConfirmedBy(),
        mapper.document(contract.getDocument()),
        history(contract).stream().map(mapper::submission).toList());
  }
}
