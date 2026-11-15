package com.ondemandmonitoring.pricing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ondemandmonitoring.common.exception.ApiException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class BaseChecklistPriceCalculatorTest {
    private final BaseChecklistPriceCalculator calculator = new BaseChecklistPriceCalculator();

    @Test
    void addsAProportionalSurchargeOnlyForAreaAboveTheIncludedStandard() {
        var result = calculator.calculate(new BigDecimal("200000"), new BigDecimal("100"), new BigDecimal("150"));

        assertThat(result.calculatedPrice()).isEqualByComparingTo("300000");
        assertThat(result.display()).isEqualTo(
                "Giá cơ bản 200.000 VNĐ (bao gồm 0,01 ha) + phụ phí diện tích vượt "
                        + "20.000.000 VNĐ/ha × 0,005 ha = 100.000 VNĐ (làm tròn đến 1.000 VNĐ); "
                        + "tổng 300.000 VNĐ.");
    }

    @Test
    void roundsAdditionalAreaPriceToTheNearestThousandUsingHalfUp() {
        var result = calculator.calculate(new BigDecimal("1000"), new BigDecimal("2"), new BigDecimal("3"));

        assertThat(result.calculatedPrice()).isEqualByComparingTo("2000");
    }

    @Test
    void rejectsMissingOrNonPositiveAreasAndNegativePrice() {
        assertThatThrownBy(() -> calculator.calculate(BigDecimal.ONE, null, BigDecimal.ONE))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> calculator.calculate(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> calculator.calculate(new BigDecimal("-1"), BigDecimal.ONE, BigDecimal.ONE))
                .isInstanceOf(ApiException.class);
    }
}
