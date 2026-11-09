package com.ondemandmonitoring.order.util;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;

public final class ServiceAreaPolicy {

    public static final double HCMC_HOME_LATITUDE = 10.7769;
    public static final double HCMC_HOME_LONGITUDE = 106.7009;

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);
    private static final Polygon HCMC_SERVICE_AREA = polygon(
            new Coordinate(106.3600, 10.3700),
            new Coordinate(106.2000, 10.5200),
            new Coordinate(106.2700, 10.8800),
            new Coordinate(106.5000, 11.1700),
            new Coordinate(106.9000, 11.1600),
            new Coordinate(107.1500, 10.9700),
            new Coordinate(107.1000, 10.5900),
            new Coordinate(106.9000, 10.3400)
    );

    private ServiceAreaPolicy() {
    }

    public static boolean contains(Point point) {
        return point != null && HCMC_SERVICE_AREA.covers(point);
    }

    public static void assertSupported(Point point, Geometry coverageArea) {
        if (!contains(point)) {
            throw new ApiException(
                    ErrorCode.INVALID_REQUEST,
                    "Service is currently limited to Ho Chi Minh City.");
        }
        if (coverageArea != null && !HCMC_SERVICE_AREA.covers(coverageArea)) {
            throw new ApiException(
                    ErrorCode.INVALID_REQUEST,
                    "Monitoring area must stay inside Ho Chi Minh City service area.");
        }
    }

    private static Polygon polygon(Coordinate... coordinates) {
        Coordinate[] closed = new Coordinate[coordinates.length + 1];
        System.arraycopy(coordinates, 0, closed, 0, coordinates.length);
        closed[coordinates.length] = new Coordinate(coordinates[0].x, coordinates[0].y);
        LinearRing shell = GEOMETRY_FACTORY.createLinearRing(closed);
        Polygon polygon = GEOMETRY_FACTORY.createPolygon(shell);
        polygon.setSRID(4326);
        return polygon;
    }
}
