package com.ondemandmonitoring.config;

import com.ondemandmonitoring.common.exception.GlobalExceptionHandler;
import com.ondemandmonitoring.mission.controller.MissionChecklistExecutionController;
import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.enums.*;
import com.ondemandmonitoring.mission.mapper.MissionChecklistExecutionMapper;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.*;
import com.ondemandmonitoring.mission.service.impl.MissionChecklistExecutionServiceImpl;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import com.ondemandmonitoring.order.repository.OrderChecklistItemRepository;
import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.util.*;
import java.time.Instant;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
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

@SpringJUnitConfig(classes = {SecurityConfig.class, ActiveAccountFilter.class, GlobalExceptionHandler.class,
        MissionChecklistExecutionController.class, MissionAuthorizationHttpTest.Beans.class,
        MissionChecklistExecutionHttpTest.Beans.class})
@WebAppConfiguration
class MissionChecklistExecutionHttpTest {
    @Autowired WebApplicationContext context;
    @Autowired AuthenticatedUserResolver resolver;
    @Autowired MissionRepository missions;
    @Autowired MissionStaffAssignmentRepository assignments;
    @Autowired MissionChecklistExecutionRepository executions;
    @Autowired MissionResultRepository results;
    @Autowired OrderChecklistItemRepository items;
    MockMvc mvc; User actor; Mission mission; MissionChecklistExecution execution;
    static final String URL = "/api/missions/m/checklist-executions";
    static final String BODY = "{\"expectedVersion\":0,\"executionStatus\":\"COMPLETED\",\"assessmentStatus\":\"NOT_ASSESSED\",\"observation\":\"Observed\"}";

