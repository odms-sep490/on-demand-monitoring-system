package com.ondemandmonitoring.ai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "ai.checklist-vision")
public class ChecklistVisionProperties {
    public enum Provider { OPENROUTER, OLLAMA }

    private boolean enabled;
    private Provider provider = Provider.OPENROUTER;
    private String model;
    private String baseUrl;
    private String apiKey;
}
