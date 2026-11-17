package com.ondemandmonitoring.ai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "ai.openrouter")
public class OpenRouterProperties {
    private String baseUrl = "https://openrouter.ai/api/v1";
    private String apiKey = "";
    private String model = "openrouter/free";
    private int connectTimeoutMs = 5000;
    private int readTimeoutMs = 60000;
    private int maxTokens = 1200;
}
