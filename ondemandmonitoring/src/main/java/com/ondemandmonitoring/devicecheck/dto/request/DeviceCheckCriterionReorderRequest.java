package com.ondemandmonitoring.devicecheck.dto.request;

import com.ondemandmonitoring.devicecheck.enums.DeviceCheckPhase;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record DeviceCheckCriterionReorderRequest(@NotNull DeviceCheckPhase phase, @NotEmpty List<String> ids) {
}
