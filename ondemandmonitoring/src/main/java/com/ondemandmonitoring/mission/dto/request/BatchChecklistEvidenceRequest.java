package com.ondemandmonitoring.mission.dto.request;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
public record BatchChecklistEvidenceRequest(@NotBlank String mediaId,
        @NotEmpty @Size(max=100) List<@NotNull @Valid Target> targets, @Size(max=1000) String note) {
    public record Target(@NotBlank String executionId, @NotNull @Min(0) Long expectedVersion) {}
}
