package com.ondemandmonitoring.ai.config;

import com.ondemandmonitoring.ai.domain.ChecklistImageAiSettings;
import com.ondemandmonitoring.ai.repository.ChecklistImageAiSettingsRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Seeds the singleton after Hibernate creates the schema, preserving every saved admin choice. */
@Component
@Order(15)
@RequiredArgsConstructor
public class ChecklistImageAiSettingsInitializer implements ApplicationRunner {
    private final ChecklistImageAiSettingsRepository repository;
    private final EntityManager entityManager;
    private final PlatformTransactionManager transactionManager;
    private final Clock clock;

    @Override
    public void run(ApplicationArguments args) {
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
                if (repository.existsById(ChecklistImageAiSettings.DEFAULT_ID)) return;
                var settings = new ChecklistImageAiSettings();
                settings.setUpdatedAt(clock.instant());
                // Persist is insert-only; merging the assigned ID could overwrite a concurrent admin update.
                entityManager.persist(settings);
                entityManager.flush();
            });
        } catch (DataIntegrityViolationException | PersistenceException conflict) {
            // Another application instance may have initialized the same row. Recheck after rollback.
            if (!repository.existsById(ChecklistImageAiSettings.DEFAULT_ID)) throw conflict;
        }
    }
}
