package com.ondemandmonitoring.flightarea.service;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.flightarea.client.MinistryFlightBanClient;
import com.ondemandmonitoring.flightarea.config.FlightAreaProperties;
import com.ondemandmonitoring.flightarea.dto.FlightAreaAssessmentResponse.MinistryFlightBan;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FlightAreaAssessmentServiceTest {
    private final MinistryFlightBanClient client = mock(MinistryFlightBanClient.class);
    private final FlightAreaProperties properties = new FlightAreaProperties();
    private final FlightAreaAssessmentService service = new FlightAreaAssessmentService(client, properties);

    @Test void parsesWhitespaceAndPassesThroughProviderResult() {
        var ban = new MinistryFlightBan(true, 100.0, 20.0, 80.0, List.of("Restricted"), "source", null);
        when(client.check(10.6, 106.7, 300)).thenReturn(ban);
        var response = service.assess(" 10.6 ", "106.7", "300", "legacy-value");
        assertEquals(10.6, response.location().latitude()); assertEquals(106.7, response.location().longitude());
        assertEquals(300, response.location().radiusMeters()); assertSame(ban, response.ministryFlightBan());
        verify(client).check(10.6, 106.7, 300);
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"NaN", "Infinity", "north", "91", "-91"})
    void invalidLatitudeNeverCallsProvider(String value) {
        assertThrows(ApiException.class, () -> service.assess(value, "106.7", "300", null));
        verifyNoInteractions(client);
    }

    @ParameterizedTest @ValueSource(strings={"NaN", "Infinity", "181", "-181", "east"})
    void invalidLongitudeNeverCallsProvider(String value) {
        assertThrows(ApiException.class, () -> service.assess("10.6", value, "300", null));
        verifyNoInteractions(client);
    }

    @ParameterizedTest @ValueSource(strings={"0", "-1", "5001", "NaN", "Infinity", "wide"})
    void invalidRadiusNeverCallsProvider(String value) {
        assertThrows(ApiException.class, () -> service.assess("10.6", "106.7", value, null));
        verifyNoInteractions(client);
    }

    @Test void honorsConfiguredRadiusLimit() {
        properties.setMaxRadiusM(100);
        service.assess("90", "180", "100", null);
        verify(client).check(90, 180, 100);
        assertThrows(ApiException.class, () -> service.assess("10", "106", "101", null));
        verifyNoMoreInteractions(client);
    }
}
