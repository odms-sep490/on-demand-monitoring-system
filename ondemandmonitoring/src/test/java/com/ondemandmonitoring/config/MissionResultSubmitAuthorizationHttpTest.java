package com.ondemandmonitoring.config;

import com.ondemandmonitoring.common.exception.GlobalExceptionHandler;
import com.ondemandmonitoring.mission.controller.MissionAccessController;
import com.ondemandmonitoring.mission.controller.MissionResultController;
import com.ondemandmonitoring.mission.service.*;
import com.ondemandmonitoring.mission.service.impl.MissionResultService;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.ondemandmonitoring.mission.service.MissionResultSubmissionFixture.Scenario;

/** Security filters, real controller, canonical actor policy and real submit service; test JWT only. */
@SpringJUnitConfig(classes={SecurityConfig.class,ActiveAccountFilter.class,GlobalExceptionHandler.class,
        MissionAccessController.class,MissionResultController.class,MissionResultSubmitAuthorizationHttpTest.Beans.class})
@WebAppConfiguration
class MissionResultSubmitAuthorizationHttpTest {
    @Autowired WebApplicationContext context;
    @Autowired MissionResultSubmissionFixture fixture;

    @ParameterizedTest @EnumSource(Scenario.class)
    void capabilityMatchesPostAndPutWithRealSubmitService(Scenario scenario) throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        for (String method : new String[]{"POST","PUT"}) {
            fixture.prepare(scenario);
            assertEquals(scenario.allowed, fixture.authorization.canSubmitMissionResult(fixture.targetId));
            var identity = jwt().jwt(j -> j.subject(fixture.actor.getId()))
                    .authorities(() -> "ROLE_" + fixture.actor.getRole().getCode().name());
            var capability = mvc.perform(MockMvcRequestBuilders.get("/api/missions/{id}/permissions",fixture.targetId).with(identity));
            if (fixture.authorization.canViewMission(fixture.targetId))
                capability.andExpect(status().isOk()).andExpect(jsonPath("$.data.canSubmitMissionResult").value(scenario.allowed));
            else capability.andExpect(status().isForbidden());
            var request = method.equals("POST")
                    ? MockMvcRequestBuilders.post("/api/missions/{id}/result",fixture.targetId)
                    : MockMvcRequestBuilders.put("/api/missions/{id}/result",fixture.targetId);
            var response = mvc.perform(request.with(identity).with(csrf()).contentType("application/json").content("{\"summary\":\"Published submission\"}"));
            if (scenario.allowed) {
                response.andExpect(status().is(method.equals("POST") ? 201 : 200))
                        .andExpect(jsonPath("$.data.missionId").value(fixture.targetId))
                        .andExpect(jsonPath("$.data.approvalStatus").value("APPROVED"))
                        .andExpect(jsonPath("$.data.inspectorConfirmed").value(true));
                verify(fixture.results).saveAndFlush(fixture.result);
            } else {
                response.andExpect(status().isForbidden());
                verify(fixture.results,never()).saveAndFlush(any());
            }
            if (scenario == Scenario.INSPECTOR)
                assertFalse(fixture.authorization.canUploadMissionMedia(fixture.targetId));
        }
    }

    @org.junit.jupiter.api.Test
    void oversizedSummaryReturnsBadRequestWithoutWritingTheResult() throws Exception {
        fixture.prepare(Scenario.INSPECTOR);
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(MockMvcRequestBuilders.post("/api/missions/{id}/result", fixture.targetId)
                .with(jwt().jwt(j -> j.subject(fixture.actor.getId())).authorities(() -> "ROLE_STAFF"))
                .with(csrf()).contentType("application/json")
                .content("{\"summary\":\"" + "x".repeat(1001) + "\"}"))
                .andExpect(status().isBadRequest());
        verify(fixture.results, never()).saveAndFlush(any());
    }

    @Configuration @EnableWebMvc
    static class Beans {
        @Bean com.ondemandmonitoring.mission.repository.MissionDeviceAssignmentRepository deviceAssignments() {
            return mock(com.ondemandmonitoring.mission.repository.MissionDeviceAssignmentRepository.class);
        }
        @Bean com.ondemandmonitoring.mission.repository.ControlHandoverRepository handovers() {
            return mock(com.ondemandmonitoring.mission.repository.ControlHandoverRepository.class);
        }
        @Bean com.ondemandmonitoring.weather.repository.MissionWeatherCheckRepository weatherChecks() {
            return mock(com.ondemandmonitoring.weather.repository.MissionWeatherCheckRepository.class);
        }

        @Bean com.ondemandmonitoring.delivery.service.IDeliveryWorkflowService deliveryWorkflow() {
            return org.mockito.Mockito.mock(com.ondemandmonitoring.delivery.service.IDeliveryWorkflowService.class);
        }
        @Bean MissionResultSubmissionFixture fixture() { return new MissionResultSubmissionFixture(); }
        @Bean AuthenticatedUserResolver resolver(MissionResultSubmissionFixture fixture) { return fixture.resolver; }
        @Bean("missionAuthorizationService") IMissionAuthorizationService authorization(MissionResultSubmissionFixture fixture) { return fixture.authorization; }
        @Bean MissionResultService resultService(MissionResultSubmissionFixture fixture) { return fixture.service; }
        @Bean JwtDecoder cognitoAccessTokenDecoder() { return mock(JwtDecoder.class); }
    }
}
