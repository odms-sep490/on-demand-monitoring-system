package com.ondemandmonitoring.delivery.dto.response;

import java.time.Instant;
import com.ondemandmonitoring.media.enums.MediaDerivativeStatus;

/** A media asset exposed through a short-lived delivery URL. */
public record DeliveryAssetResponse(String id, String type, String fileName, Long size,
                                    String url, Instant urlExpiresAt, boolean downloadable,
                                    String thumbnailUrl, String previewUrl, MediaDerivativeStatus previewStatus) {}
