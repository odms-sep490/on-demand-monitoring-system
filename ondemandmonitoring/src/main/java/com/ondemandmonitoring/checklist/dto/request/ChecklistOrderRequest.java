package com.ondemandmonitoring.checklist.dto.request;

import jakarta.validation.constraints.*;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
public class ChecklistOrderRequest {
    @NotNull
    @Min(0)
    private Integer displayOrder;
}
