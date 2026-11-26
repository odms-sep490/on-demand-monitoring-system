package com.ondemandmonitoring.ai;

import com.ondemandmonitoring.ai.config.ChecklistImageAiSettingsInitializer;
import com.ondemandmonitoring.ai.domain.ChecklistImageAiSettings;
import com.ondemandmonitoring.ai.repository.ChecklistImageAiSettingsRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChecklistImageAiSettingsInitializerTest {
    private final ChecklistImageAiSettingsRepository repository = mock(ChecklistImageAiSettingsRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final Instant now = Instant.parse("2026-11-27T00:00:00Z");

    private ChecklistImageAiSettingsInitializer initializer() {
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        return new ChecklistImageAiSettingsInitializer(repository, entityManager, transactions,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test void createsTheMissingSingletonAfterSchemaCreation() {
        initializer().run(null);
        var captured = ArgumentCaptor.forClass(ChecklistImageAiSettings.class);
        verify(entityManager).persist(captured.capture());
        assertEquals("default", captured.getValue().getId());
        assertTrue(captured.getValue().isEnabled());
        assertEquals(now, captured.getValue().getUpdatedAt());
        verify(entityManager).flush();
    }

    @Test void neverOverwritesExistingConfiguration() {
        when(repository.existsById("default")).thenReturn(true);
        initializer().run(null);
        verifyNoInteractions(entityManager);
        verify(repository, never()).save(any());
    }

    @Test void acceptsAnotherInstancesInsertAfterRollingBackTheConflict() {
        when(repository.existsById("default")).thenReturn(false, true);
        doThrow(new PersistenceException("Concurrent insert")).when(entityManager).flush();
        assertDoesNotThrow(() -> initializer().run(null));
        verify(transactions).rollback(any());
        verify(repository, times(2)).existsById("default");
    }

    @Test void doesNotHideInitializationFailures() {
        doThrow(new PersistenceException("Storage unavailable")).when(entityManager).flush();
        assertThrows(PersistenceException.class, () -> initializer().run(null));
    }
}
