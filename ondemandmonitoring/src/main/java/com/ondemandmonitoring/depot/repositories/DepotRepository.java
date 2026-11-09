package com.ondemandmonitoring.depot.repositories;

import com.ondemandmonitoring.depot.domain.Depot;
import com.ondemandmonitoring.depot.enums.DepotStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface DepotRepository extends JpaRepository<Depot, String> {
    List<Depot> findAllByStatus(DepotStatus status);

    @Query(value = """
        SELECT d.*
        FROM depots d
        WHERE d.status = 'ACTIVE'
        ORDER BY ST_Distance(
            CAST(d.location AS geography),
            CAST(ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326) AS geography)
        ) ASC, d.id ASC
        LIMIT 1
        """, nativeQuery = true)
    Optional<Depot> findNearestActiveDepot(
        @Param("longitude") double longitude,
        @Param("latitude") double latitude
    );

    @Query(value = """
        SELECT ST_Distance(
            CAST(d.location AS geography),
            CAST(ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326) AS geography)
        )
        FROM depots d
        WHERE d.id = :depotId
        """, nativeQuery = true)
    Double calculateDistanceMeters(
        @Param("depotId") String depotId,
        @Param("longitude") double longitude,
        @Param("latitude") double latitude
    );
}
