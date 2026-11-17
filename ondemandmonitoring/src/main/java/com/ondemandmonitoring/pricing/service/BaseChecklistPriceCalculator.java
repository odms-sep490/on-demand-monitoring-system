package com.ondemandmonitoring.pricing.service;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.pricing.dto.ChecklistPriceBreakdown;
import com.ondemandmonitoring.pricing.enums.PricingCategory;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Arrays;
import java.util.Locale;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Polygon;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.ArrayList;

/** The only calculation used for configured Base Checklist prices. */
@Component
public class BaseChecklistPriceCalculator {
    private static final BigDecimal SQUARE_METERS_PER_HECTARE = BigDecimal.valueOf(10_000);
    private static final BigDecimal VND_ROUNDING_UNIT = BigDecimal.valueOf(1_000);

    public record Calculation(BigDecimal basePrice, BigDecimal standardAreaM2,
            BigDecimal actualAreaM2, BigDecimal calculatedPrice, String display) {}

    /** Measures the submitted WGS84 polygon using the same local projection used by the legacy area calculator. */
    public BigDecimal measureSquareMeters(Polygon polygon) {
        if (polygon == null || polygon.isEmpty())
            throw new ApiException(ErrorCode.PRICING_CALCULATION_FAILED, "Actual area must be greater than zero");
        Coordinate[] points = polygon.getExteriorRing().getCoordinates();
        if (points.length < 4)
            throw new ApiException(ErrorCode.PRICING_CALCULATION_FAILED, "Actual area must be greater than zero");
        double lat0 = Arrays.stream(points).mapToDouble(c -> c.y).average().orElse(0);
        double sx = 111_320d * Math.cos(Math.toRadians(lat0));
        double sy = 110_540d;
        double twiceArea = 0;
        for (int i = 0; i < points.length - 1; i++) {
            twiceArea += points[i].x * sx * points[i + 1].y * sy
                    - points[i + 1].x * sx * points[i].y * sy;
        }
        BigDecimal area = BigDecimal.valueOf(Math.abs(twiceArea) / 2d).setScale(3, RoundingMode.HALF_UP);
        if (area.signum() <= 0)
            throw new ApiException(ErrorCode.PRICING_CALCULATION_FAILED, "Actual area must be greater than zero");
        return area;
    }

    public Calculation calculate(BigDecimal basePrice, BigDecimal standardAreaM2, BigDecimal actualAreaM2) {
        if (basePrice == null || basePrice.signum() < 0
                || standardAreaM2 == null || standardAreaM2.signum() <= 0
                || actualAreaM2 == null || actualAreaM2.signum() <= 0) {
            throw new ApiException(ErrorCode.PRICING_CALCULATION_FAILED,
                    "Base price must be non-negative and both areas must be greater than zero");
        }
        BigDecimal excessAreaM2 = actualAreaM2.subtract(standardAreaM2).max(BigDecimal.ZERO);
        BigDecimal extraPrice = basePrice.multiply(excessAreaM2)
                .divide(standardAreaM2.multiply(VND_ROUNDING_UNIT), 0, RoundingMode.HALF_UP)
                .multiply(VND_ROUNDING_UNIT);
        BigDecimal price = basePrice.add(extraPrice);
        String display;
        if (excessAreaM2.signum() == 0) {
            display = "Giá cơ bản " + formatVnd(basePrice) + " VNĐ (bao gồm tối đa "
                    + formatHectares(standardAreaM2) + " ha); diện tích "
                    + formatHectares(actualAreaM2) + " ha, không phát sinh phụ phí diện tích.";
        } else {
            BigDecimal pricePerHectare = basePrice.multiply(SQUARE_METERS_PER_HECTARE)
                    .divide(standardAreaM2, 6, RoundingMode.HALF_UP);
            display = "Giá cơ bản " + formatVnd(basePrice) + " VNĐ (bao gồm "
                    + formatHectares(standardAreaM2) + " ha) + phụ phí diện tích vượt "
                    + formatRate(pricePerHectare) + " VNĐ/ha × "
                    + formatHectares(excessAreaM2) + " ha = " + formatVnd(extraPrice)
                    + " VNĐ (làm tròn đến 1.000 VNĐ); tổng " + formatVnd(price) + " VNĐ.";
        }
        return new Calculation(basePrice, standardAreaM2, actualAreaM2, price, display);
    }

