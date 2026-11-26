package com.ondemandmonitoring.inspectionreport;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public record ReportDocument(
        @NotBlank @Size(max=200) String reportTitle,
        @NotBlank @Size(max=3000) String executiveSummary,
        @NotBlank @Size(max=3000) String inspectionScope,
        @NotBlank @Size(max=3000) String methodology,
        @NotEmpty @Size(max=20) List<@NotNull @Valid Section> sections,
        @NotNull @Size(max=20) List<@NotNull @Size(max=1000) String> limitations,
        @NotBlank @Size(max=3000) String conclusion,
        @NotNull @Size(max=20) List<@NotNull @Size(max=1000) String> recommendations) {
    public record Section(@NotBlank @Size(max=80) String sectionId,
            @NotBlank @Size(max=200) String title, @NotBlank @Size(max=6000) String content,
            @NotNull @Size(max=12) List<@NotNull @Valid Image> images,
            @NotNull @Size(max=40) List<String> sourceFindingIds,
            @NotNull @Size(max=40) List<String> sourceMediaIds,
            java.util.Map<String,Object> richText) {}
    public record Image(@NotBlank String mediaId, @NotBlank @Pattern(regexp="OVERVIEW|EVIDENCE|SUPPORTING") String role,
            @NotBlank @Size(max=1000) String caption) {}
}
