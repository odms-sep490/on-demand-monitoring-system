package com.ondemandmonitoring.review.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ondemandmonitoring.review.domain.ReviewRegionPolicy;
import com.ondemandmonitoring.review.repository.ReviewRegionPolicyRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.mockito.ArgumentCaptor;

class ReviewRegionPolicySeederTest {

    @Test
    void seedsAnActivePolicyContainingTheDisplayedOrderLocation() {
        ReviewRegionPolicyRepository policies = mock(ReviewRegionPolicyRepository.class);
        when(policies.findByCode(ReviewRegionPolicySeeder.HCMC_POLICY_CODE))
                .thenReturn(Optional.empty());
        ReviewRegionPolicySeeder seeder = new ReviewRegionPolicySeeder(policies);

        seeder.run();

        ArgumentCaptor<ReviewRegionPolicy> saved = ArgumentCaptor.forClass(ReviewRegionPolicy.class);
        verify(policies).save(saved.capture());
        ReviewRegionPolicy policy = saved.getValue();
        Point orderPoint = new GeometryFactory(new PrecisionModel(), 4326)
                .createPoint(new Coordinate(106.695068, 10.642206));
        assertThat(policy.getCode()).isEqualTo(ReviewRegionPolicySeeder.HCMC_POLICY_CODE);
        assertThat(policy.isActive()).isTrue();
        assertThat(policy.getServiceArea().covers(orderPoint)).isTrue();
        assertThat(policy.getBusinessTimeZone()).isEqualTo("Asia/Ho_Chi_Minh");
        assertThat(policy.getMaxAltitudeM()).isPositive();
        assertThat(policy.getLongRouteM()).isPositive();
    }

    @Test
    void updatesTheExistingSeedRecordInsteadOfCreatingADuplicate() {
        ReviewRegionPolicyRepository policies = mock(ReviewRegionPolicyRepository.class);
        ReviewRegionPolicy existing = new ReviewRegionPolicy();
        when(policies.findByCode(ReviewRegionPolicySeeder.HCMC_POLICY_CODE))
                .thenReturn(Optional.of(existing));
        ReviewRegionPolicySeeder seeder = new ReviewRegionPolicySeeder(policies);

        seeder.run();

        verify(policies).save(existing);
        verify(policies).findByCode(ReviewRegionPolicySeeder.HCMC_POLICY_CODE);
    }
}
