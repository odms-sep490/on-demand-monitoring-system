package com.ondemandmonitoring.media.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record RemoveMediaRequest(@NotNull @PositiveOrZero Long expectedVersion,
                                 @NotBlank @Size(max = 500) String reason) {}
