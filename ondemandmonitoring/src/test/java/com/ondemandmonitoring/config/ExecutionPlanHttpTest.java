package com.ondemandmonitoring.config;

import com.ondemandmonitoring.common.exception.GlobalExceptionHandler;
import com.ondemandmonitoring.mission.controller.*;
import com.ondemandmonitoring.mission.dto.response.ChecklistAssignmentSelectionResponse;
import com.ondemandmonitoring.mission.service.*;
import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitConfig(classes={SecurityConfig.class,ActiveAccountFilter.class,GlobalExceptionHandler.class,
        MissionController.class,MissionChecklistAssignmentController.class,ExecutionPlanController.class,MissionCancellationController.class,MissionAuthorizationHttpTest.Beans.class,ExecutionPlanHttpTest.Beans.class})
@WebAppConfiguration
class ExecutionPlanHttpTest {
    @Autowired WebApplicationContext context;
    @Autowired AuthenticatedUserResolver resolver;
    @Autowired IMissionChecklistAssignmentService selection;
    @Autowired IExecutionPlanService plan;
    @Autowired IMissionCancellationService cancellation;
    @Autowired IMissionService missions;
    MockMvc mvc;
    @BeforeEach void prepare() {
        reset(resolver,selection,plan,cancellation,missions);
        when(resolver.getCurrentUser()).thenReturn(User.builder().isActive(true).role(Role.builder().code(RoleCode.MANAGER).active(true).build()).build());
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @Test void anonymousAndCrewCannotManagePlanning() throws Exception {
        mvc.perform(get("/api/missions/m/checklist-assignments")).andExpect(status().isUnauthorized());
        for (String role:List.of("CUSTOMER","STAFF")) {
            mvc.perform(get("/api/missions/m/checklist-assignments").with(jwt().authorities(()->"ROLE_"+role))).andExpect(status().isForbidden());
            mvc.perform(put("/api/missions/m/checklist-assignments").with(jwt().authorities(()->"ROLE_"+role))
                    .contentType("application/json").content("{\"expectedPlanRevision\":2,\"items\":[]}")).andExpect(status().isForbidden());
            mvc.perform(post("/api/orders/o/execution-plan/finalize").with(jwt().authorities(()->"ROLE_"+role))
                    .contentType("application/json").content("{\"expectedPlanRevision\":2,\"decompositionConfirmed\":true}")).andExpect(status().isForbidden());
        }
        verifyNoInteractions(selection,plan);
    }
    @Test void managerAndAdminCanReadAndReplaceUsingExplicitRevision() throws Exception {
        when(selection.get("m")).thenReturn(new ChecklistAssignmentSelectionResponse("m",2,List.of()));
        when(selection.replace(eq("m"),any())).thenReturn(new ChecklistAssignmentSelectionResponse("m",3,List.of()));
        for (String role:List.of("MANAGER","ADMIN")) {
            mvc.perform(get("/api/missions/m/checklist-assignments").with(jwt().authorities(()->"ROLE_"+role)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value(2));
            mvc.perform(put("/api/missions/m/checklist-assignments").with(jwt().authorities(()->"ROLE_"+role))
                    .contentType("application/json").content("{\"expectedPlanRevision\":2,\"items\":[{\"orderChecklistItemId\":\"A\"}]}")).andExpect(status().isOk());
            mvc.perform(post("/api/orders/o/execution-plan/finalize").with(jwt().authorities(()->"ROLE_"+role))
                    .contentType("application/json").content("{\"expectedPlanRevision\":3,\"decompositionConfirmed\":true}")).andExpect(status().isOk());
        }
        verify(selection,times(2)).replace(eq("m"),argThat(r->r.expectedPlanRevision()==2 && r.items().getFirst().orderChecklistItemId().equals("A")));
    }
    @Test void invalidSelectionAndMissingFinalizeFieldsNeverReachService() throws Exception {
        for (String body:List.of("{}","{\"expectedPlanRevision\":-1,\"items\":[]}","{\"expectedPlanRevision\":2,\"items\":[{\"orderChecklistItemId\":\"\"}]}"))
            mvc.perform(put("/api/missions/m/checklist-assignments").with(jwt().authorities(()->"ROLE_MANAGER")).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/orders/o/execution-plan/finalize").with(jwt().authorities(()->"ROLE_MANAGER")).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(selection,plan);
    }
    @Test void amendEndpointHasBeenRemoved() throws Exception {
        mvc.perform(post("/api/orders/o/execution-plan/amend").with(jwt().authorities(() -> "ROLE_MANAGER"))
                .contentType("application/json").content("{\"expectedPlanRevision\":4,\"reason\":\"Weather\"}"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(plan);
    }

    @Configuration static class Beans {
        @Bean IMissionCancellationService cancellation() { return mock(IMissionCancellationService.class); }
        @Bean IMissionChecklistAssignmentService selection() {return mock(IMissionChecklistAssignmentService.class);}
        @Bean IExecutionPlanService plan() {return mock(IExecutionPlanService.class);}
    }

    @Test void cancellationIsManagerAdminOnlyAndValidatesRequests() throws Exception {
        String body = "{\"expectedPlanRevision\":4,\"reason\":\"Weather\"}";
        for (String path : List.of("/api/missions/m/cancel")) {
            mvc.perform(post(path).contentType("application/json").content(body)).andExpect(status().isUnauthorized());
            for (String role : List.of("CUSTOMER", "STAFF")) {
                mvc.perform(post(path).with(jwt().authorities(() -> "ROLE_" + role)).contentType("application/json").content(body))
                        .andExpect(status().isForbidden());
            }
            mvc.perform(post(path).with(jwt().authorities(() -> "ROLE_MANAGER")).contentType("application/json").content("{}"))
                    .andExpect(status().isBadRequest());
            for (String role : List.of("MANAGER", "ADMIN")) {
                mvc.perform(post(path).with(jwt().authorities(() -> "ROLE_" + role)).contentType("application/json").content(body))
                        .andExpect(status().isOk());
            }
        }
        verify(cancellation,times(2)).cancel(eq("m"),any());
    }
    @Test void missionConfirmationAndDeletionRequireManagerAdmin() throws Exception {
        for (String role : List.of("CUSTOMER", "STAFF")) {
            mvc.perform(post("/api/missions/m/confirm-planning").with(jwt().authorities(() -> "ROLE_" + role)))
                    .andExpect(status().isForbidden());
            mvc.perform(delete("/api/missions/m").with(jwt().authorities(() -> "ROLE_" + role)))
                    .andExpect(status().isForbidden());
        }
        for (String role : List.of("MANAGER", "ADMIN")) {
            mvc.perform(post("/api/missions/m/confirm-planning").with(jwt().authorities(() -> "ROLE_" + role)))
                    .andExpect(status().isOk());
            mvc.perform(delete("/api/missions/m").with(jwt().authorities(() -> "ROLE_" + role)))
                    .andExpect(status().isNoContent());
        }
        verify(missions, times(2)).confirmMissionPlanning("m"); verify(missions, times(2)).deleteDraftMission("m");
    }

    @Test void createAdditionalUsesNewEndpointAndValidatesPayload() throws Exception {
        var body = "{\"scopeLabel\":\"Extra scope\",\"scheduledStartAt\":\"2026-11-01T01:00:00Z\","
                + "\"scheduledEndAt\":\"2026-11-01T02:00:00Z\",\"checklistIds\":[\"i\"]}";
        mvc.perform(post("/api/missions/o/additional").with(jwt().authorities(() -> "ROLE_MANAGER"))
                .contentType("application/json").content(body)).andExpect(status().isCreated());
        verify(missions).createAdditionalMission(eq("o"), argThat(r -> r.getChecklistIds().equals(List.of("i"))));
        mvc.perform(post("/api/missions/o/additional").with(jwt().authorities(() -> "ROLE_MANAGER"))
                .contentType("application/json").content("{}")).andExpect(status().isBadRequest());
    }

}
