
package com.ondemandmonitoring.depot.mapper;

import com.ondemandmonitoring.depot.domain.Depot;
import com.ondemandmonitoring.depot.dto.request.DepotCreateRequest;
import com.ondemandmonitoring.depot.dto.request.DepotUpdateRequest;
import com.ondemandmonitoring.depot.dto.response.DepotResponse;
import com.ondemandmonitoring.order.util.GeoReader;

import org.locationtech.jts.geom.Point;
import org.mapstruct.*;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface DepotMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(
            target = "location",
            expression = "java(toPoint(request.longitude(), request.latitude()))"
    )
    Depot toEntity(DepotCreateRequest request);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(
            target = "location",
            expression = "java(toPoint(request.longitude(), request.latitude()))"
    )
    void updateEntity(
            DepotUpdateRequest request,
            @MappingTarget Depot depot
    );

    @Mapping(
            target = "longitude",
            source = "location",
            qualifiedByName = "toLongitude"
    )
    @Mapping(
            target = "latitude",
            source = "location",
            qualifiedByName = "toLatitude"
    )
    @Mapping(target = "distanceKm", ignore = true)
    DepotResponse toResponse(Depot depot);

    default DepotResponse toResponse(
            Depot depot,
            Double distanceKm
    ) {
        DepotResponse response = toResponse(depot);

        return new DepotResponse(
                response.id(),
                response.name(),
                response.address(),
                response.longitude(),
                response.latitude(),
                response.status(),
                distanceKm
        );
    }

    @Named("toLongitude")
    default Double toLongitude(Point point) {
        return point == null
                ? null
                : GeoReader.getLongitude(point);
    }

    @Named("toLatitude")
    default Double toLatitude(Point point) {
        return point == null
                ? null
                : GeoReader.getLatitude(point);
    }

    default Point toPoint(Double longitude, Double latitude) {
        if (longitude == null || latitude == null) {
            return null;
        }

        return GeoReader.createPoint(longitude, latitude);
    }
}
