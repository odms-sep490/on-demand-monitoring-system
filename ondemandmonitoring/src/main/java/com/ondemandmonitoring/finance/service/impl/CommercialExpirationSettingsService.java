package com.ondemandmonitoring.finance.service.impl;

import com.ondemandmonitoring.finance.domain.CommercialExpirationSettings;
import com.ondemandmonitoring.finance.domain.CommercialExpirationSettingsAudit;
import com.ondemandmonitoring.finance.dto.CommercialExpirationSettingsAuditResponse;
import com.ondemandmonitoring.finance.dto.CommercialExpirationSettingsRequest;
import com.ondemandmonitoring.finance.dto.CommercialExpirationSettingsResponse;
import com.ondemandmonitoring.finance.repository.CommercialExpirationSettingsAuditRepository;
import com.ondemandmonitoring.finance.repository.CommercialExpirationSettingsRepository;
import com.ondemandmonitoring.user.domain.UserIdentity;
import com.ondemandmonitoring.user.repository.UserIdentityRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CommercialExpirationSettingsService {
    public static final int MIN_TIMEOUT_HOURS = 1;
    public static final int MAX_TIMEOUT_HOURS = 8760;
    private static final int DEFAULT_QUOTATION_HOURS = 48;
    private static final int DEFAULT_DEPOSIT_HOURS = 24;
    private static final int DEFAULT_FINAL_PAYMENT_HOURS = 24;
    private static final Pattern COGNITO_SUB_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");

    private final CommercialExpirationSettingsRepository settingsRepository;
    private final CommercialExpirationSettingsAuditRepository auditRepository;
    private final UserIdentityRepository userIdentityRepository;
    private final Clock clock;

    private com.ondemandmonitoring.audit.service.AdminAuditLogService auditLog;

    /** Optional collaborator: records the change on the Admin audit log. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAuditLog(com.ondemandmonitoring.audit.service.AdminAuditLogService auditLog) {
        this.auditLog = auditLog;
    }

    @Transactional(readOnly = true)
    public CommercialExpirationSettingsResponse current() {
        return settingsRepository.findById(CommercialExpirationSettings.DEFAULT_ID)
                .map(this::response)
                .orElseGet(() -> new CommercialExpirationSettingsResponse(
                        DEFAULT_QUOTATION_HOURS, DEFAULT_DEPOSIT_HOURS, DEFAULT_FINAL_PAYMENT_HOURS,
                        "system", null));
    }

    @Transactional
    public CommercialExpirationSettingsResponse update(CommercialExpirationSettingsRequest request, String actor) {
        validate(request.quotationAcceptanceTimeoutHours());
        validate(request.depositPaymentTimeoutHours());
        validate(request.finalPaymentTimeoutHours());
        String normalizedActor = actor == null || actor.isBlank() ? "unknown" : actor;
        CommercialExpirationSettings settings = settingsRepository
                .findByIdForUpdate(CommercialExpirationSettings.DEFAULT_ID)
                .orElseGet(() -> defaultSettings(normalizedActor));
        Instant changedAt = Instant.now(clock);
        List<CommercialExpirationSettingsAudit> audits = new ArrayList<>(3);
        recordChange(audits, "quotationAcceptanceTimeoutHours", settings.getQuotationAcceptanceTimeoutHours(),
                request.quotationAcceptanceTimeoutHours(), normalizedActor, changedAt);
        recordChange(audits, "depositPaymentTimeoutHours", settings.getDepositPaymentTimeoutHours(),
                request.depositPaymentTimeoutHours(), normalizedActor, changedAt);
        recordChange(audits, "finalPaymentTimeoutHours", settings.getFinalPaymentTimeoutHours(),
                request.finalPaymentTimeoutHours(), normalizedActor, changedAt);
        if (!audits.isEmpty()) {
            settings.setQuotationAcceptanceTimeoutHours(request.quotationAcceptanceTimeoutHours());
            settings.setDepositPaymentTimeoutHours(request.depositPaymentTimeoutHours());
            settings.setFinalPaymentTimeoutHours(request.finalPaymentTimeoutHours());
            settings.setUpdatedBy(normalizedActor);
            settings.setUpdatedAt(changedAt);
            settingsRepository.save(settings);
            auditRepository.saveAll(audits);
            if (auditLog != null) {
                java.util.Map<String, Object> before = new java.util.LinkedHashMap<>();
                java.util.Map<String, Object> after = new java.util.LinkedHashMap<>();
                for (CommercialExpirationSettingsAudit change : audits) {
                    before.put(change.getSettingName(), change.getPreviousValue());
                    after.put(change.getSettingName(), change.getNewValue());
                }
                auditLog.record(com.ondemandmonitoring.audit.enums.AuditAction.UPDATE, "order_expiration_settings",
                        CommercialExpirationSettings.DEFAULT_ID, before, after);
            }
        }
        return response(settings);
    }

    @Transactional(readOnly = true)
    public List<CommercialExpirationSettingsAuditResponse> recentAudit(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        List<CommercialExpirationSettingsAudit> audits = auditRepository
                .findAllByOrderByChangedAtDesc(PageRequest.of(0, safeLimit));
        List<String> cognitoSubs = audits.stream()
                .map(CommercialExpirationSettingsAudit::getActor)
                .filter(actor -> actor != null && COGNITO_SUB_PATTERN.matcher(actor).matches())
                .distinct()
                .toList();
        Map<String, String> actorNames = cognitoSubs.isEmpty()
                ? Map.of()
                : userIdentityRepository.findAllByCognitoSubIn(cognitoSubs).stream()
                        .collect(Collectors.toMap(
                                UserIdentity::getCognitoSub,
                                identity -> identity.getUser().getFullName(),
                                (first, ignored) -> first));

        return audits.stream()
                .map(audit -> new CommercialExpirationSettingsAuditResponse(audit.getId(), audit.getSettingName(),
                        audit.getPreviousValue(), audit.getNewValue(),
                        actorNames.getOrDefault(audit.getActor(), audit.getActor()), audit.getChangedAt()))
                .toList();
    }

    private void recordChange(List<CommercialExpirationSettingsAudit> audits, String field, int previousValue,
            int newValue, String actor, Instant changedAt) {
        if (previousValue == newValue) return;
        CommercialExpirationSettingsAudit audit = new CommercialExpirationSettingsAudit();
        audit.setSettingName(field);
        audit.setPreviousValue(previousValue);
        audit.setNewValue(newValue);
        audit.setActor(actor);
        audit.setChangedAt(changedAt);
        audits.add(audit);
    }

    private CommercialExpirationSettings defaultSettings(String actor) {
        CommercialExpirationSettings settings = new CommercialExpirationSettings();
        settings.setId(CommercialExpirationSettings.DEFAULT_ID);
        settings.setQuotationAcceptanceTimeoutHours(DEFAULT_QUOTATION_HOURS);
        settings.setDepositPaymentTimeoutHours(DEFAULT_DEPOSIT_HOURS);
        settings.setFinalPaymentTimeoutHours(DEFAULT_FINAL_PAYMENT_HOURS);
        settings.setUpdatedBy(actor);
        settings.setUpdatedAt(Instant.now(clock));
        return settings;
    }

    private CommercialExpirationSettingsResponse response(CommercialExpirationSettings settings) {
        return new CommercialExpirationSettingsResponse(settings.getQuotationAcceptanceTimeoutHours(),
                settings.getDepositPaymentTimeoutHours(), settings.getFinalPaymentTimeoutHours(),
                settings.getUpdatedBy(), settings.getUpdatedAt());
    }

    private void validate(Integer hours) {
        if (hours == null || hours < MIN_TIMEOUT_HOURS || hours > MAX_TIMEOUT_HOURS)
            throw new IllegalArgumentException("Timeout must be between 1 and 8760 hours");
    }
}
