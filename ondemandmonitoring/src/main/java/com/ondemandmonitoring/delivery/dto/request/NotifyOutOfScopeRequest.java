package com.ondemandmonitoring.delivery.dto.request;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record NotifyOutOfScopeRequest(@NotNull Long expectedVersion, @NotNull UUID clientRequestId) {}
