package com.ondemandmonitoring.mission.inspection;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ondemandmonitoring.mission.inspection.controller.InspectionCorrectionController;
import com.ondemandmonitoring.mission.inspection.service.*;
import com.ondemandmonitoring.mission.service.IMissionAuthorizationService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@SpringJUnitConfig(InspectionCorrectionControllerTest.Config.class)
@WebAppConfiguration
class InspectionCorrectionControllerTest {
  @Autowired WebApplicationContext context;
  @Autowired IInspectionCorrectionService service;
  @Autowired IInternalInspectionMissionService planning;
  @Autowired IMissionAuthorizationService authorization;
  MockMvc mvc;
  String decision = "{\"expectedVersion\":2,\"reviewToken\":\"token\",\"note\":\"Reviewed\"}";

  @BeforeEach
  void setup() {
    reset(service, planning, authorization);
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void inspectorRequestUsesExactMissionAndValidatedScope() throws Exception {
    when(authorization.canAssessMonitoringChecklist("m")).thenReturn(true);
    mvc.perform(
            post("/api/missions/m/inspection-corrections")
                .with(user("inspector").roles("STAFF"))
                .contentType("application/json")
                .content(
                    "{\"clientRequestId\":\"00000000-0000-0000-0000-000000000001\",\"items\":[{\"executionId\":\"e\",\"expectedVersion\":3,\"reason\":\"Blurred\",\"mediaIds\":[]}]}"))
        .andExpect(status().isOk());
    verify(service)
        .request(
            eq("m"),
            argThat(
                r ->
                    r.items().getFirst().executionId().equals("e")
                        && r.items().getFirst().expectedVersion() == 3));
  }

  @Test
  void unassignedStaffCannotRequestOrResolve() throws Exception {
    mvc.perform(
            post("/api/missions/m/inspection-corrections/c/resolve")
                .with(user("staff").roles("STAFF"))
                .contentType("application/json")
                .content(decision))
        .andExpect(status().isForbidden());
    verifyNoInteractions(service);
  }

  @Test
  void reporterCannotConfirmOwnCorrection() throws Exception {
    when(authorization.canAttachChecklistEvidence("m")).thenReturn(true);
    mvc.perform(
            post("/api/missions/m/inspection-corrections/c/resubmit")
                .with(user("pilot").roles("STAFF"))
                .contentType("application/json")
                .content(decision))
        .andExpect(status().isOk());
    verify(service)
        .resubmit(
            eq("m"),
            eq("c"),
            argThat(r -> r.expectedVersion() == 2 && r.reviewToken().equals("token")));
    mvc.perform(
            post("/api/missions/m/inspection-corrections/c/resolve")
                .with(user("pilot").roles("STAFF"))
                .contentType("application/json")
                .content(decision))
        .andExpect(status().isForbidden());
  }

  @Test
  void staffCannotCreateInternalMission() throws Exception {
    mvc.perform(
            post("/api/missions/m/inspection-corrections/c/missions")
                .with(user("staff").roles("STAFF"))
                .contentType("application/json")
                .content(
                    "{\"expectedVersion\":2,\"clientRequestId\":\"00000000-0000-0000-0000-000000000001\",\"scopeLabel\":\"Recapture\",\"scheduledStartAt\":\"2026-11-15T01:00:00Z\",\"scheduledEndAt\":\"2026-11-15T02:00:00Z\"}"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(planning);
  }

  @Test
  void invalidDecisionDoesNotReachService() throws Exception {
    when(authorization.canAssessMonitoringChecklist("m")).thenReturn(true);
    mvc.perform(
            post("/api/missions/m/inspection-corrections/c/resolve")
                .with(user("inspector").roles("STAFF"))
                .contentType("application/json")
                .content("{\"expectedVersion\":-1,\"reviewToken\":\"token\",\"note\":\"\"}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }

  @Test
  void customerCannotReadInternalRequestsAndAnonymousIsUnauthenticated() throws Exception {
    mvc.perform(
            get("/api/missions/m/inspection-corrections").with(user("customer").roles("CUSTOMER")))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/missions/m/inspection-corrections")).andExpect(status().isUnauthorized());
    verifyNoInteractions(service);
  }

  @Test
  void managerCreatesTheExplicitInternalMissionViaPost() throws Exception {
    mvc.perform(
            post("/api/missions/m/inspection-corrections/c/missions")
                .with(user("manager").roles("MANAGER"))
                .contentType("application/json")
                .content(
                    "{\"expectedVersion\":2,\"clientRequestId\":\"00000000-0000-0000-0000-000000000001\",\"scopeLabel\":\"Recapture\",\"scheduledStartAt\":\"2026-11-15T01:00:00Z\",\"scheduledEndAt\":\"2026-11-15T02:00:00Z\"}"))
        .andExpect(status().isOk());
    verify(planning)
        .create(
            eq("m"),
            eq("c"),
            argThat(r -> r.expectedVersion() == 2 && r.scopeLabel().equals("Recapture")));
  }

  @Test
  void managerCannotAcceptOrReturnInspectorCorrections() throws Exception {
    for (String action : new String[] {"resolve", "return"}) {
      mvc.perform(post("/api/missions/m/inspection-corrections/c/" + action)
              .with(user("manager").roles("MANAGER"))
              .contentType("application/json").content(decision))
          .andExpect(status().isForbidden());
    }
    verifyNoInteractions(service);
  }

  @Test
  void assignedInspectorAcceptsCorrectionsWithoutManagerApproval() throws Exception {
    when(authorization.canAssessMonitoringChecklist("m")).thenReturn(true);
    mvc.perform(post("/api/missions/m/inspection-corrections/c/resolve")
            .with(user("inspector").roles("STAFF"))
            .contentType("application/json").content(decision))
        .andExpect(status().isOk());
    verify(service).resolve(eq("m"), eq("c"), any());
    verifyNoInteractions(planning);
  }

  @Configuration
  @EnableWebMvc
  @EnableWebSecurity
  @EnableMethodSecurity
  static class Config {
    @Bean
    InspectionCorrectionController controller(
        IInspectionCorrectionService s, IInternalInspectionMissionService p) {
      return new InspectionCorrectionController(s, p);
    }

    @Bean
    IInspectionCorrectionService service() {
      return mock(IInspectionCorrectionService.class);
    }

    @Bean
    IInternalInspectionMissionService planning() {
      return mock(IInternalInspectionMissionService.class);
    }

    @Bean(name = "missionAuthorizationService")
    IMissionAuthorizationService authorization() {
      return mock(IMissionAuthorizationService.class);
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
      return http.csrf(csrf -> csrf.disable())
          .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
          .httpBasic(Customizer.withDefaults())
          .build();
    }
  }
}
