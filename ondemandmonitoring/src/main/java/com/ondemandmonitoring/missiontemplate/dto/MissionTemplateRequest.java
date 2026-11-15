package com.ondemandmonitoring.missiontemplate.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record MissionTemplateRequest(
        @NotBlank @Size(max = 255) String scopeLabel,
        @NotNull List<@NotBlank String> checklistIds) {}
