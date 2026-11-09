package com.ondemandmonitoring.dashboard.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ManagerDashboardAnalyticsRepositoryTest {
    @Test
    void quoteValueUsesOnlyLatestBusinessQuotePerOrder() {
        EntityManager entityManager = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(entityManager.createNativeQuery(argThat(sql ->
                sql.contains("max(q2.quote_version)")
                        && sql.contains("APPROVED")
                        && sql.contains("ACCEPTED_BY_CUSTOMER")))).thenReturn(query);
        when(query.setParameter(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(query);
        when(query.getSingleResult()).thenReturn(new BigDecimal("3500000"));

        var repository = new ManagerDashboardAnalyticsRepository(entityManager);
        var total = repository.totalCurrentQuoteValue(
                Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"));

        assertThat(total).isEqualByComparingTo("3500000");
        verify(entityManager).createNativeQuery(argThat(sql -> sql.contains("q.quote_version =")));
    }
}
