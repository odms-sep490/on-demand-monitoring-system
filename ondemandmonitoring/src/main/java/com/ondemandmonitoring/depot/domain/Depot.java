package com.ondemandmonitoring.depot.domain;


import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.depot.enums.DepotStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import org.locationtech.jts.geom.Point;

@Entity
@Table(name = "depots")
@Getter
@Setter
@NoArgsConstructor
public class Depot extends BaseEntity {

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "address", length = 255)
    private String address;

    @Column(
            name = "location",
            nullable = false,
            columnDefinition = "geometry(Point,4326)"
    )
    private Point location;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private DepotStatus status = DepotStatus.ACTIVE;
}
