package com.ondemandmonitoring.order.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Set;

public class AllowedRetentionDaysValidator implements ConstraintValidator<AllowedRetentionDays, Integer> {
    private static final Set<Integer> ALLOWED = Set.of(30, 90, 180, 365);

    @Override
    public boolean isValid(Integer value, ConstraintValidatorContext context) {
        return value == null || ALLOWED.contains(value);
    }
}
