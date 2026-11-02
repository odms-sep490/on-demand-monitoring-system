package com.ondemandmonitoring.flightpermit.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.enums.FlightPermitStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.FieldDefaults;

@Getter
@Setter
@Entity
@Table(name = "flight_permits", uniqueConstraints =
        @UniqueConstraint(name = "uk_flight_permits_mission", columnNames = "mission_id"))
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class FlightPermit extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mission_id", nullable = false, unique = true,
            foreignKey = @ForeignKey(name = "fk_flight_permits_mission"))
    Mission mission;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    FlightPermitStatus status = FlightPermitStatus.PENDING;

    @Column(name = "note", length = 1000)
    String note;

    @Column(name = "file_name", length = 160)
    String fileName;

    @Column(name = "content_type", length = 100)
    String contentType;

    @Column(name = "data_url", columnDefinition = "TEXT")
    String dataUrl;

    @Column(name = "resource_hold_until")
    Instant resourceHoldUntil;

    @Column(name = "resources_released_at")
    Instant resourcesReleasedAt;

    public static FlightPermit pendingFor(Mission mission) {
        FlightPermit permit = new FlightPermit();
        permit.setMission(mission);
        mission.setFlightPermit(permit);
        return permit;
    }
}
