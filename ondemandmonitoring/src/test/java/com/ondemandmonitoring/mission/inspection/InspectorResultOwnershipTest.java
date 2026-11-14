package com.ondemandmonitoring.mission.inspection;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ondemandmonitoring.mission.controller.ManagerMissionResultApprovalController;
import com.ondemandmonitoring.mission.service.IMissionResultService;
import com.ondemandmonitoring.mission.service.MissionResultSubmissionFixture;
import com.ondemandmonitoring.mission.inspection.service.impl.InspectionCorrectionPolicy;
import com.ondemandmonitoring.delivery.service.IDeliveryWorkflowService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringJUnitConfig(InspectorResultOwnershipTest.Config.class)
@WebAppConfiguration
class InspectorResultOwnershipTest {
  @Autowired WebApplicationContext context;
  @Autowired IMissionResultService service;
  @Autowired MissionResultSubmissionFixture fixture;

  @AfterEach void clear() { SecurityContextHolder.clearContext(); }

  @ParameterizedTest
  @ValueSource(strings = {"MANAGER", "ADMIN", "STAFF"})
  void retiredReviewEndpointsRejectEveryActor(String role) throws Exception {
    var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    for (String action : new String[] {"approve", "reject"}) {
      mvc.perform(post("/api/manager/mission-results/r/" + action)
              .with(user("actor").roles(role)).contentType("application/json").content("{}"))
          .andExpect(status().isForbidden());
    }
    verifyNoInteractions(fixture.results, fixture.missions, fixture.completion);
  }

  @ParameterizedTest
  @ValueSource(strings = {"MANAGER", "ADMIN", "STAFF"})
  void retiredServiceMethodsCannotBypassInspectorConfirmation(String role) {
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
        "actor", "unused", AuthorityUtils.createAuthorityList("ROLE_" + role)));
    assertThatThrownBy(() -> service.approve("r", null)).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> service.reject("r", null)).isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(fixture.results, fixture.missions, fixture.completion);
  }

  @Configuration
  @Import(InspectionCorrectionControllerTest.Config.class)
  static class Config {
    @Bean MissionResultSubmissionFixture fixture() { return new MissionResultSubmissionFixture(); }
    @Bean IMissionResultService results(MissionResultSubmissionFixture f) { return f.service; }
    @Bean IDeliveryWorkflowService delivery(MissionResultSubmissionFixture f) { return f.delivery; }
    @Bean InspectionCorrectionPolicy policy() { return mock(InspectionCorrectionPolicy.class); }
    @Bean ManagerMissionResultApprovalController managerResults(IMissionResultService service) {
      return new ManagerMissionResultApprovalController(service);
    }
  }
}
