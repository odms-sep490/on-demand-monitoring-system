package com.ondemandmonitoring.weather.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.weather.client.OpenMeteoClient;
import com.ondemandmonitoring.weather.config.WeatherProperties;
import com.ondemandmonitoring.weather.dto.WeatherForecastResponse;
import com.ondemandmonitoring.weather.dto.WeatherForecastStatus;
import com.ondemandmonitoring.weather.dto.WeatherSuitability;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.web.client.RestTemplate;

/** Uses a mocked HTTP server: the real Open-Meteo is never called. */
class WeatherForecastServiceTest {

    private static final String LAT = "10.762620";
    private static final String LON = "106.680712";
    private static final String DATE = "2026-10-04";

    private MockRestServiceServer server;
    private WeatherForecastService service;
    private WeatherProperties properties;

    @BeforeEach
    void setUp() {
        properties = new WeatherProperties();
        properties.getOpenMeteo().setBaseUrl("http://open-meteo.test");
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        Clock clock = Clock.fixed(Instant.parse("2026-10-03T03:00:00Z"), ZoneOffset.UTC);
        service = new WeatherForecastService(
                new OpenMeteoClient(restTemplate, properties),
                new CustomerWeatherSuitabilityEvaluator(properties),
                properties,
                clock);
    }

    /**
     * 24 local-time hours where every value encodes its hour so a wrong-hour match is obvious:
     * temperature = 20 + hour, rain probability = hour, wind = 5 + hour / 2.
     */
    private static String dayBody(String date) {
        return dayBody(date, h -> h);
    }

