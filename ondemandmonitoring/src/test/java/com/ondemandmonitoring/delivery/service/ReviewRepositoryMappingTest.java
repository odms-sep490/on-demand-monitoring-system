package com.ondemandmonitoring.delivery.service;

import com.ondemandmonitoring.delivery.repository.*;
import com.ondemandmonitoring.media.repository.MediaAssetRepository;
import jakarta.persistence.Entity;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.jpa.repository.Query;

/** Validates real entity mappings and HQL without connecting to any database. */
class ReviewRepositoryMappingTest {
    @Test void reviewAndRetentionQueriesMatchTheHibernateEntityModel() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        var configuration = new Configuration();
        configuration.setProperty("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        configuration.setProperty("hibernate.boot.allow_jdbc_metadata_access", "false");
        configuration.setProperty("hibernate.hbm2ddl.auto", "none");
        configuration.setProperty("jakarta.persistence.validation.mode", "none");
        for (var bean : scanner.findCandidateComponents("com.ondemandmonitoring"))
            configuration.addAnnotatedClass(Class.forName(bean.getBeanClassName()));
        try (var factory = configuration.buildSessionFactory(); var session = factory.openSession()) {
            for (var repository : new Class<?>[] {ResultRevisionCaseRepository.class, ResultReviewRoundEvidenceRepository.class,
                    OrderDeliveryRepository.class, ChecklistDeliveryDraftRepository.class, ResultReviewRoundRepository.class,
                    ResultRevisionRequestRepository.class, ResultRevisionRequestItemRepository.class, MediaAssetRepository.class}) {
                for (var method : repository.getMethods()) {
                    var query = method.getAnnotation(Query.class);
                    if (query != null && !query.nativeQuery()) {
                        if (query.value().stripLeading().toLowerCase(java.util.Locale.ROOT).startsWith("update"))
                            session.createMutationQuery(query.value());
                        else session.createSelectionQuery(query.value());
                    }
                }
            }
        }
    }
}
