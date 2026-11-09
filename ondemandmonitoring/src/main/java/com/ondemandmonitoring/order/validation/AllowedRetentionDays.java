package com.ondemandmonitoring.order.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Constraint(validatedBy = AllowedRetentionDaysValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface AllowedRetentionDays {
    String message() default "Thời hạn lưu trữ chỉ được chọn 30, 90, 180 hoặc 365 ngày.";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