    private static String dayBody(String date, IntUnaryOperator rainProbabilityByHour) {
        List<String> times = new ArrayList<>();
        List<String> temp = new ArrayList<>();
        List<String> humidity = new ArrayList<>();
        List<String> rainProb = new ArrayList<>();
        List<String> rain = new ArrayList<>();
        List<String> code = new ArrayList<>();
        List<String> cloud = new ArrayList<>();
        List<String> visibility = new ArrayList<>();
        List<String> wind = new ArrayList<>();
        List<String> gust = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            times.add("\"%sT%02d:00\"".formatted(date, h));
            temp.add(String.valueOf(20 + h));
            humidity.add("70");
            rainProb.add(String.valueOf(rainProbabilityByHour.applyAsInt(h)));
            rain.add("0.0");
            code.add("1");
            cloud.add("30");
            visibility.add("24000");
            wind.add(String.valueOf(5 + h / 2.0));
            gust.add(String.valueOf(10 + h / 2.0));
        }
        return """
                {"timezone":"Asia/Bangkok","utc_offset_seconds":25200,"hourly":{
                "time":[%s],"temperature_2m":[%s],"relative_humidity_2m":[%s],
                "precipitation_probability":[%s],"precipitation":[%s],"weather_code":[%s],
                "cloud_cover":[%s],"visibility":[%s],"wind_speed_10m":[%s],"wind_gusts_10m":[%s]}}
                """.formatted(
                String.join(",", times), String.join(",", temp), String.join(",", humidity),
                String.join(",", rainProb), String.join(",", rain), String.join(",", code),
                String.join(",", cloud), String.join(",", visibility), String.join(",", wind),
                String.join(",", gust));
    }

    private void expectForecastCall(String date, org.springframework.test.web.client.ResponseCreator response) {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://open-meteo.test/v1/forecast")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("start_date", date))
                .andExpect(queryParam("end_date", date))
                .andExpect(queryParam("timezone", "auto"))
                .andExpect(queryParam("wind_speed_unit", "kmh"))
                .andRespond(response);
    }

    @Test
    void validRequestReturnsTheSelectedHourAsACleanDto() {
        expectForecastCall(DATE, withSuccess(dayBody(DATE), MediaType.APPLICATION_JSON));

        WeatherForecastResponse response = service.forecast(LAT, LON, DATE, "08:00");

        assertEquals(WeatherForecastStatus.AVAILABLE, response.status());
        assertEquals("2026-10-04T08:00", response.forecastTime());
        assertEquals(28.0, response.temperatureC());
        assertEquals(8, response.precipitationProbabilityPercent());
        assertEquals(9.0, response.windSpeedKmh());
        assertEquals(70, response.relativeHumidityPercent());
        assertEquals(1, response.weatherCode());
        assertEquals("Ít mây", response.weatherLabel());
        assertEquals(WeatherSuitability.GOOD, response.suitability());
        assertEquals(10.762620, response.latitude());
        assertEquals(106.680712, response.longitude());
        server.verify();
    }

    @Test
    void localTimeIsMatchedAsGivenAndNeverShiftedToUtc() {
        // Provider returns the location's LOCAL hours (timezone=auto). 08:00 requested in
        // Ho Chi Minh must select the 08:00 row, not 01:00 (UTC) and not 15:00.
        expectForecastCall(DATE, withSuccess(dayBody(DATE), MediaType.APPLICATION_JSON));

        WeatherForecastResponse response = service.forecast(LAT, LON, DATE, "08:00");

        assertEquals("2026-10-04T08:00", response.forecastTime());
        assertEquals(28.0, response.temperatureC());
    }

    @Test
    void minutesAreFlooredToTheHour() {
        expectForecastCall(DATE, withSuccess(dayBody(DATE), MediaType.APPLICATION_JSON));

        WeatherForecastResponse response = service.forecast(LAT, LON, DATE, "15:45");

        assertEquals("2026-10-04T15:00", response.forecastTime());
        assertEquals(35.0, response.temperatureC());
    }

    @Test
    void suitabilityIsDerivedFromTheSelectedHourOnly() {
        String body = dayBody(DATE, h -> h == 15 ? 55 : h == 19 ? 85 : 5);
        for (int i = 0; i < 3; i++) {
            expectForecastCall(DATE, withSuccess(body, MediaType.APPLICATION_JSON));
        }

        assertEquals(WeatherSuitability.GOOD, service.forecast(LAT, LON, DATE, "08:00").suitability());
        WeatherForecastResponse caution = service.forecast(LAT, LON, DATE, "15:00");
        assertEquals(WeatherSuitability.CAUTION, caution.suitability());
        assertEquals(List.of("RAIN"), caution.warnings());
        WeatherForecastResponse poor = service.forecast(LAT, LON, DATE, "19:00");
        assertEquals(WeatherSuitability.POOR, poor.suitability());
        server.verify();
    }

    @Test
    void invalidLatitudeIsRejectedBeforeCallingTheProvider() {
        ApiException ex = assertThrows(ApiException.class, () -> service.forecast("91", LON, DATE, "08:00"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        ex = assertThrows(ApiException.class, () -> service.forecast("abc", LON, DATE, "08:00"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        ex = assertThrows(ApiException.class, () -> service.forecast("NaN", LON, DATE, "08:00"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        server.verify();
    }

    @Test
    void invalidLongitudeIsRejected() {
        ApiException ex = assertThrows(ApiException.class, () -> service.forecast(LAT, "181", DATE, "08:00"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        ex = assertThrows(ApiException.class, () -> service.forecast(LAT, "-180.5", DATE, "08:00"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    void malformedDateOrTimeIsRejected() {
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ApiException.class, () -> service.forecast(LAT, LON, "04/10/2026", "08:00")).getStatus());
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ApiException.class, () -> service.forecast(LAT, LON, DATE, "morning")).getStatus());
    }

    @Test
    void pastDateIsRejectedWithoutCallingTheProvider() {
        ApiException ex = assertThrows(ApiException.class, () -> service.forecast(LAT, LON, "2026-09-20", "08:00"));
        assertEquals(ErrorCode.INVALID_REQUEST, ex.getErrorCode());
        server.verify();
    }

    @Test
    void providerServerErrorBecomesAControlledBadGateway() {
        expectForecastCall(DATE, withServerError());

        ApiException ex = assertThrows(ApiException.class, () -> service.forecast(LAT, LON, DATE, "08:00"));

        assertEquals(ErrorCode.WEATHER_PROVIDER_UNAVAILABLE, ex.getErrorCode());
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatus());
    }

    @Test
    void providerTimeoutBecomesAControlledBadGateway() {
        expectForecastCall(DATE, request -> {
            throw new IOException("Read timed out");
        });

        ApiException ex = assertThrows(ApiException.class, () -> service.forecast(LAT, LON, DATE, "08:00"));

        assertEquals(ErrorCode.WEATHER_PROVIDER_UNAVAILABLE, ex.getErrorCode());
    }

    @Test
    void garbageProviderBodyBecomesAControlledBadGateway() {
        expectForecastCall(DATE, withSuccess("not json", MediaType.APPLICATION_JSON));

        ApiException ex = assertThrows(ApiException.class, () -> service.forecast(LAT, LON, DATE, "08:00"));

        assertEquals(ErrorCode.WEATHER_PROVIDER_UNAVAILABLE, ex.getErrorCode());
    }

    @Test
    void dateBeyondForecastRangeReturnsForecastNotAvailableWithoutCallingTheProvider() {
        // clock is 2026-10-03; max 16 days -> 2026-10-19 is the last accepted day
        WeatherForecastResponse response = service.forecast(LAT, LON, "2026-11-30", "08:00");

        assertEquals(WeatherForecastStatus.FORECAST_NOT_AVAILABLE, response.status());
        assertNull(response.temperatureC());
        assertNull(response.suitability());
        assertEquals(List.of(), response.warnings());
        server.verify();
    }

    @Test
    void providerRejectingTheDateRangeAlsoReturnsForecastNotAvailable() {
        expectForecastCall("2026-10-18", withBadRequest());

        WeatherForecastResponse response = service.forecast(LAT, LON, "2026-10-18", "08:00");

        assertEquals(WeatherForecastStatus.FORECAST_NOT_AVAILABLE, response.status());
    }

    @Test
    void hourMissingFromTheProviderPayloadReturnsForecastNotAvailable() {
        expectForecastCall(DATE, withSuccess("{\"hourly\":{\"time\":[]}}", MediaType.APPLICATION_JSON));

        WeatherForecastResponse response = service.forecast(LAT, LON, DATE, "08:00");

        assertEquals(WeatherForecastStatus.FORECAST_NOT_AVAILABLE, response.status());
    }

    @Test
    void identicalRequestsAreServedFromTheCache() {
        server.expect(ExpectedCount.once(), requestTo(org.hamcrest.Matchers.startsWith("http://open-meteo.test/v1/forecast")))
                .andRespond(withSuccess(dayBody(DATE), MediaType.APPLICATION_JSON));

        WeatherForecastResponse first = service.forecast(LAT, LON, DATE, "08:00");
        // coordinates a few metres away round to the same cache key
        WeatherForecastResponse second = service.forecast("10.762621", "106.680713", DATE, "08:30");

        assertEquals(first.temperatureC(), second.temperatureC());
        server.verify(); // would fail on a second provider call (only one expected)
    }

    @Test
    void failuresAreNotCached() {
        expectForecastCall(DATE, withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        expectForecastCall(DATE, withSuccess(dayBody(DATE), MediaType.APPLICATION_JSON));
        assertThrows(ApiException.class, () -> service.forecast(LAT, LON, DATE, "08:00"));
        assertEquals(WeatherForecastStatus.AVAILABLE, service.forecast(LAT, LON, DATE, "08:00").status());
        server.verify();
    }
}
