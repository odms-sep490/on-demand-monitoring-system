package com.ondemandmonitoring.config;

import com.ondemandmonitoring.common.exception.GlobalExceptionHandler;
import com.ondemandmonitoring.mission.controller.*;
import com.ondemandmonitoring.mission.domain.*;
import com.ondemandmonitoring.mission.enums.*;
import com.ondemandmonitoring.mission.mapper.CustomerMissionReportMapper;
import com.ondemandmonitoring.mission.repository.*;
import com.ondemandmonitoring.mission.service.*;
import com.ondemandmonitoring.mission.service.impl.CustomerMissionReportQueryService;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.repository.OrderRepository;
import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mapstruct.factory.Mappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitConfig(classes={SecurityConfig.class,ActiveAccountFilter.class,GlobalExceptionHandler.class,
        CustomerMissionReportController.class,MissionResultController.class,MissionChecklistExecutionController.class,
        MissionAuthorizationHttpTest.Beans.class,CustomerMissionReportHttpTest.Beans.class})
@WebAppConfiguration
class CustomerMissionReportHttpTest {
    @Autowired WebApplicationContext context;
    @Autowired AuthenticatedUserResolver actor;
    @Autowired OrderRepository orders;
    @Autowired MissionRepository missions;
    @Autowired MissionResultRepository results;
    @Autowired MissionChecklistExecutionRepository executions;
    @Autowired IMissionResultService operationalResults;
    @Autowired IMissionChecklistExecutionService operationalChecklist;
    MockMvc mvc;
    Mission mission;
    Order order;

    @BeforeEach void setup() {
        reset(actor,orders,missions,results,executions,operationalResults,operationalChecklist);
        var user=User.builder().isActive(true).role(Role.builder().active(true).code(RoleCode.CUSTOMER).build()).build(); user.setId("a");
        when(actor.getCurrentUser()).thenReturn(user);
        order=Order.builder().customer(user).build(); order.setId("oa");
        when(orders.findById("oa")).thenReturn(Optional.of(order));
        mission=new Mission(); mission.setId("m1"); mission.setOrder(order); mission.setMissionCode("M1"); mission.setStatus(MissionStatus.COMPLETED);
        when(missions.findById("m1")).thenReturn(Optional.of(mission));
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @Test void customerGetsPublicReportAndCannotReadOperationalApis() throws Exception {
        var result=new MissionResult(); result.setId("r"); result.setMission(mission); result.setApprovalStatus(MissionResultApprovalStatus.APPROVED);
        result.setSummary("Published"); result.setReviewNote("Secret"); when(results.findByMissionId("m1")).thenReturn(Optional.of(result));
        mvc.perform(get("/api/customer/orders/oa/missions/m1/report").with(jwt().authorities(() -> "ROLE_CUSTOMER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.reportAvailable").value(true))
                .andExpect(jsonPath("$.data.result.summary").value("Published"))
                .andExpect(jsonPath("$.data.result.reviewNote").doesNotExist())
                .andExpect(jsonPath("$.data.mission.staffAssignments").doesNotExist());
        for (String path:List.of("/api/missions/m1/result","/api/missions/m1/checklist-executions"))
            mvc.perform(get(path).with(jwt().authorities(() -> "ROLE_CUSTOMER"))).andExpect(status().isForbidden());
        verifyNoInteractions(operationalResults,operationalChecklist);
    }
    @Test void crossCustomerAndMismatchedOrderMissionPairsAreDenied() throws Exception {
        var other=User.builder().build(); other.setId("b"); var foreign=Order.builder().customer(other).build(); foreign.setId("ob");
        when(orders.findById("ob")).thenReturn(Optional.of(foreign)); mission.setOrder(foreign);
        for (String path:List.of("/api/customer/orders/ob/missions","/api/customer/orders/ob/missions/m1/report","/api/customer/orders/oa/missions/m1/report"))
            mvc.perform(get(path).with(jwt().authorities(() -> "ROLE_CUSTOMER"))).andExpect(status().isForbidden());
        verifyNoInteractions(results,executions);
    }
    @Test void onlyAuthenticatedCustomerCanReadAndNoMutationEndpointExists() throws Exception {
        String path="/api/customer/orders/oa/missions/m1/report";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        for (String role:List.of("MANAGER","ADMIN","STAFF"))
            mvc.perform(get(path).with(jwt().authorities(() -> "ROLE_"+role))).andExpect(status().isForbidden());
        mvc.perform(post(path).with(jwt().authorities(() -> "ROLE_CUSTOMER"))).andExpect(status().isMethodNotAllowed());
    }
    @Test void unpublishedResultReturnsNoAssessmentOrInternalContent() throws Exception {
        var result=new MissionResult(); result.setApprovalStatus(MissionResultApprovalStatus.REJECTED); result.setSummary("Secret rejected draft");
        when(results.findByMissionId("m1")).thenReturn(Optional.of(result));
        mvc.perform(get("/api/customer/orders/oa/missions/m1/report").with(jwt().authorities(() -> "ROLE_CUSTOMER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.reportAvailable").value(false))
                .andExpect(jsonPath("$.data.result").isEmpty()).andExpect(jsonPath("$.data.checklistItems").isEmpty());
        verifyNoInteractions(executions);
    }
    @Configuration static class Beans {
        @Bean OrderRepository orders(){return mock(OrderRepository.class);}
        @Bean MissionResultRepository results(){return mock(MissionResultRepository.class);}
        @Bean MissionChecklistExecutionRepository executions(){return mock(MissionChecklistExecutionRepository.class);}
        @Bean IMissionChecklistExecutionService operationalChecklist(){return mock(IMissionChecklistExecutionService.class);}
        @Bean ICustomerMissionReportQueryService reports(OrderRepository o,MissionRepository m,MissionResultRepository r,
                MissionChecklistExecutionRepository e,AuthenticatedUserResolver actor){
            return new CustomerMissionReportQueryService(o,m,r,e,actor,Mappers.getMapper(CustomerMissionReportMapper.class));
        }
    }
}
