package com.ondemandmonitoring.pricing.dto;

import com.ondemandmonitoring.pricing.enums.TerrainType;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record CustomWorkAssessmentRequest(@DecimalMin("0") BigDecimal estimatedFlightHours,
        @DecimalMin("0") BigDecimal estimatedDistanceKm, @DecimalMin("0") BigDecimal estimatedAreaHa,
        @Min(1) Integer pilotCount, @Min(1) Integer droneCount, @Min(0) Integer batteryUnits,
        @NotNull TerrainType terrain, boolean thermalRequired, boolean multispectralRequired,
        boolean nightOperation, @Size(max = 500) String specialEquipment,
        @Size(max = 1000) String otherCostDrivers, boolean assessmentComplete,
        Long expectedChecklistRevision) {
    public CustomWorkAssessmentRequest(BigDecimal estimatedFlightHours,
            BigDecimal estimatedDistanceKm, BigDecimal estimatedAreaHa,
            Integer pilotCount, Integer droneCount, Integer batteryUnits,
            TerrainType terrain, boolean thermalRequired, boolean multispectralRequired,
            boolean nightOperation, String specialEquipment,
            String otherCostDrivers, boolean assessmentComplete) {
        this(estimatedFlightHours, estimatedDistanceKm, estimatedAreaHa, pilotCount, droneCount, batteryUnits,
                terrain, thermalRequired, multispectralRequired, nightOperation, specialEquipment,
                otherCostDrivers, assessmentComplete, null);
    }
}
