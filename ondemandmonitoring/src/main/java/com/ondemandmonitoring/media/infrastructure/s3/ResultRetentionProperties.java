package com.ondemandmonitoring.media.infrastructure.s3;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@org.springframework.validation.annotation.Validated
@ConfigurationProperties(prefix = "odms.result-retention.cleanup")
public class ResultRetentionProperties {
    private boolean enabled = true;
    private int batchSize = 100;
    @jakarta.validation.constraints.Min(0)
    @jakarta.validation.constraints.Max(365)
    private int revisionGraceDays = 14;
}
