package com.ondemandmonitoring.pricing.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

public final class BaseChecklistPriceConfig {
    private BaseChecklistPriceConfig() {}

    public record Update(
            @NotNull @DecimalMin("0") @Digits(integer = 19, fraction = 0) BigDecimal basePrice,
            @NotNull @DecimalMin("0.001") @Digits(integer = 11, fraction = 3) BigDecimal standardAreaM2,
            @DecimalMin("0") @Digits(integer = 19, fraction = 0) BigDecimal extraImageUnitPrice,
            @DecimalMin("0") @Digits(integer = 19, fraction = 0) BigDecimal extraVideoUnitPrice,
            @NotNull @Min(0) Long expectedVersion) {
        public Update(BigDecimal basePrice, BigDecimal standardAreaM2, Long expectedVersion) {
            this(basePrice, standardAreaM2, null, null, expectedVersion);
        }
    }

    public record Preview(@NotNull @DecimalMin("0.001") @Digits(integer = 11, fraction = 3) BigDecimal actualAreaM2) {}

    public record Row(String checklistId, String name, BigDecimal basePrice, BigDecimal standardAreaM2,
            BigDecimal extraImageUnitPrice, BigDecimal extraVideoUnitPrice, boolean active, long version,
            BigDecimal calculatedPrice, String calculationDisplay, String calculationError, List<String> packageNames) {
        public Row(String checklistId, String name, BigDecimal basePrice, BigDecimal standardAreaM2,
                boolean active, long version, BigDecimal calculatedPrice, String calculationDisplay,
                String calculationError) {
            this(checklistId, name, basePrice, standardAreaM2, BigDecimal.valueOf(50_000), BigDecimal.valueOf(100_000),
                    active, version, calculatedPrice,
                    calculationDisplay, calculationError, List.of());
        }
        public Row(String checklistId, String name, BigDecimal basePrice, BigDecimal standardAreaM2,
                boolean active, long version, BigDecimal calculatedPrice, String calculationDisplay,
                String calculationError, List<String> packageNames) {
            this(checklistId, name, basePrice, standardAreaM2, BigDecimal.valueOf(50_000), BigDecimal.valueOf(100_000),
                    active, version, calculatedPrice, calculationDisplay, calculationError, packageNames);
        }
    }
}
