package com.ondemandmonitoring.media.infrastructure.s3;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "odms.result-retention.cleanup")
public class ResultRetentionProperties {
    private boolean enabled = true;
    private int batchSize = 100;
}
