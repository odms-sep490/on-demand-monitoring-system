package com.ondemandmonitoring.mission.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ondemandmonitoring.common.exception.GlobalExceptionHandler;
import com.ondemandmonitoring.config.*;
import com.ondemandmonitoring.mission.controller.*;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.service.impl.MissionAuthorizationServiceImpl;
import com.ondemandmonitoring.order.domain.Order;
import com.ondemandmonitoring.order.enums.*;
import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import com.ondemandmonitoring.mission.repository.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.locationtech.jts.geom.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real command + MVC validation + project security, with repository storage mocked (PG tests prove atomicity). */
@SpringJUnitConfig(classes={SecurityConfig.class,ActiveAccountFilter.class,
        MissionController.class,MissionAuthorizationServiceImpl.class,GlobalExceptionHandler.class,MissionCreationHttpTest.Beans.class})
@WebAppConfiguration
class MissionCreationHttpTest {
    @Autowired WebApplicationContext context;
    @Autowired MissionServiceTest fixture;
    MockMvc mvc;
    Order order;
    User caller;
    Map<String,Mission> stored;
    @BeforeEach void setup() {
        reset(fixture.orderRepository,fixture.missionRepository,fixture.authenticatedUserResolver);
        var quote = new com.ondemandmonitoring.finance.domain.Quote();
        quote.setStatus(com.ondemandmonitoring.finance.enums.QuoteStatus.ACCEPTED_BY_CUSTOMER);
        var invoice = new com.ondemandmonitoring.finance.domain.Invoice(); invoice.setQuote(quote);
        when(fixture.invoiceRepository.findByOrderId(anyString())).thenReturn(Optional.of(invoice));
        when(fixture.missionPaymentEligibilityService.isDepositSatisfied(invoice)).thenReturn(true);
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        caller=User.builder().isActive(true).role(Role.builder().code(RoleCode.MANAGER).active(true).build()).build();caller.setId("manager");
        when(fixture.authenticatedUserResolver.getCurrentUser()).thenAnswer(inv->caller);
        order=Order.builder().orderStatus(OrderStatus.APPROVED).preferredDateFrom(LocalDate.of(2026,10,8)).preferredDateTo(LocalDate.of(2026,10,10)).build();order.setId("o");
        order.setPoint(new GeometryFactory(new PrecisionModel(),4326).createPoint(new Coordinate(106,10)));
        when(fixture.orderRepository.findByIdForUpdate("o")).thenReturn(Optional.of(order));
        stored=new HashMap<>();
        when(fixture.missionRepository.findAllByOrderIdOrderByScheduledStartAtAscIdAsc(anyString()))
                .thenAnswer(inv->stored.values().stream().filter(m->m.getOrder().getId().equals(inv.getArgument(0))).toList());
        when(fixture.missionRepository.saveAndFlush(any())).thenAnswer(inv->{Mission m=inv.getArgument(0);m.setId(UUID.randomUUID().toString());stored.put(m.getId(),m);return m;});
    }
    ResultActions create(String orderId,String key,long revision,String label,String role) throws Exception {
        var r=MissionCreationServiceTest.request(orderId,key,revision);r.setScopeLabel(label);
        return mvc.perform(post("/api/missions/{orderId}", orderId).with(jwt().authorities(()->"ROLE_"+role)).contentType("application/json")
                .content(new ObjectMapper().findAndRegisterModules().writeValueAsString(r)));
    }
    @Test void repeatedRequestsCreateDistinctMissions() throws Exception {
        create("o","unused",0,"Scope","MANAGER").andExpect(status().isCreated());
        create("o","unused",1,"Scope","MANAGER").andExpect(status().isCreated());
        assertEquals(2,stored.size());assertEquals(2,order.getExecutionPlan().getRevision());
    }
    @Test void differentKeyAndDifferentOrderAreIndependent() throws Exception {
        String key=UUID.randomUUID().toString();create("o",key,0,"Scope","ADMIN").andExpect(status().isCreated());
        create("o",UUID.randomUUID().toString(),1,"Scope","ADMIN").andExpect(status().isCreated());
        var other=Order.builder().orderStatus(OrderStatus.APPROVED).preferredDateFrom(order.getPreferredDateFrom()).preferredDateTo(order.getPreferredDateTo()).point(order.getPoint()).build();other.setId("other");
        when(fixture.orderRepository.findByIdForUpdate("other")).thenReturn(Optional.of(other));
        create("other",key,0,"Scope","ADMIN").andExpect(status().isCreated());assertEquals(3,stored.size());
    }
    @Test void staleAndFinalizedErrorsAreExplicit() throws Exception {
        String key=UUID.randomUUID().toString();
        create("o",key,3,"Scope","MANAGER").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EXECUTION_PLAN_REVISION_CONFLICT"));
        create("o",key,0,"Scope","MANAGER").andExpect(status().isCreated());
        order.getExecutionPlan().setStatus(ExecutionPlanStatus.FINALIZED);order.setOrderStatus(OrderStatus.COMPLETED);
        create("o",UUID.randomUUID().toString(),1,"Scope","MANAGER").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EXECUTION_PLAN_NOT_DRAFT"));
        create("o",key,0,"Scope","MANAGER").andExpect(status().isConflict());
    }
    @ParameterizedTest @EnumSource(value=OrderStatus.class,names={"COMPLETED","CANCELLED","REJECTED"})
    void terminalOrderRejected(OrderStatus status) throws Exception {order.setOrderStatus(status);create("o",UUID.randomUUID().toString(),0,"Scope","MANAGER").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));}
    @Test void requiredFieldsAndRevisionValidatedAndCustomerDenied() throws Exception {
        mvc.perform(post("/api/missions/o").with(jwt().authorities(()->"ROLE_MANAGER")).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        caller.setRole(Role.builder().code(RoleCode.CUSTOMER).active(true).build());
        create("o",UUID.randomUUID().toString(),0,"Scope","CUSTOMER").andExpect(status().isForbidden());verifyNoInteractions(fixture.orderRepository);
        mvc.perform(post("/api/missions/o").contentType("application/json").content("{}" )).andExpect(status().isUnauthorized());
    }
    @Configuration @EnableWebMvc static class Beans {
        @Bean com.ondemandmonitoring.devicecheck.repository.PersistedPreDeviceCheckRepository preflightRepository() {
            return mock(com.ondemandmonitoring.devicecheck.repository.PersistedPreDeviceCheckRepository.class);
        }
        @Bean com.ondemandmonitoring.delivery.service.IDeliveryWorkflowService deliveryWorkflow() {
            return org.mockito.Mockito.mock(com.ondemandmonitoring.delivery.service.IDeliveryWorkflowService.class);
        }
        @Bean IMissionChecklistScopeService scope(){return ScopeTestFixture.validScope();}
        @Bean MissionServiceTest fixture(){var f=new MissionServiceTest();f.setUp();return f;}
        @Bean IMissionService missionService(MissionServiceTest f){return f.missionService;}
        @Bean MissionRepository missions(MissionServiceTest f){return f.missionRepository;}
        @Bean MissionStaffAssignmentRepository assignments(MissionServiceTest f){return f.missionStaffAssignmentRepository;}
        @Bean MissionResultRepository results(){return mock(MissionResultRepository.class);}
        @Bean AuthenticatedUserResolver resolver(MissionServiceTest f){return f.authenticatedUserResolver;}
        @Bean IOrderMissionQueryService queryService(){return mock(IOrderMissionQueryService.class);}
        @Bean IMissionResultService resultService(){return mock(IMissionResultService.class);}
        @Bean IMissionMediaUploadService mediaUploadService(){return mock(IMissionMediaUploadService.class);}
        @Bean com.ondemandmonitoring.media.mapper.MediaAssetMapper mediaAssetMapper(){return mock(com.ondemandmonitoring.media.mapper.MediaAssetMapper.class);}
        @Bean @Qualifier("cognitoAccessTokenDecoder") JwtDecoder decoder(){return mock(JwtDecoder.class);}
    }
}