    private String formatVnd(BigDecimal amount) {
        return format("#,##0").format(amount);
    }

    private String formatRate(BigDecimal amount) {
        return format("#,##0.######").format(amount.stripTrailingZeros());
    }

    private String formatHectares(BigDecimal squareMeters) {
        BigDecimal hectares = squareMeters.divide(SQUARE_METERS_PER_HECTARE, 6, RoundingMode.HALF_UP)
                .stripTrailingZeros();
        return format("#,##0.######").format(hectares);
    }

    private DecimalFormat format(String pattern) {
        DecimalFormat format = new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.forLanguageTag("vi-VN")));
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format;
    }

    public ChecklistPriceBreakdown breakdown(String checklistId, String name, Calculation calculation) {
        var line = new ChecklistPriceBreakdown.Line("AREA_SCALE", PricingCategory.CHECKLIST,
                calculation.display(), BigDecimal.ONE, calculation.basePrice(), calculation.calculatedPrice(),
                null, null, null, null);
        return new ChecklistPriceBreakdown(checklistId, name, null, false,
                0, 0, 0, 0, 0, 0, null, List.of(line), calculation.calculatedPrice(), false,
                calculation.basePrice(), calculation.standardAreaM2(), calculation.actualAreaM2(), calculation.display());
    }

    public ChecklistPriceBreakdown withMediaCharges(ChecklistPriceBreakdown base,
            int includedImages, int includedVideos, int requestedImages, int requestedVideos,
            BigDecimal extraImageUnitPrice, BigDecimal extraVideoUnitPrice) {
        int extraImages = Math.max(0, requestedImages - includedImages);
        int extraVideos = Math.max(0, requestedVideos - includedVideos);
        List<ChecklistPriceBreakdown.Line> lines = new ArrayList<>(base.lines());
        BigDecimal subtotal = base.subtotal();
        subtotal = addMediaCharge(lines, subtotal, "EXTRA_IMAGE", "Ảnh bổ sung vượt mức bao gồm",
                extraImages, extraImageUnitPrice, "ảnh");
        subtotal = addMediaCharge(lines, subtotal, "EXTRA_VIDEO", "Video bổ sung vượt mức bao gồm",
                extraVideos, extraVideoUnitPrice, "video");
        return new ChecklistPriceBreakdown(base.checklistId(), base.content(), base.pricingProfile(), base.custom(),
                includedImages, includedVideos, requestedImages, requestedVideos, extraImages, extraVideos,
                base.standardVideoDurationSeconds(), List.copyOf(lines), subtotal, base.quotePending(),
                base.basePrice(), base.standardAreaM2(), base.actualAreaM2(), base.calculationDisplay());
    }

    private BigDecimal addMediaCharge(List<ChecklistPriceBreakdown.Line> lines, BigDecimal subtotal,
            String component, String description, int quantity, BigDecimal unitPrice, String unitName) {
        if (quantity <= 0 || unitPrice == null) return subtotal;
        if (unitPrice.signum() < 0) throw new ApiException(ErrorCode.PRICING_CALCULATION_FAILED);
        BigDecimal amount = unitPrice.multiply(BigDecimal.valueOf(quantity));
        if (amount.signum() == 0) return subtotal;
        lines.add(new ChecklistPriceBreakdown.Line(component, PricingCategory.MEDIA,
                description + " (" + quantity + " × " + formatVnd(unitPrice) + " VNĐ/" + unitName + ")",
                BigDecimal.valueOf(quantity), unitPrice, amount, null, null, null, null));
        return subtotal.add(amount);
    }
}
