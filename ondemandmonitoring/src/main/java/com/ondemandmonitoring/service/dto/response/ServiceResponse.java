package com.ondemandmonitoring.service.dto.response;

import java.time.Instant;
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
public class ServiceResponse {

    String id;
    String code;
    String name;
    String description;
    /** Default price: the sum of the active Base Checklists. */
    java.math.BigDecimal defaultPrice;
    /** CHECKLISTS or UNPRICED. */
    String priceSource;
    String imageUrl;
    String fallbackImageUrl;
    Boolean isActive;
    Instant createdAt;
    Instant updatedAt;
}
