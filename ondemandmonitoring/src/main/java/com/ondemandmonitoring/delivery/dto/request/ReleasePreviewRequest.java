package com.ondemandmonitoring.delivery.dto.request;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/** Request used by a manager to select the approved media exposed as previews. */
public record ReleasePreviewRequest(@NotEmpty List<String> mediaAssetIds, String notes) {}
