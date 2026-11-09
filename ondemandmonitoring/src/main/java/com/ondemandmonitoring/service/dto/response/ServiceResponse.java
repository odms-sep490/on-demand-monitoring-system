package com.ondemandmonitoring.service.dto.response;

import java.time.Instant;
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
public class ServiceResponse {

    String id;
    String code;
    String name;
    String description;
    /** Legacy catalog price; only used as the price while no default checklist is priced. */
    BigDecimal basePrice;
    /** Default price: the sum of the default checklists (see priceSource). Null when not computed. */
    BigDecimal defaultPrice;
    /** CHECKLISTS or LEGACY_BASE_PRICE. */
    String priceSource;
    String imageUrl;
    String fallbackImageUrl;
    Boolean isActive;
    Instant createdAt;
    Instant updatedAt;
}
