package com.ondemandmonitoring.flightarea.client;

import com.ondemandmonitoring.flightarea.config.FlightAreaProperties;
import java.net.SocketTimeoutException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** All provider HTTP exchanges are local mocks, including timeout/error cases. */
class MinistryFlightBanClientTest {
    private MockRestServiceServer server;
    private MinistryFlightBanClient client;

    @BeforeEach void setUp() {
        var properties = new FlightAreaProperties();
        properties.getMinistryFlightBan().setBaseUrl("http://ministry.test");
        var rest = new RestTemplate();
        server = MockRestServiceServer.bindTo(rest).build();
        client = new MinistryFlightBanClient(rest, properties);
    }

    @Test void postsClosedGeoJsonPolygonAndMapsOfficialResult() {
        server.expect(requestTo("http://ministry.test/api/calculate-area"))
                .andExpect(method(HttpMethod.POST)).andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.feature.type").value("Feature"))
                .andExpect(jsonPath("$.feature.geometry.type").value("Polygon"))
                .andExpect(jsonPath("$.feature.geometry.coordinates[0].length()").value(65))
                .andExpect(request -> {
                    var body = tools.jackson.databind.json.JsonMapper.builder().build().readTree(
                            ((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString());
                    var ring = body.path("feature").path("geometry").path("coordinates").get(0);
                    assertEquals(ring.get(0), ring.get(64));
                    assertEquals(106.7, ring.get(0).get(0).asDouble(), 0.00001);
                    assertTrue(ring.get(0).get(1).asDouble() > 10.6);
                })
                .andRespond(withSuccess("{\"totalArea\":100,\"violatedArea\":20,\"safeArea\":80,\"violatedZones\":[\"Zone A\"]}", MediaType.APPLICATION_JSON));
        var result = client.check(10.6, 106.7, 300);
        assertTrue(result.available()); assertEquals(100.0, result.totalAreaSquareMeters());
        assertEquals(20.0, result.violatedAreaSquareMeters()); assertEquals(80.0, result.safeAreaSquareMeters());
        assertEquals(List.of("Zone A"), result.violatedZones()); assertNull(result.errorCode());
        assertEquals(MinistryFlightBanClient.SOURCE_URL, result.sourceUrl()); server.verify();
    }

    @Test void timeoutIsUnavailableAndDoesNotInventClearance() {
        server.expect(anything()).andRespond(withException(new SocketTimeoutException("test timeout")));
        assertUnavailable(client.check(10.6, 106.7, 300), "PROVIDER_UNAVAILABLE"); server.verify();
    }

    @Test void serverErrorIsUnavailable() {
        server.expect(anything()).andRespond(withServerError());
        assertUnavailable(client.check(10.6, 106.7, 300), "PROVIDER_UNAVAILABLE"); server.verify();
    }

    @Test void malformedJsonIsUnavailable() {
        server.expect(anything()).andRespond(withSuccess("not-json", MediaType.APPLICATION_JSON));
        assertUnavailable(client.check(10.6, 106.7, 300), "PROVIDER_UNAVAILABLE"); server.verify();
    }

    @Test void emptyBodyIsUnavailable() {
        server.expect(anything()).andRespond(withSuccess("", MediaType.APPLICATION_JSON));
        assertUnavailable(client.check(10.6, 106.7, 300), "EMPTY_RESPONSE"); server.verify();
    }

    private void assertUnavailable(com.ondemandmonitoring.flightarea.dto.FlightAreaAssessmentResponse.MinistryFlightBan result,
                                   String code) {
        assertFalse(result.available()); assertNull(result.totalAreaSquareMeters());
        assertNull(result.safeAreaSquareMeters()); assertNull(result.violatedAreaSquareMeters());
        assertTrue(result.violatedZones().isEmpty()); assertEquals(code, result.errorCode());
    }
}
