package com.ondemandmonitoring.mission.dto.request;

import com.ondemandmonitoring.mission.enums.ChecklistExecutionStatus;
import com.ondemandmonitoring.mission.enums.ChecklistAssessmentStatus;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class ChecklistExecutionUpdateRequest {
    @NotNull @Min(0)
    private Long expectedVersion;
    @NotNull
    private ChecklistExecutionStatus executionStatus;
    @NotNull
    private ChecklistAssessmentStatus assessmentStatus;
    @Size(max = 2000)
    private String assessmentNote;
    @Size(max = 2000)
    private String observation;
    @Size(max = 1000)
    private String unableToVerifyReason;
}
