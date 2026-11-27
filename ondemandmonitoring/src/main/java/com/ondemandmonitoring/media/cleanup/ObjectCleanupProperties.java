package com.ondemandmonitoring.media.cleanup;

import jakarta.validation.constraints.*;

import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@ConfigurationProperties(prefix = "app.storage-cleanup")
@Validated
@Getter
@Setter
public class ObjectCleanupProperties {
    private boolean enabled = true;

    @Min(1)
    @Max(100)
    private int batchSize = 20;

    @Min(1)
    @Max(100)
    private int maxAttempts = 8;

    @Min(5)
    @Max(86400)
    private int leaseSeconds = 120;

    @Min(1)
    @Max(86400)
    private int retryBaseSeconds = 30;

    @Min(1)
    @Max(604800)
    private int retryMaxSeconds = 3600;

    @Min(60)
    @Max(604800)
    private int uploadGraceSeconds = 3600;

    @Min(1)
    @Max(3650)
    private int retentionDays = 30;
}