    @BeforeEach void prepare() {
        reset(resolver, missions, assignments, executions, results, items);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        actor = User.builder().isActive(true).role(Role.builder().code(RoleCode.STAFF).active(true).build()).build(); actor.setId("actor");
        var order = new Order(); order.setId("o");
        mission = new Mission(); mission.setId("m"); mission.setOrder(order); mission.setStatus(MissionStatus.IN_FLIGHT);
        var item = new OrderChecklistItem(); item.setId("i"); item.setOrder(order); item.setContent("Historical requirement");
        item.setEvidencePolicyVersion(0); item.setMinimumEvidenceCount(0);
        execution = new MissionChecklistExecution(); execution.setId("e"); execution.setVersion(0L);
        execution.setMissionId("m"); execution.setOrderId("o"); execution.setOrderChecklistItemId("i");
        execution.setMission(mission); execution.setOrderChecklistItem(item);
        ScopeTestFixture.link(execution, mission);
        when(resolver.getCurrentUser()).thenReturn(actor); when(resolver.getCurrentUserId()).thenReturn("actor");
        when(missions.findById("m")).thenReturn(Optional.of(mission));
        when(missions.findByIdForUpdate("m")).thenReturn(Optional.of(mission));
        when(executions.findById("e")).thenReturn(Optional.of(execution));
        when(executions.findByIdForUpdate("e")).thenReturn(Optional.of(execution));
        when(executions.updateIfVersionMatches(anyString(), anyLong(), anyString(), anyString(),
                nullable(String.class), nullable(String.class), nullable(Instant.class), nullable(Instant.class),
                nullable(String.class), any(Instant.class))).thenAnswer(call -> {
                    execution.setExecutionStatus(ChecklistExecutionStatus.valueOf(call.getArgument(2)));
                    execution.setAssessmentStatus(ChecklistAssessmentStatus.valueOf(call.getArgument(3)));
                    execution.setObservation(call.getArgument(4));
                    execution.setUnableToVerifyReason(call.getArgument(5));
                    execution.setStartedAt(call.getArgument(6));
                    execution.setCompletedAt(call.getArgument(7));
                    execution.setLastModifiedBy(call.getArgument(8));
                    execution.setUpdatedAt(call.getArgument(9));
                    execution.setVersion(execution.getVersion() + 1);
                    return 1;
                });
        when(executions.findOrderedByMissionId("m")).thenReturn(List.of(execution));
        when(executions.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(items.findAllByOrderIdInOrderByDisplayOrderAscIdAsc(List.of("o"))).thenReturn(List.of(item));
        crew(MissionStaffRole.OPERATOR);
    }

    void crew(MissionStaffRole role) {
        var entry = MissionStaffAssignment.builder().staff(actor).assignedRole(role)
                .responseStatus(MissionStaffAssignmentStatus.ACCEPTED).isCurrent(true).build();
        when(assignments.findByMissionId("m")).thenReturn(List.of(entry));
        when(assignments.findAllByMissionIdAndStaffIdAndIsCurrentTrueOrderByAssignedAtDesc("m", "actor")).thenReturn(List.of(entry));
    }

    @Test void acceptedOperatorReadsHistoryAndUpdatesWithAuthenticatedActor() throws Exception {
        mvc.perform(get(URL).with(jwt().authorities(() -> "ROLE_STAFF")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.executions[0].content").value("Historical requirement"));
        verify(executions, never()).saveAllAndFlush(any());
        mvc.perform(patch(URL + "/e").with(jwt().authorities(() -> "ROLE_STAFF")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.lastModifiedBy").value("actor"));
    }

    @Test void pendingReviewAllowsOperatorMutationButLocksSubmittedResults() throws Exception {
        mission.setStatus(MissionStatus.PENDING_REVIEW);
        mvc.perform(patch(URL + "/e").with(jwt().authorities(() -> "ROLE_STAFF")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isOk());
        var submitted = new MissionResult(); submitted.setApprovalStatus(MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL);
        when(results.findByMissionId("m")).thenReturn(Optional.of(submitted));
        mvc.perform(patch(URL + "/e").with(jwt().authorities(() -> "ROLE_STAFF")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isConflict());
    }

    @Test void customerManagerAndAdminCannotMutate() throws Exception {
        for (RoleCode role : List.of(RoleCode.CUSTOMER, RoleCode.MANAGER, RoleCode.ADMIN)) {
            actor.getRole().setCode(role); crew(MissionStaffRole.INSPECTOR);
            mvc.perform(patch(URL + "/e").with(jwt().authorities(() -> "ROLE_" + role.name())).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        }
        verify(executions, never()).saveAndFlush(any());
    }

    @Test void unauthenticatedAndUnassignedRequestsDenied() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
        when(assignments.findByMissionId("m")).thenReturn(List.of());
        when(assignments.findAllByMissionIdAndStaffIdAndIsCurrentTrueOrderByAssignedAtDesc("m", "actor")).thenReturn(List.of());
        mvc.perform(get(URL).with(jwt().authorities(() -> "ROLE_STAFF"))).andExpect(status().isForbidden());
        mvc.perform(patch(URL + "/e").with(jwt().authorities(() -> "ROLE_STAFF")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
    }

    @Test void idorAndStaleVersionsReturnSafeErrors() throws Exception {
        execution.setMissionId("other");
        mvc.perform(patch(URL + "/e").with(jwt().authorities(() -> "ROLE_STAFF")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CHECKLIST_EXECUTION_PARENT_MISMATCH"));
        execution.setMissionId("m"); execution.setVersion(1L);
        mvc.perform(patch(URL + "/e").with(jwt().authorities(() -> "ROLE_STAFF")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_UPDATE"));
    }

    @Test void invalidPayloadAndReviewLockAreEnforced() throws Exception {
        mvc.perform(patch(URL + "/e").with(jwt().authorities(() -> "ROLE_STAFF")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        var result = new MissionResult(); result.setApprovalStatus(MissionResultApprovalStatus.PENDING_MANAGER_APPROVAL);
        when(results.findByMissionId("m")).thenReturn(Optional.of(result));
        mvc.perform(patch(URL + "/e").with(jwt().authorities(() -> "ROLE_STAFF")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHECKLIST_EXECUTION_LOCKED"));
    }

    @Configuration static class Beans {
        @Bean MissionChecklistExecutionRepository executions() { return mock(MissionChecklistExecutionRepository.class); }
        @Bean MissionResultRepository results() { return mock(MissionResultRepository.class); }
        @Bean OrderChecklistItemRepository items() { return mock(OrderChecklistItemRepository.class); }
        @Bean IMissionChecklistExecutionService checklistService(MissionRepository missions,
                OrderChecklistItemRepository items, MissionChecklistExecutionRepository executions,
                MissionResultRepository results, IMissionAuthorizationService auth, AuthenticatedUserResolver resolver) {
            return new MissionChecklistExecutionServiceImpl(missions, com.ondemandmonitoring.mission.service.ScopeTestFixture.validScope(), executions, results, auth, resolver,
                    org.mapstruct.factory.Mappers.getMapper(MissionChecklistExecutionMapper.class), com.ondemandmonitoring.mission.service.EvidenceTestFixture.emptyService());
        }
    }
}
