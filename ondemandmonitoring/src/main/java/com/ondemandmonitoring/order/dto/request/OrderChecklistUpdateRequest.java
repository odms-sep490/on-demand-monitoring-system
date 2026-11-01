package com.ondemandmonitoring.order.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class OrderChecklistUpdateRequest {
    @NotNull(message = "expectedRevision is required")
    @Min(value = 0, message = "expectedRevision must be >= 0")
    private Long expectedRevision;

    @Valid
    @Size(max = 100)
    private List<OrderChecklistUpdateItemRequest> items;
}

