package com.ondemandmonitoring.flightarea.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ondemandmonitoring.common.exception.GlobalExceptionHandler;
import com.ondemandmonitoring.flightarea.client.MinistryFlightBanClient;
import com.ondemandmonitoring.flightarea.config.FlightAreaProperties;
import com.ondemandmonitoring.flightarea.dto.FlightAreaAssessmentResponse.MinistryFlightBan;
import com.ondemandmonitoring.flightarea.service.FlightAreaAssessmentService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Controller contract tests for the current Ministry flight-ban-only assessment. */
class FlightAreaAssessmentControllerTest {

    private MinistryFlightBanClient ministry;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ministry = mock(MinistryFlightBanClient.class);
        FlightAreaProperties properties = new FlightAreaProperties();
        FlightAreaAssessmentService service = new FlightAreaAssessmentService(ministry, properties);
        mvc = MockMvcBuilders.standaloneSetup(new FlightAreaAssessmentController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        when(ministry.check(10.6398245, 106.7172615, 300.0))
                .thenReturn(new MinistryFlightBan(
                        true, 282743.34, 0.0, 282743.34, List.of(),
                        MinistryFlightBanClient.SOURCE_URL, null));
    }

    private ResultActions call(String latitude, String longitude, String radius, String altitude)
            throws Exception {
        var request = get("/api/flight-area-assessment")
                .param("latitude", latitude)
                .param("longitude", longitude)
                .param("radiusMeters", radius);
        if (altitude != null) request.param("requestedAltitudeAgl", altitude);
        return mvc.perform(request);
    }

    @Test
    void returnsEnvelopeWithLocationAndMinistryAssessment() throws Exception {
        call("10.6398245", "106.7172615", "300", "60")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Official restricted-zone check completed"))
                .andExpect(jsonPath("$.data.location.latitude").value(10.6398245))
                .andExpect(jsonPath("$.data.location.longitude").value(106.7172615))
                .andExpect(jsonPath("$.data.location.radiusMeters").value(300.0))
                .andExpect(jsonPath("$.data.ministryFlightBan.available").value(true))
                .andExpect(jsonPath("$.data.ministryFlightBan.totalAreaSquareMeters").value(282743.34))
                .andExpect(jsonPath("$.data.ministryFlightBan.violatedAreaSquareMeters").value(0.0))
                .andExpect(jsonPath("$.data.ministryFlightBan.safeAreaSquareMeters").value(282743.34))
                .andExpect(jsonPath("$.data.ministryFlightBan.sourceUrl")
                        .value(MinistryFlightBanClient.SOURCE_URL));

        verify(ministry).check(10.6398245, 106.7172615, 300.0);
    }

    @Test
    void legacyAltitudeParameterIsOptionalAndIgnored() throws Exception {
        call("10.6398245", "106.7172615", "300", null).andExpect(status().isOk());
        call("10.6398245", "106.7172615", "300", "not-a-number").andExpect(status().isOk());

        verify(ministry, org.mockito.Mockito.times(2)).check(10.6398245, 106.7172615, 300.0);
    }

    @Test
    void includeOsmCompatibilityParameterDoesNotChangeCurrentContract() throws Exception {
        mvc.perform(get("/api/flight-area-assessment")
                        .param("latitude", "10.6398245")
                        .param("longitude", "106.7172615")
                        .param("radiusMeters", "300")
                        .param("includeOsm", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ministryFlightBan.available").value(true))
                .andExpect(jsonPath("$.data.osmContext").doesNotExist());
    }

    @Test
    void returnsUnavailableMinistryResultWithoutFailingTheRequest() throws Exception {
        when(ministry.check(10.6398245, 106.7172615, 300.0))
                .thenReturn(new MinistryFlightBan(
                        false, null, null, null, List.of(),
                        MinistryFlightBanClient.SOURCE_URL, "PROVIDER_UNAVAILABLE"));

        call("10.6398245", "106.7172615", "300", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ministryFlightBan.available").value(false))
                .andExpect(jsonPath("$.data.ministryFlightBan.errorCode").value("PROVIDER_UNAVAILABLE"))
                .andExpect(jsonPath("$.data.ministryFlightBan.totalAreaSquareMeters").doesNotExist());
    }

    @Test
    void rejectsInvalidLatitudeWithoutCallingProvider() throws Exception {
        call("91", "106.7", "300", null).andExpect(status().isBadRequest());
        call("-90.5", "106.7", "300", null).andExpect(status().isBadRequest());
        call("north", "106.7", "300", null).andExpect(status().isBadRequest());
        call("NaN", "106.7", "300", null).andExpect(status().isBadRequest());

        verifyNoInteractions(ministry);
    }

    @Test
    void rejectsInvalidLongitudeWithoutCallingProvider() throws Exception {
        call("10.6", "181", "300", null).andExpect(status().isBadRequest());
        call("10.6", "-181", "300", null).andExpect(status().isBadRequest());
        call("10.6", "", "300", null).andExpect(status().isBadRequest());

        verifyNoInteractions(ministry);
    }

    @Test
    void rejectsInvalidRadiusWithoutCallingProvider() throws Exception {
        call("10.6", "106.7", "0", null).andExpect(status().isBadRequest());
        call("10.6", "106.7", "-5", null).andExpect(status().isBadRequest());
        call("10.6", "106.7", "5000.5", null).andExpect(status().isBadRequest());
        call("10.6", "106.7", "wide", null).andExpect(status().isBadRequest());

        verifyNoInteractions(ministry);
    }

    @Test
    void missingRequiredRadiusIsBadRequest() throws Exception {
        mvc.perform(get("/api/flight-area-assessment")
                        .param("latitude", "10.6")
                        .param("longitude", "106.7"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(ministry);
    }
}
