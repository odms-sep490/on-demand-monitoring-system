package com.ondemandmonitoring.device.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.mission.domain.DeviceConnection;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.FieldDefaults;

@Getter
@Setter
@Entity
@Table(name = "device_telemetry")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class DeviceTelemetry extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "device_connection_id", nullable = false)
    DeviceConnection deviceConnection;

    @Column(name = "battery_percent")
    Double batteryPercent;

    @Column(name = "sim_x")
    Double simX;

    @Column(name = "sim_y")
    Double simY;

    @Column(name = "latitude")
    Double latitude;

    @Column(name = "longitude")
    Double longitude;

    @Column(name = "absolute_altitude_m")
    Double absoluteAltitudeM;

    @Column(name = "relative_altitude_m")
    Double relativeAltitudeM;

    @Column(name = "connected")
    Boolean connected;

    @Column(name = "recorded_at", nullable = false)
    Instant recordedAt = Instant.now();
}

