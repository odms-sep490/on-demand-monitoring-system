package com.ondemandmonitoring.pricing.dto;

import com.ondemandmonitoring.pricing.enums.TerrainType;
import java.math.BigDecimal;

public record CustomWorkAssessmentResponse(String id, String orderId, BigDecimal estimatedFlightHours,
        BigDecimal estimatedDistanceKm, BigDecimal estimatedAreaHa, Integer pilotCount, Integer droneCount,
        Integer batteryUnits, TerrainType terrain, boolean thermalRequired, boolean multispectralRequired,
        boolean nightOperation, String specialEquipment, String otherCostDrivers,
        boolean assessmentComplete, long version) {}
