package com.ondemandmonitoring.service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.FieldDefaults;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class ServiceRequest {

    String code;

    @NotBlank(message = "Service name is required")
    String name;

    String description;

    @NotNull(message = "Service base price is required")
    @Positive(message = "Service base price must be greater than zero")
    @Digits(integer = 19, fraction = 0, message = "Service base price must be a whole VND amount")
    BigDecimal basePrice;

    Boolean isActive;
}
