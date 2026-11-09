package com.ondemandmonitoring.flightarea.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "flight-area-assessment")
public class FlightAreaProperties {

    private double maxRadiusM = 5000;
    private MinistryFlightBan ministryFlightBan = new MinistryFlightBan();

    @Data
    public static class MinistryFlightBan {
        private String baseUrl = "https://cambay.mod.gov.vn";
        private String path = "/api/calculate-area";
        private int connectTimeoutMs = 3000;
        private int readTimeoutMs = 8000;
        private int polygonPoints = 64;
    }

}
