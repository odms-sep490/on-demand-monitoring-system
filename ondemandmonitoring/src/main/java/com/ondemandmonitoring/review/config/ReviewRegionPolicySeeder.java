package com.ondemandmonitoring.review.config;

import com.ondemandmonitoring.review.domain.ReviewRegionPolicy;
import com.ondemandmonitoring.review.repository.ReviewRegionPolicyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Seeds the database-backed review policy represented by the HCMC service-area map. */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReviewRegionPolicySeeder implements CommandLineRunner {

    public static final String HCMC_POLICY_CODE = "HCMC_SERVICE_AREA";
    private static final GeometryFactory GEOMETRY_FACTORY =
            new GeometryFactory(new PrecisionModel(), 4326);

    private final ReviewRegionPolicyRepository policies;

    @Override
    @Transactional
    public void run(String... args) {
        ReviewRegionPolicy policy = policies.findByCode(HCMC_POLICY_CODE)
                .orElseGet(ReviewRegionPolicy::new);
        policy.setCode(HCMC_POLICY_CODE);
        policy.setName("Khu vực phục vụ TP. Hồ Chí Minh");
        policy.setActive(true);
        policy.setEnforceReview(true);
        policy.setPriority(100);
        policy.setServiceArea(hcmcServiceArea());
        policy.setBusinessTimeZone("Asia/Ho_Chi_Minh");
        policy.setMaxAltitudeM(120);
        policy.setLongRouteM(20_000);
        policy.setTightDeadlineDays(2);
        policies.save(policy);
        log.info("Seeded review region policy {}", HCMC_POLICY_CODE);
    }

    private Polygon hcmcServiceArea() {
        Coordinate[] ring = {
                new Coordinate(106.3600, 10.3700),
                new Coordinate(106.2000, 10.5200),
                new Coordinate(106.2700, 10.8800),
                new Coordinate(106.5000, 11.1700),
                new Coordinate(106.9000, 11.1600),
                new Coordinate(107.1500, 10.9700),
                new Coordinate(107.1000, 10.5900),
                new Coordinate(106.9000, 10.3400),
                new Coordinate(106.3600, 10.3700)
        };
        Polygon polygon = GEOMETRY_FACTORY.createPolygon(ring);
        polygon.setSRID(4326);
        return polygon;
    }
}
