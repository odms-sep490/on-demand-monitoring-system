
package com.ondemandmonitoring.depot.dto.request;

import com.ondemandmonitoring.depot.enums.DepotStatus;
import jakarta.validation.constraints.*;

public record DepotUpdateRequest(

        @NotBlank
        String name,

        String address,

        @NotNull
        @DecimalMin("-180.0")
        @DecimalMax("180.0")
        Double longitude,

        @NotNull
        @DecimalMin("-90.0")
        @DecimalMax("90.0")
        Double latitude,

        @NotNull
        DepotStatus status
) {
}
