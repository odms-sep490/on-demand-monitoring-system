package com.ondemandmonitoring.finance.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.finance.domain.Invoice;
import com.ondemandmonitoring.finance.domain.MissionPaymentMilestone;
import com.ondemandmonitoring.finance.domain.Payment;
import com.ondemandmonitoring.finance.dto.MissionFinancialSummaryResponse;
import com.ondemandmonitoring.finance.enums.PaymentType;
import com.ondemandmonitoring.finance.repository.InvoiceRepository;
import com.ondemandmonitoring.finance.repository.MissionPaymentMilestoneRepository;
import com.ondemandmonitoring.finance.repository.PaymentRepository;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.enums.MissionPurpose;
import com.ondemandmonitoring.mission.repository.MissionRepository;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MissionFinancialSummaryService {
    private final MissionRepository missions;
    private final InvoiceRepository invoices;
    private final MissionPaymentMilestoneRepository milestones;
    private final PaymentRepository payments;
    private final MissionPaymentMilestoneService allocationService;

    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    @Transactional(readOnly = true)
    public MissionFinancialSummaryResponse getForManager(String missionId) {
        Mission mission = missions.findByIdWithOrder(missionId)
                .orElseThrow(() -> new ApiException(ErrorCode.MISSION_NOT_FOUND));

        // Rework is remediation for a result that did not meet the accepted scope.
        // It is intentionally excluded from financial presentation and billing.
        if (mission.getPurpose() == MissionPurpose.REWORK) {
            return new MissionFinancialSummaryResponse(
                    mission.getId(), mission.getPurpose(), null, null, null, null, null, null);
        }

        Invoice invoice = invoices.findByOrderId(mission.getOrder().getId()).orElse(null);
        if (invoice == null) {
            return new MissionFinancialSummaryResponse(
                    mission.getId(), mission.getPurpose(), "VND", null, null, List.of(), null, null);
        }

        BigDecimal grossChecklistAmount = milestones.sumChecklistPricesByMission(
                        invoice.getQuote().getId(), mission.getOrder().getId(), List.of(missionId))
                .stream()
                .filter(row -> missionId.equals(row.getMissionId()))
                .map(MissionPaymentMilestoneRepository.MissionChecklistPriceTotal::getGrossAmount)
                .findFirst()
                .orElse(null);

        List<MissionFinancialSummaryResponse.PaymentHistoryEntry> depositPayments = payments
                .findAllByInvoiceIdAndPaymentTypeOrderByCreatedAtDesc(invoice.getId(), PaymentType.DEPOSIT)
                .stream()
                .map(this::paymentHistoryEntry)
                .toList();

        var invoiceSummary = new MissionFinancialSummaryResponse.InvoiceSummary(
                invoice.getId(), invoice.getTotalAmount(), invoice.getDepositAmount(),
                invoice.getPaidAmount(), invoice.getRemainingAmount(), invoice.effectiveDepositStatus(),
                invoice.getIssuedAt(), invoice.getDepositExpiresAt());

        MissionFinancialSummaryResponse.MissionPaymentSummary missionPayment = milestones
                .findByMissionId(missionId)
                .map(milestone -> missionPaymentSummary(milestone))
                .orElse(null);

        return new MissionFinancialSummaryResponse(
                mission.getId(), mission.getPurpose(), "VND", grossChecklistAmount,
                invoiceSummary, depositPayments, missionPayment,
                allocationService.priceBreakdown(invoice, missionId));
    }

    private MissionFinancialSummaryResponse.MissionPaymentSummary missionPaymentSummary(
            MissionPaymentMilestone milestone) {
        List<MissionFinancialSummaryResponse.PaymentHistoryEntry> history = payments
                .findAllByMissionPaymentMilestoneIdOrderByCreatedAtDesc(milestone.getId())
                .stream()
                .map(this::paymentHistoryEntry)
                .toList();
        return new MissionFinancialSummaryResponse.MissionPaymentSummary(
                milestone.getId(), milestone.getAmount(), milestone.getStatus(),
                milestone.getHandedOverAt(), milestone.getPaymentExpiresAt(), history);
    }

    private MissionFinancialSummaryResponse.PaymentHistoryEntry paymentHistoryEntry(Payment payment) {
        return new MissionFinancialSummaryResponse.PaymentHistoryEntry(
                payment.getId(), payment.getPaymentType(), payment.getAmount(), payment.getStatus(),
                payment.getProvider(), maskReference(payment.getTransactionReference()), payment.getPaidAt());
    }

    private String maskReference(String reference) {
        if (reference == null || reference.isBlank()) return null;
        String normalized = reference.trim();
        return "••••" + normalized.substring(Math.max(0, normalized.length() - 4));
    }
}
