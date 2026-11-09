package com.ondemandmonitoring.mission.dto.request;
import jakarta.validation.constraints.*;
public record AttachChecklistEvidenceRequest(@NotNull @Min(0) Long expectedVersion, @Size(max=1000) String note) {}
