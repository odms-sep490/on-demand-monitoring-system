package com.ondemandmonitoring.ai.service;

import com.ondemandmonitoring.ai.client.ChecklistImageVisionClient;
import com.ondemandmonitoring.ai.domain.ChecklistImageAiSettings;
import com.ondemandmonitoring.ai.repository.ChecklistImageAiSettingsRepository;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChecklistImageAiSettingsService {
    private final ChecklistImageAiSettingsRepository repository;
    private final ChecklistImageVisionClient vision;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.jdbc.core.JdbcTemplate db;

    public boolean isEnabled() {
        return repository.findById(ChecklistImageAiSettings.DEFAULT_ID)
                .map(ChecklistImageAiSettings::isEnabled).orElse(false);
    }

    public Status current() {
        return status(repository.findById(ChecklistImageAiSettings.DEFAULT_ID)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND)));
    }

    @Transactional
    public Status update(UpdateRequest request, String actor) {
        if (request == null || request.enabled() == null || request.expectedVersion() == null
                || request.expectedVersion() < 0)
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        var settings = repository.findByIdForUpdate(ChecklistImageAiSettings.DEFAULT_ID)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if (request.expectedVersion() != settings.getVersion())
            throw new ApiException(ErrorCode.CONCURRENT_UPDATE);
        if (settings.isEnabled() != request.enabled()) {
            settings.setEnabled(request.enabled());
            settings.setUpdatedBy(actor);
            settings.setUpdatedAt(clock.instant());
            repository.saveAndFlush(settings);
        }
        if (db != null) {
            db.update("""
                    UPDATE ai_models SET enabled = ?, status = ?, updated_at = now()
                    WHERE code = 'checklist-image-vision'
                    """, request.enabled(), request.enabled() ? "IDLE" : "DISABLED");
        }
        return status(settings);
    }

    private Status status(ChecklistImageAiSettings settings) {
        boolean configured = vision.available();
        return new Status(settings.isEnabled(), configured, settings.isEnabled() && configured,
                settings.getVersion(), settings.getUpdatedAt(), settings.getUpdatedBy());
    }

    public record UpdateRequest(@NotNull Boolean enabled, @NotNull @Min(0) Long expectedVersion) {}
    public record Status(boolean enabled, boolean providerConfigured, boolean effectiveEnabled,
            long version, Instant updatedAt, String updatedBy) {}
}
