package com.ondemandmonitoring.flightpermit.repository;

import com.ondemandmonitoring.flightpermit.domain.FlightPermit;
import com.ondemandmonitoring.mission.enums.FlightPermitStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlightPermitRepository extends JpaRepository<FlightPermit, String> {

    Optional<FlightPermit> findByMissionId(String missionId);

    @EntityGraph(attributePaths = "mission")
    List<FlightPermit> findByStatusAndResourceHoldUntilLessThanEqualAndResourcesReleasedAtIsNull(
            FlightPermitStatus status, Instant holdUntil);
}
