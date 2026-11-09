package com.ondemandmonitoring.mission.dto.request;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.List;

@Getter
@Setter
public class MissionCreateRequest {
    @NotBlank
    @Size(max = 255)
    private String scopeLabel;

    @JsonAlias("scheduledStart")
    @NotNull(message = "scheduledStartAt is required")
    private Instant scheduledStartAt;

    @JsonAlias("scheduledEnd")
    @NotNull(message = "scheduledEndAt is required")
    private Instant scheduledEndAt;

    @NotEmpty(message = "checklistIds must contain at least one checklist item")
    @Size(max = 1000)
    private List<@NotBlank String> checklistIds;
}
