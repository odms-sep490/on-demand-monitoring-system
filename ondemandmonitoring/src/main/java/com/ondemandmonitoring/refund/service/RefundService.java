package com.ondemandmonitoring.refund.service;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.finance.domain.Invoice;
import com.ondemandmonitoring.finance.domain.Payment;
import com.ondemandmonitoring.finance.enums.InvoiceStatus;
import com.ondemandmonitoring.finance.enums.PaymentProvider;
import com.ondemandmonitoring.finance.enums.PaymentStatus;
import com.ondemandmonitoring.finance.enums.PaymentType;
import com.ondemandmonitoring.finance.repository.InvoiceRepository;
import com.ondemandmonitoring.finance.repository.PaymentRepository;
import com.ondemandmonitoring.finance.service.impl.MissionPaymentMilestoneService;
import com.ondemandmonitoring.media.service.IMediaObjectStorage;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.enums.FlightPermitStatus;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.enums.LegalReviewStatus;
import com.ondemandmonitoring.order.enums.OrderCancellationReason;
import com.ondemandmonitoring.order.enums.OrderStatus;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.refund.domain.Refund;
import com.ondemandmonitoring.refund.domain.RefundReceipt;
import com.ondemandmonitoring.refund.domain.RefundTransferAttempt;
import com.ondemandmonitoring.refund.dto.RecordRefundTransferAttemptRequest;
import com.ondemandmonitoring.refund.dto.RefundDepositPaymentResponse;
import com.ondemandmonitoring.refund.dto.RefundDecisionRequest;
import com.ondemandmonitoring.refund.dto.RefundReceiptAccessResponse;
import com.ondemandmonitoring.refund.dto.RefundReceiptResponse;
import com.ondemandmonitoring.refund.dto.RefundResponse;
import com.ondemandmonitoring.refund.dto.RefundAdminSummaryResponse;
import com.ondemandmonitoring.refund.dto.RefundTransferAttemptResponse;
import com.ondemandmonitoring.refund.dto.RequestDepositRefundRequest;
import com.ondemandmonitoring.refund.enums.RefundStatus;
import com.ondemandmonitoring.refund.enums.RefundExecutionChannel;
import com.ondemandmonitoring.refund.enums.TransferOutcome;
import com.ondemandmonitoring.refund.event.RefundStatusChangedEvent;
import com.ondemandmonitoring.refund.repository.RefundReceiptRepository;
import com.ondemandmonitoring.refund.repository.RefundRepository;
import com.ondemandmonitoring.refund.repository.RefundTransferAttemptRepository;
import com.ondemandmonitoring.refund.validation.RefundReceiptValidator;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class RefundService {
    private static final List<MissionStatus> EXECUTION_STARTED_STATUSES = List.of(
            MissionStatus.IN_PROGRESS, MissionStatus.AWAITING_REVIEW, MissionStatus.PENDING_REVIEW,
            MissionStatus.COMPLETED, MissionStatus.FAILED);

    private final RefundRepository refunds;
    private final RefundTransferAttemptRepository attempts;
    private final RefundReceiptRepository receipts;
    private final OrderRepository orders;
    private final InvoiceRepository invoices;
    private final PaymentRepository payments;
    private final MissionPaymentMilestoneService missionMilestones;
    private final MissionRepository missions;
    private final AuthenticatedUserResolver currentUser;
    private final IMediaObjectStorage storage;
    private final ApplicationEventPublisher events;
    private final com.ondemandmonitoring.contract.repository.ContractCancellationRepository contractCancellations;

    @Value("${app.refund.receipt-max-image-bytes:5242880}")
    private long maxReceiptBytes = 5 * 1024 * 1024;

    @PreAuthorize("hasRole('MANAGER')")
    @Transactional
    public RefundResponse requestDepositRefund(String orderId, RequestDepositRefundRequest request) {
        Order order = orders.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found"));
        if (refunds.existsByOrderId(orderId)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "A refund request already exists for this Order");
        }
        ensureEligibleOrder(order);

        Invoice invoice = invoices.findByOrderId(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.INVOICE_NOT_FOUND));
        invoice = invoices.findByIdForUpdate(invoice.getId())
                .orElseThrow(() -> new ApiException(ErrorCode.INVOICE_NOT_FOUND));
        if (invoice.getStatus() == InvoiceStatus.CANCELLED || invoice.getStatus() == InvoiceStatus.REFUNDED) {
            throw invalid("The Order invoice is not eligible for a Deposit refund");
        }

        List<Payment> invoicePayments = payments.findAllByInvoiceIdForUpdate(invoice.getId());
        if (invoicePayments.stream().anyMatch(payment -> payment.getPaymentType() == PaymentType.DEPOSIT
                && (payment.getStatus() == PaymentStatus.PENDING || payment.getStatus() == PaymentStatus.PROCESSING))) {
            throw invalid("A pending Deposit payment must be verified before requesting a refund");
        }
        List<Payment> successfulDeposits = invoicePayments.stream()
                .filter(payment -> payment.getPaymentType() == PaymentType.DEPOSIT)
                .filter(payment -> payment.getStatus() == PaymentStatus.SUCCESS && payment.getPaidAt() != null)
                .filter(payment -> payment.getAmount() != null && payment.getAmount().signum() > 0)
                .toList();
        BigDecimal paidDepositTotal = successfulDeposits.stream()
                .map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean contractCancellation = isContractSystemCancellation(order);
        if (successfulDeposits.isEmpty() || (!contractCancellation && paidDepositTotal.compareTo(invoice.getDepositAmount()) < 0)) {
            throw invalid("A fully paid, verified Deposit is required before requesting a refund");
        }
        // Preserve the permit workflow's invoice cap. A system contract cancellation
        // returns all verified deposits, including partial payments and excess receipts.
        BigDecimal refundable = contractCancellation ? paidDepositTotal : invoice.getDepositAmount();

        User manager = currentUser.getCurrentUser();
        Refund refund = new Refund();
        refund.setOrder(order);
        refund.setCustomer(order.getCustomer());
        refund.setInvoice(invoice);
        refund.setDepositPayments(successfulDeposits);
        refund.setRequestedBy(manager);
        refund.setReason(request.reason().trim());
        refund.setStatus(RefundStatus.REQUESTED);
        refund.setRequestedAt(Instant.now());
        refund.setRefundableAmount(refundable.setScale(0, RoundingMode.UNNECESSARY));
        refund.setRefundedAmount(BigDecimal.ZERO);
        try {
            refund = refunds.saveAndFlush(refund);
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "A refund request already exists for this Order");
        }
        publishStatus(refund);
        return response(refund, false);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public List<RefundResponse> listRefunds(RefundStatus status) {
        List<Refund> found = status == null
                ? refunds.findAllByOrderByRequestedAtAsc()
                : refunds.findAllByStatusOrderByRequestedAtAsc(status);
        return found.stream().map(refund -> response(refund, true)).toList();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public RefundAdminSummaryResponse getAdminSummary() {
        List<RefundStatus> pendingStatuses = List.of(RefundStatus.REQUESTED, RefundStatus.PROCESSING);
        return new RefundAdminSummaryResponse(
                payments.sumSuccessfulServiceTransactions(),
                refunds.sumRefundedAmountByStatus(RefundStatus.COMPLETED),
                refunds.sumRefundableAmountByStatuses(pendingStatuses),
                refunds.countByStatusIn(pendingStatuses),
                missionMilestones.outstandingAmount(),
                missionMilestones.outstandingCount());
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public RefundResponse getAdminRefund(String refundId) {
        return response(loadRefund(refundId), true);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public RefundResponse startProcessing(String refundId) {
        Refund refund = lockRefund(refundId);
        if (refund.getStatus() == RefundStatus.REQUESTED) {
            validatedRefundAmount(refund);
            User admin = currentUser.getCurrentUser();
            refund.setStatus(RefundStatus.PROCESSING);
            refund.setProcessingAdmin(admin);
            refund.setProcessingAt(Instant.now());
            publishStatus(refund);
        } else if (refund.getStatus() == RefundStatus.PROCESSING) {
            validatedRefundAmount(refund);
        } else {
            throw invalid("Only a requested Refund can enter processing");
        }
        return response(refund, true);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public RefundResponse rejectRefund(String refundId, RefundDecisionRequest request) {
        Refund refund = lockRefund(refundId);
        if (refund.getStatus() != RefundStatus.REQUESTED) {
            throw invalid("A Refund can only be rejected before processing starts");
        }
        String reason = requiredDecisionReason(request.reason());
        User admin = currentUser.getCurrentUser();
        refund.setRejectedBy(admin);
        refund.setRejectedAt(Instant.now());
        refund.setRejectionReason(reason);
        refund.setStatus(RefundStatus.REJECTED);
        publishStatus(refund);
        return response(refund, true);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public RefundResponse cancelRefund(String refundId, RefundDecisionRequest request) {
        Refund refund = lockRefund(refundId);
        requireProcessing(refund);
        if (attempts.existsByRefundIdAndOutcome(refundId, TransferOutcome.SUCCESSFUL)) {
            throw invalid("A successful transfer attempt exists; resolve its confirmation before cancelling");
        }
        String reason = requiredDecisionReason(request.reason());
        User admin = currentUser.getCurrentUser();
        refund.setCancelledBy(admin);
        refund.setCancelledAt(Instant.now());
        refund.setCancellationReason(reason);
        refund.setStatus(RefundStatus.CANCELLED);
        publishStatus(refund);
        return response(refund, true);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public RefundResponse recordTransferAttempt(String refundId, RecordRefundTransferAttemptRequest request) {
        Refund refund = lockRefund(refundId);
        requireProcessing(refund);
        validatedRefundAmount(refund);
        if (request.executionChannel() == null) throw invalid("Choose an external refund method");
        String reference = request.transactionReference().trim();
        var existing = attempts.findByTransactionReference(reference);
        if (existing.isPresent()) {
            if (existing.get().getRefund().getId().equals(refundId)) return response(refund, true);
            throw invalid("This bank transaction reference has already been recorded");
        }
        if (attempts.existsByRefundIdAndOutcome(refundId, TransferOutcome.SUCCESSFUL)) {
            throw invalid("A successful transfer attempt is already recorded; upload its receipt and confirm the refund");
        }
        if (request.executionChannel() == RefundExecutionChannel.BANK_TRANSFER) {
            if (isBlank(request.beneficiaryBank()) || isBlank(request.beneficiaryName())
                    || isBlank(request.beneficiaryAccountNumber()) || !request.beneficiaryConfirmedWithCustomer()) {
                throw invalid("Confirmed bank beneficiary details are required for a bank transfer");
            }
        } else if (request.executionChannel() == RefundExecutionChannel.VNPAY_MERCHANT_PORTAL) {
            boolean singleVnPayDeposit = refund.getDepositPayments().size() == 1
                    && refund.getDepositPayments().get(0).getProvider() == PaymentProvider.VNPAY;
            if (!singleVnPayDeposit) {
                throw invalid("VNPay Merchant Portal refunds require exactly one linked VNPay Deposit payment");
            }
        }
        BigDecimal amount = wholeVnd(request.amount());
        if (amount.signum() <= 0 || amount.compareTo(refund.getRefundableAmount()) > 0) {
            throw invalid("Transfer amount must be positive and cannot exceed the refundable Deposit");
        }
        if (request.outcome() == TransferOutcome.SUCCESSFUL
                && amount.compareTo(refund.getRefundableAmount()) != 0) {
            throw invalid("A successful transfer must equal the full refundable Deposit amount");
        }
        if (request.transferredAt().isAfter(Instant.now())) {
            throw invalid("Transfer date and time cannot be in the future");
        }
        String failureReason = trimToNull(request.failureReason());
        if (request.outcome() == TransferOutcome.FAILED && failureReason == null) {
            throw invalid("A failure reason is required for a failed transfer attempt");
        }

        RefundTransferAttempt attempt = new RefundTransferAttempt();
        attempt.setRefund(refund);
        attempt.setAttemptNumber(attempts.countByRefundId(refundId) + 1);
        attempt.setExecutionChannel(request.executionChannel());
        attempt.setBeneficiaryBank(trimToNull(request.beneficiaryBank()));
        attempt.setBeneficiaryName(trimToNull(request.beneficiaryName()));
        attempt.setBeneficiaryAccountNumber(trimToNull(request.beneficiaryAccountNumber()));
        attempt.setBeneficiaryConfirmedWithCustomer(
                request.executionChannel() == RefundExecutionChannel.BANK_TRANSFER
                        && request.beneficiaryConfirmedWithCustomer());
        attempt.setAmount(amount);
        attempt.setTransactionReference(reference);
        attempt.setTransferredAt(request.transferredAt());
        attempt.setNote(trimToNull(request.note()));
        attempt.setOutcome(request.outcome());
        attempt.setFailureReason(request.outcome() == TransferOutcome.FAILED ? failureReason : null);
        attempt.setRecordedBy(currentUser.getCurrentUser());
        try {
            attempts.saveAndFlush(attempt);
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "This transfer attempt was already recorded");
        }
        return response(refund, true);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public RefundResponse uploadReceipt(String refundId, String attemptId, MultipartFile file) {
        Refund refund = lockRefund(refundId);
        requireProcessing(refund);
        RefundTransferAttempt attempt = attempts.findByIdAndRefundId(attemptId, refundId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Transfer attempt not found"));
        if (attempt.getOutcome() != TransferOutcome.SUCCESSFUL) {
            throw invalid("Evidence can only be attached to a successful external refund");
        }
        if (file == null || file.isEmpty() || file.getSize() > maxReceiptBytes) {
            throw invalid("Receipt image must be non-empty and within the configured size limit");
        }
        if (receipts.countByAttemptId(attemptId) >= 10) {
            throw invalid("At most 10 receipt images are allowed for a transfer attempt");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException exception) {
            throw invalid("Receipt image could not be read");
        }
        RefundReceiptValidator.ValidatedImage image = RefundReceiptValidator.validate(bytes, maxReceiptBytes);
        String key = storageKey(refundId, attemptId, image.extension());
        IMediaObjectStorage.StoredObject stored;
        try {
            stored = storage.put(key, image.contentType(), bytes.length,
                    new ByteArrayInputStream(bytes), "deposit-refund-receipt");
        } catch (RuntimeException exception) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Receipt image could not be stored privately");
        }

        RefundReceipt receipt = new RefundReceipt();
        receipt.setAttempt(attempt);
        receipt.setStorageBucket(stored.bucket());
        receipt.setStorageKey(stored.key());
        receipt.setContentType(image.contentType());
        receipt.setDisplayName("external-refund-evidence-" + attempt.getAttemptNumber() + "-"
                + (receipts.countByAttemptId(attemptId) + 1) + "." + image.extension());
        receipt.setByteSize(image.byteSize());
        try {
            receipts.saveAndFlush(receipt);
            attempt.getReceipts().add(receipt);
            cleanupOnRollback(stored.bucket(), stored.key());
        } catch (RuntimeException exception) {
            storage.deleteQuietly(stored.bucket(), stored.key());
            throw exception;
        }
        return response(refund, true);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public RefundResponse confirmRefund(String refundId) {
        Refund refund = lockRefund(refundId);
        if (refund.getStatus() == RefundStatus.COMPLETED) return response(refund, true);
        requireProcessing(refund);
        validatedRefundAmount(refund);
        List<RefundTransferAttempt> refundAttempts = attempts.findAllByRefundIdOrderByAttemptNumberAsc(refundId);
        RefundTransferAttempt successful = refundAttempts.stream()
                .filter(attempt -> attempt.getOutcome() == TransferOutcome.SUCCESSFUL)
                .findFirst()
                .orElseThrow(() -> invalid("A successful external refund must be recorded before confirmation"));
        validateSuccessfulAttempt(refund, successful);
        List<RefundReceipt> evidence = successful.getReceipts();
        if (evidence == null || evidence.isEmpty()) {
            throw invalid("At least one valid external refund evidence image is required before confirmation");
        }
        boolean storedEvidenceVerified = evidence.stream().anyMatch(this::isStoredReceiptValid);
        if (!storedEvidenceVerified) {
            throw invalid("A valid stored external refund evidence image is required before confirmation");
        }

        User admin = currentUser.getCurrentUser();
        refund.setRefundedAmount(successful.getAmount());
        refund.setCompletedBy(admin);
        refund.setCompletedAt(Instant.now());
        refund.setStatus(RefundStatus.COMPLETED);
        publishStatus(refund);
        return response(refund, true);
    }

    @PreAuthorize("hasAnyRole('CUSTOMER','MANAGER')")
    @Transactional
    public RefundResponse getRefundForOrder(String orderId) {
        Refund refund = refunds.findByOrderId(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No refund exists for this Order"));
        authorizeView(refund);
        return response(refund, false);
    }

    @PreAuthorize("isAuthenticated()")
    @Transactional
    public RefundReceiptAccessResponse getReceiptAccess(String refundId, String receiptId) {
        RefundReceipt receipt = receipts.findById(receiptId)
                .filter(item -> item.getAttempt().getRefund().getId().equals(refundId))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Receipt not found"));
        Refund refund = receipt.getAttempt().getRefund();
        authorizeView(refund);
        User user = currentUser.getCurrentUser();
        if (user.getRole().getCode() == RoleCode.CUSTOMER && refund.getStatus() != RefundStatus.COMPLETED) {
            throw new ApiException(ErrorCode.ACCESS_DENIED);
        }
        String url = storage.createPresignedGetUrl(receipt.getStorageBucket(), receipt.getStorageKey());
        return new RefundReceiptAccessResponse(url, receipt.getContentType(), receipt.getDisplayName(),
                storage.presignedUrlExpiresSeconds());
    }

    private boolean isContractSystemCancellation(Order order) {
        return order != null && order.getOrderStatus() == OrderStatus.CANCELLED
                && order.getCancellationReason() == OrderCancellationReason.CONTRACT_SYSTEM_RESPONSIBILITY
                && contractCancellations.findByOrderId(order.getId()).filter(c ->
                    c.getResponsibility() == com.ondemandmonitoring.contract.enums.CancellationResponsibility.SYSTEM
                    && c.getStatus() == com.ondemandmonitoring.contract.enums.ContractCancellationStatus.REFUND_REQUESTED
                    && c.getContract().getStatus() == com.ondemandmonitoring.contract.enums.ContractStatus.CANCELLED).isPresent();
    }

    private void ensureEligibleOrder(Order order) {
        OrderStatus status = order.getOrderStatus();
        if (isContractSystemCancellation(order)) {
            if (!missions.findAllByOrderIdOrderByScheduledStartAtAscIdAsc(order.getId()).isEmpty())
                throw invalid("Contract cancellation refunds are only available before Mission planning");
            return;
        }
        boolean permitCancelled = status == OrderStatus.CANCELLED
                && order.getCancellationReason() == OrderCancellationReason.PERMIT_REJECTED;
        if (status != OrderStatus.APPROVED && !permitCancelled) {
            throw invalid("Only an approved Order stopped by a permit rejection can request a Deposit refund");
        }
        if (order.getFlightAreaReview() == null
                || order.getFlightAreaReview().getLegalReviewStatus() != LegalReviewStatus.REJECTED) {
            throw invalid("The required flight permit must be rejected before requesting a Deposit refund");
        }
        List<Mission> orderMissions = missions.findAllByOrderIdOrderByScheduledStartAtAscIdAsc(order.getId());
        boolean hasRejectedPermit = orderMissions.stream().anyMatch(mission -> mission.getFlightPermit() != null
                && mission.getFlightPermit().getStatus() == FlightPermitStatus.REJECTED);
        boolean wasCancelledForPermit = permitCancelled;
        if (!hasRejectedPermit && !wasCancelledForPermit
                && !"Permit verification was rejected".equals(order.getFlightAreaReview().getFlightAreaReviewReason())) {
            throw invalid("A permit rejection must be recorded for this Order before requesting a refund");
        }
        boolean executionStarted = orderMissions.stream().anyMatch(mission -> mission.getActualStartAt() != null
                || mission.getCompletedAt() != null || EXECUTION_STARTED_STATUSES.contains(mission.getStatus()));
        if (executionStarted || status == OrderStatus.IN_PROGRESS || status == OrderStatus.COMPLETED) {
            throw invalid("A Deposit refund cannot be requested after Order execution has started");
        }
    }

    private void validateSuccessfulAttempt(Refund refund, RefundTransferAttempt attempt) {
        boolean completeBankTransfer = attempt.getExecutionChannel() == RefundExecutionChannel.BANK_TRANSFER
                && !isBlank(attempt.getBeneficiaryBank()) && !isBlank(attempt.getBeneficiaryName())
                && !isBlank(attempt.getBeneficiaryAccountNumber()) && attempt.isBeneficiaryConfirmedWithCustomer();
        boolean completeVnPayPortalRefund = attempt.getExecutionChannel() == RefundExecutionChannel.VNPAY_MERCHANT_PORTAL
                && refund.getDepositPayments().size() == 1
                && refund.getDepositPayments().get(0).getProvider() == PaymentProvider.VNPAY;
        if ((!completeBankTransfer && !completeVnPayPortalRefund)
                || isBlank(attempt.getTransactionReference()) || attempt.getTransferredAt() == null
                || attempt.getAmount() == null
                || attempt.getAmount().compareTo(refund.getRefundableAmount()) != 0) {
            throw invalid("Complete external refund details are required before confirmation");
        }
    }

    private boolean isStoredReceiptValid(RefundReceipt receipt) {
        try {
            var stored = storage.inspect(receipt.getStorageBucket(), receipt.getStorageKey());
            return receipt.getByteSize() > 0 && receipt.getByteSize() <= maxReceiptBytes
                    && stored.contentLength() != null && stored.contentLength() == receipt.getByteSize()
                    && receipt.getContentType().equals(stored.contentType())
                    && List.of("image/jpeg", "image/png").contains(receipt.getContentType());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private void authorizeView(Refund refund) {
        User user = currentUser.getCurrentUser();
        RoleCode role = user.getRole().getCode();
        if (role == RoleCode.ADMIN) return;
        if (role == RoleCode.MANAGER && refund.getRequestedBy().getId().equals(user.getId())) return;
        if (role == RoleCode.CUSTOMER && refund.getCustomer().getId().equals(user.getId())) return;
        throw new ApiException(ErrorCode.ACCESS_DENIED);
    }

    private void requireProcessing(Refund refund) {
        if (refund.getStatus() != RefundStatus.PROCESSING) {
            throw invalid("Start refund processing before recording transfer details or receipts");
        }
    }

    private BigDecimal validatedRefundAmount(Refund refund) {
        BigDecimal eligibleDepositTotal = refund.getDepositPayments().stream()
                .filter(payment -> payment.getPaymentType() == PaymentType.DEPOSIT)
                .filter(payment -> payment.getStatus() == PaymentStatus.SUCCESS && payment.getPaidAt() != null)
                .filter(payment -> "VND".equals(payment.getCurrency()))
                .filter(payment -> payment.getAmount() != null && payment.getAmount().signum() > 0)
                .map(Payment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal invoiceDeposit = refund.getInvoice().getDepositAmount();
        if (eligibleDepositTotal.signum() <= 0 || invoiceDeposit == null || invoiceDeposit.signum() <= 0) {
            throw invalid("Refund no longer has an eligible paid Deposit");
        }
        BigDecimal allowedAmount = isContractSystemCancellation(refund.getOrder()) ? eligibleDepositTotal : eligibleDepositTotal.min(invoiceDeposit);
        BigDecimal requestedAmount = wholeVnd(refund.getRefundableAmount());
        if (requestedAmount.compareTo(allowedAmount) != 0) {
            throw invalid("Refund amount no longer matches the eligible successful Deposit payments");
        }
        return requestedAmount;
    }

    private String requiredDecisionReason(String reason) {
        if (reason == null || reason.isBlank()) throw invalid("A reason is required");
        return reason.trim();
    }

    private Refund lockRefund(String refundId) {
        return refunds.findByIdForUpdate(refundId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Refund not found"));
    }

    private Refund loadRefund(String refundId) {
        return refunds.findById(refundId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Refund not found"));
    }

    private RefundResponse response(Refund refund, boolean showFullAccountNumber) {
        List<RefundTransferAttemptResponse> transferResponses =
                attempts.findAllByRefundIdOrderByAttemptNumberAsc(refund.getId()).stream()
                        .map(attempt -> attemptResponse(attempt, showFullAccountNumber)).toList();
        List<RefundDepositPaymentResponse> depositPayments = refund.getDepositPayments().stream()
                .map(payment -> new RefundDepositPaymentResponse(payment.getId(), payment.getAmount(),
                        payment.getCurrency(), payment.getProvider().name(), payment.getTransactionReference(),
                        payment.getPaidAt()))
                .toList();
        return new RefundResponse(refund.getId(), refund.getOrder().getId(), refund.getOrder().getOrderCode(),
                refund.getOrder().getTitle(), refund.getCustomer().getId(), refund.getCustomer().getFullName(),
                refund.getCustomer().getEmail(), refund.getInvoice().getId(), refund.getInvoice().getInvoiceNumber(),
                depositPayments, refund.getReason(), refund.getStatus(), refund.getRequestedBy().getId(),
                refund.getRequestedBy().getFullName(), refund.getRequestedAt(),
                refund.getProcessingAdmin() == null ? null : refund.getProcessingAdmin().getId(),
                refund.getProcessingAdmin() == null ? null : refund.getProcessingAdmin().getFullName(),
                refund.getProcessingAt(), refund.getCompletedBy() == null ? null : refund.getCompletedBy().getId(),
                refund.getCompletedBy() == null ? null : refund.getCompletedBy().getFullName(), refund.getCompletedAt(),
                refund.getRejectedBy() == null ? null : refund.getRejectedBy().getId(),
                refund.getRejectedBy() == null ? null : refund.getRejectedBy().getFullName(), refund.getRejectedAt(),
                refund.getRejectionReason(),
                refund.getCancelledBy() == null ? null : refund.getCancelledBy().getId(),
                refund.getCancelledBy() == null ? null : refund.getCancelledBy().getFullName(), refund.getCancelledAt(),
                refund.getCancellationReason(), refund.getRefundableAmount(), refund.getRefundedAmount(),
                transferResponses);
    }

    private RefundTransferAttemptResponse attemptResponse(RefundTransferAttempt attempt, boolean showFullAccountNumber) {
        List<RefundReceiptResponse> receiptResponses = attempt.getReceipts() == null ? List.of()
                : attempt.getReceipts().stream().map(receipt -> new RefundReceiptResponse(receipt.getId(),
                        receipt.getDisplayName(), receipt.getContentType(), receipt.getByteSize(), receipt.getCreatedAt(),
                        "/api/deposit-refunds/" + attempt.getRefund().getId() + "/receipts/"
                                + receipt.getId() + "/access")).toList();
        String accountNumber = showFullAccountNumber ? attempt.getBeneficiaryAccountNumber()
                : maskAccount(attempt.getBeneficiaryAccountNumber());
        return new RefundTransferAttemptResponse(attempt.getId(), attempt.getAttemptNumber(),
                attempt.getExecutionChannel(),
                attempt.getBeneficiaryBank(), attempt.getBeneficiaryName(), accountNumber,
                attempt.isBeneficiaryConfirmedWithCustomer(), attempt.getAmount(),
                attempt.getTransactionReference(), attempt.getTransferredAt(), attempt.getNote(), attempt.getOutcome(),
                attempt.getFailureReason(), attempt.getRecordedBy().getId(), attempt.getRecordedBy().getFullName(),
                attempt.getCreatedAt(), receiptResponses);
    }

    private String storageKey(String refundId, String attemptId, String extension) {
        String prefix = storage.prefix();
        String key = "refund-receipts/" + refundId + "/" + attemptId + "/" + UUID.randomUUID() + "." + extension;
        return prefix == null || prefix.isBlank() ? key : prefix.replaceAll("^/+|/+$", "") + "/" + key;
    }

    private void cleanupOnRollback(String bucket, String key) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) storage.deleteQuietly(bucket, key);
            }
        });
    }

    private void publishStatus(Refund refund) {
        events.publishEvent(new RefundStatusChangedEvent(refund.getCustomer().getId(), refund.getOrder().getId(),
                refund.getId(), refund.getStatus(), Instant.now()));
    }

    private BigDecimal wholeVnd(BigDecimal amount) {
        try {
            return amount.setScale(0, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw invalid("Refund transfers must use whole VND amounts");
        }
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String maskAccount(String accountNumber) {
        if (accountNumber == null || accountNumber.length() <= 4) return "****";
        return "*".repeat(Math.max(4, accountNumber.length() - 4))
                + accountNumber.substring(accountNumber.length() - 4);
    }

    private ApiException invalid(String message) {
        return new ApiException(ErrorCode.INVALID_REQUEST, message);
    }
}
