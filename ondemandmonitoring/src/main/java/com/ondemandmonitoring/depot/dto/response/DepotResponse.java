package com.ondemandmonitoring.depot.dto.response;


import com.ondemandmonitoring.depot.enums.DepotStatus;

public record DepotResponse(
        String id,
        String name,
        String address,
        Double longitude,
        Double latitude,
        DepotStatus status,
        Double distanceKm
) {}
