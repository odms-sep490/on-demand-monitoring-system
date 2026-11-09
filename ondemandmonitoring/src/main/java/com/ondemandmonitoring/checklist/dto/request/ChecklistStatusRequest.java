package com.ondemandmonitoring.checklist.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
public class ChecklistStatusRequest {
    @NotNull
    private Boolean active;
}
