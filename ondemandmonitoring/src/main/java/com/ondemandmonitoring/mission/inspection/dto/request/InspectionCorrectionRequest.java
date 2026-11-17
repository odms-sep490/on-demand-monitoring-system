package com.ondemandmonitoring.mission.inspection.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;

public record InspectionCorrectionRequest(
    @NotNull UUID clientRequestId,
    @NotNull com.ondemandmonitoring.mission.inspection.enums.InspectionCorrectionMode mode, @NotEmpty @Size(max = 100) List<@NotNull @Valid Item> items) {
  public record Item(
      @NotBlank String executionId,
      @NotNull @PositiveOrZero Long expectedVersion,
      @NotBlank @Size(max = 2000) String reason,
      @NotNull @Size(max = 100) List<@NotBlank String> mediaIds) {}
}
