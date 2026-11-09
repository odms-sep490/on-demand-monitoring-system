package com.ondemandmonitoring.review.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.ZoneId;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.locationtech.jts.geom.Polygon;

@Entity
@Table(name = "review_region_policies", uniqueConstraints = @UniqueConstraint(
        name = "uk_review_region_policy_code", columnNames = "code"))
@Getter
@Setter
@NoArgsConstructor
public class ReviewRegionPolicy extends BaseEntity {

    @Column(nullable = false, length = 80)
    private String code;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false)
    private boolean enforceReview = true;

    @Column(nullable = false)
    private int priority;

    @Column(name = "service_area", nullable = false, columnDefinition = "geometry(Polygon,4326)")
    private Polygon serviceArea;

    @Column(name = "business_time_zone", nullable = false, length = 60)
    private String businessTimeZone;

    @Column(nullable = false)
    private double maxAltitudeM;

    @Column(nullable = false)
    private double longRouteM;

    @Column(nullable = false)
    private long tightDeadlineDays;

    public ZoneId businessZoneId() {
        return ZoneId.of(businessTimeZone);
    }
}
