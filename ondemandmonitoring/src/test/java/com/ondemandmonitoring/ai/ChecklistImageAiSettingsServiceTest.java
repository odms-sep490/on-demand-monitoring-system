package com.ondemandmonitoring.ai;

import com.ondemandmonitoring.ai.client.ChecklistImageVisionClient;
import com.ondemandmonitoring.ai.domain.ChecklistImageAiSettings;
import com.ondemandmonitoring.ai.repository.ChecklistImageAiSettingsRepository;
import com.ondemandmonitoring.ai.service.ChecklistImageAiSettingsService;
import com.ondemandmonitoring.ai.service.ChecklistImageAiSettingsService.UpdateRequest;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChecklistImageAiSettingsServiceTest {
    private final ChecklistImageAiSettingsRepository repository = mock(ChecklistImageAiSettingsRepository.class);
    private final ChecklistImageVisionClient vision = mock(ChecklistImageVisionClient.class);
    private final ChecklistImageAiSettings entity = new ChecklistImageAiSettings();
    private final ChecklistImageAiSettingsService service = new ChecklistImageAiSettingsService(
            repository, vision, Clock.fixed(Instant.parse("2026-11-26T00:00:00Z"), ZoneOffset.UTC));

    @Test void persistsBothSwitchDirectionsAndRejectsStaleUpdates() {
        when(repository.findByIdForUpdate("default")).thenReturn(Optional.of(entity));
        when(repository.findById("default")).thenReturn(Optional.of(entity));
        when(vision.available()).thenReturn(true);
        when(repository.saveAndFlush(entity)).thenAnswer(call -> {
            entity.setVersion(entity.getVersion() + 1);
            return entity;
        });
        var disabled = service.update(new UpdateRequest(false, 0L), "admin");
        assertFalse(disabled.effectiveEnabled());
        assertFalse(service.isEnabled());
        assertEquals("admin", disabled.updatedBy());
        assertEquals(1L, disabled.version());
        assertEquals(ErrorCode.CONCURRENT_UPDATE, assertThrows(ApiException.class,
                () -> service.update(new UpdateRequest(true, 0L), "another-admin")).getErrorCode());
        var enabled = service.update(new UpdateRequest(true, 1L), "admin");
        assertTrue(enabled.effectiveEnabled());
        assertTrue(service.isEnabled());
        service.update(new UpdateRequest(true, 2L), "admin");
        verify(repository, times(2)).saveAndFlush(entity);
    }

    @Test void reportsMissingProviderAndFailsClosedWhenSettingsAreAbsent() {
        assertFalse(service.isEnabled());
        when(repository.findById("default")).thenReturn(Optional.of(entity));
        var status = service.current();
        assertTrue(status.enabled());
        assertFalse(status.providerConfigured());
        assertFalse(status.effectiveEnabled());
    }
}
