package com.ondemandmonitoring.delivery.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.ondemandmonitoring.delivery.repository.ResultRevisionMediaHoldRepository;
import com.ondemandmonitoring.delivery.service.impl.ResultRevisionMediaRetention;
import com.ondemandmonitoring.media.infrastructure.s3.ResultRetentionProperties;
import java.time.*;
import org.junit.jupiter.api.Test;

class ResultRevisionMediaRetentionTest {
    @Test void closureStartsAFreshConfiguredGracePeriod() {
        var holds = mock(ResultRevisionMediaHoldRepository.class);
        var properties = new ResultRetentionProperties(); properties.setRevisionGraceDays(7);
        var service = new ResultRevisionMediaRetention(holds, properties);
        var closedAt = Instant.parse("2026-12-01T00:00:00Z");
        assertThat(service.release("case", closedAt)).isEqualTo(closedAt.plus(Duration.ofDays(7)));
        verify(holds).releaseCase("case", closedAt.plus(Duration.ofDays(7)));
    }
}
