package com.ondemandmonitoring.weather.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.common.exception.GlobalExceptionHandler;
import com.ondemandmonitoring.weather.dto.WeatherForecastResponse;
import com.ondemandmonitoring.weather.dto.WeatherForecastStatus;
import com.ondemandmonitoring.weather.dto.WeatherSuitability;
import com.ondemandmonitoring.weather.service.WeatherForecastService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WeatherForecastControllerTest {

    private WeatherForecastService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(WeatherForecastService.class);
        mvc = MockMvcBuilders.standaloneSetup(new WeatherForecastController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void returnsTheEnvelopeWithTheForecast() throws Exception {
        when(service.forecast("10.76", "106.68", "2026-10-04", "08:00")).thenReturn(new WeatherForecastResponse(
                WeatherForecastStatus.AVAILABLE, 10.76, 106.68, "2026-10-04T08:00",
                30.0, 70, 10, 0.0, 8.0, 12.0, 20, 24000.0, 0, "Trời quang",
                WeatherSuitability.GOOD, List.of()));

        mvc.perform(get("/api/weather/forecast")
                        .param("latitude", "10.76").param("longitude", "106.68")
                        .param("date", "2026-10-04").param("time", "08:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.temperatureC").value(30.0))
                .andExpect(jsonPath("$.data.suitability").value("GOOD"))
                .andExpect(jsonPath("$.data.weatherLabel").value("Trời quang"));
    }

    @Test
    void forecastNotAvailableIsACleanOkResponseNotAnError() throws Exception {
        when(service.forecast("10.76", "106.68", "2026-12-31", "08:00"))
                .thenReturn(WeatherForecastResponse.notAvailable(10.76, 106.68, "2026-12-31T08:00"));

        mvc.perform(get("/api/weather/forecast")
                        .param("latitude", "10.76").param("longitude", "106.68")
                        .param("date", "2026-12-31").param("time", "08:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FORECAST_NOT_AVAILABLE"));
    }

    @Test
    void invalidLatitudeReturns400() throws Exception {
        when(service.forecast("95", "106.68", "2026-10-04", "08:00"))
                .thenThrow(new ApiException(ErrorCode.INVALID_REQUEST, "latitude is out of range"));

        mvc.perform(get("/api/weather/forecast")
                        .param("latitude", "95").param("longitude", "106.68")
                        .param("date", "2026-10-04").param("time", "08:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void invalidLongitudeReturns400() throws Exception {
        when(service.forecast("10.76", "200", "2026-10-04", "08:00"))
                .thenThrow(new ApiException(ErrorCode.INVALID_REQUEST, "longitude is out of range"));

        mvc.perform(get("/api/weather/forecast")
                        .param("latitude", "10.76").param("longitude", "200")
                        .param("date", "2026-10-04").param("time", "08:00"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingParameterReturns400() throws Exception {
        mvc.perform(get("/api/weather/forecast").param("latitude", "10.76"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void providerFailureIsAControlled502NotAnUnhandled500() throws Exception {
        when(service.forecast("10.76", "106.68", "2026-10-04", "08:00"))
                .thenThrow(new ApiException(ErrorCode.WEATHER_PROVIDER_UNAVAILABLE));

        mvc.perform(get("/api/weather/forecast")
                        .param("latitude", "10.76").param("longitude", "106.68")
                        .param("date", "2026-10-04").param("time", "08:00"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("WEATHER_PROVIDER_UNAVAILABLE"));
    }
}
