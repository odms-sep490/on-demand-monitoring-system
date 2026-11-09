package com.ondemandmonitoring.weather.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.mission.domain.Mission;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(
        name = "mission_weather_checks",
        indexes = @Index(
                name = "idx_mission_weather_checks_mission_created",
                columnList = "mission_id,created_at"))
public class MissionWeatherCheck extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mission_id", nullable = false)
    Mission mission;

    @Column(name = "device_id", length = 50)
    String deviceId;

    @Column(nullable = false, length = 20)
    String status;

    @Column(name = "safe_to_fly", nullable = false)
    Boolean safeToFly;

    @Column(nullable = false, length = 500)
    String summary;

    @Column(name = "wind_speed_mps", nullable = false)
    Double windSpeedMps;

    @Column(name = "wind_gust_mps", nullable = false)
    Double windGustMps;

    @Column(name = "precipitation_mm_h", nullable = false)
    Double precipitationMmH;

    @Column(name = "visibility_km", nullable = false)
    Double visibilityKm;

    @Column(name = "temperature_c", nullable = false)
    Double temperatureC;

    @Column(name = "humidity_percent", nullable = false)
    Integer humidityPercent;

    @Column(name = "advisories", columnDefinition = "text")
    String advisories;

    @Column(name = "checked_at", nullable = false)
    Instant checkedAt;

    @Column(name = "inspector_accepted")
    Boolean inspectorAccepted = false;

    @Column(name = "inspector_accepted_by", length = 50)
    String inspectorAcceptedBy;

    @Column(name = "inspector_accepted_at")
    Instant inspectorAcceptedAt;
}
