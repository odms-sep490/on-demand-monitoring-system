package com.ondemandmonitoring.devicecheck.dto.request;

import com.ondemandmonitoring.devicecheck.enums.DeviceCheckPhase;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Create/update body. {@code phase} and {@code code} are only read on create. */
public record DeviceCheckCriterionRequest(
        DeviceCheckPhase phase,
        @Size(max = 50) @Pattern(regexp = "^[a-z0-9_]*$", message = "code chỉ gồm chữ thường, số và dấu gạch dưới") String code,
        @NotBlank @Size(max = 120) String name,
        @Size(max = 500) String description,
        @Size(max = 80) String category,
        Boolean required,
        Boolean active,
        Long version) {
}
