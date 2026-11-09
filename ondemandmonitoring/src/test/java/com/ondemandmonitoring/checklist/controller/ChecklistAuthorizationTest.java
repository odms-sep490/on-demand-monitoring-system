package com.ondemandmonitoring.checklist.controller;

import com.ondemandmonitoring.checklist.dto.response.*;
import com.ondemandmonitoring.checklist.service.*;
import com.ondemandmonitoring.common.api.PageResponse;
import com.ondemandmonitoring.common.exception.GlobalExceptionHandler;
import com.ondemandmonitoring.config.*;
import com.ondemandmonitoring.role.domain.*;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import com.ondemandmonitoring.service.controller.ServiceController;
import com.ondemandmonitoring.pricing.service.ServicePriceResolver;
import com.ondemandmonitoring.service.dto.response.ServiceResponse;
import com.ondemandmonitoring.service.service.IServiceService;
import com.ondemandmonitoring.service.domain.Service;
import java.math.BigDecimal;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Page;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.util.List;
import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitConfig(classes = {SecurityConfig.class, ActiveAccountFilter.class,
        ChecklistController.class, ServiceChecklistController.class, ChecklistTemplateController.class,
        ServiceController.class, ChecklistConstraintHandler.class, GlobalExceptionHandler.class,
        ChecklistAuthorizationTest.Beans.class})
@WebAppConfiguration
class ChecklistAuthorizationTest {
    @Autowired WebApplicationContext context;
    @Autowired IChecklistService catalog;
    @Autowired IServiceChecklistService assignments;
    @Autowired IServiceService services;
    @Autowired AuthenticatedUserResolver resolver;
    @Autowired ServicePriceResolver priceResolver;
    MockMvc mvc;

    @BeforeEach void setup() {
        reset(catalog, assignments, services, resolver, priceResolver);
        when(priceResolver.forDisplay(any())).thenReturn(ServicePriceResolver.of(new BigDecimal("3200000")));
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var response = new ChecklistResponse(); response.setId("c1"); response.setContent("Check exits");
        when(catalog.create(any())).thenReturn(response);
        when(catalog.update(anyString(), any())).thenReturn(response);
        when(catalog.updateStatus(anyString(), anyBoolean())).thenReturn(response);
        when(catalog.getById(anyString())).thenReturn(response);
        when(catalog.getAll(any(), any(), any())).thenReturn(PageResponse.from(Page.empty()));
        when(assignments.getByService(anyString(), anyBoolean())).thenReturn(List.of());
        when(assignments.getByChecklist(anyString())).thenReturn(List.of());
        when(services.getEntityById("s1")).thenReturn(Service.builder()
                .name("Service").basePrice(new BigDecimal("3200000")).isActive(true).build());
    }

    List<MockHttpServletRequestBuilder> adminRequests() {
        return List.of(
            post("/api/admin/checklists").content("{\"content\":\"Check exits\"}"),
            get("/api/admin/checklists"), get("/api/admin/checklists/c1"),
            put("/api/admin/checklists/c1").content("{\"content\":\"New\"}"),
            delete("/api/admin/checklists/c1"),
            patch("/api/admin/checklists/c1/status").content("{\"active\":false}"),
            post("/api/admin/services/s1/checklists").content("{\"checklistId\":\"c1\",\"displayOrder\":0}"),
            patch("/api/admin/services/s1/checklists/c1").content("{\"displayOrder\":2}"),
            delete("/api/admin/services/s1/checklists/c1"),
            put("/api/admin/services/s1/checklists/c1/pricing").content("{\"basePriceOverride\":250000,\"includedImageCountOverride\":12}"),
            put("/api/admin/services/s1/checklists/order").content("{\"items\":[]}"),
            get("/api/admin/services/s1/checklists"), get("/api/admin/checklists/c1/services"),
            post("/api/services").content("{\"name\":\"Service\",\"basePrice\":3200000}"),
            put("/api/services/s1").content("{\"name\":\"Service\",\"basePrice\":3200000}"),
            delete("/api/services/s1")
        );
    }

    @Test void anonymousCannotAccessAnyChecklistApi() throws Exception {
        for (var request : adminRequests()) mvc.perform(request.contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/services/s1/checklists")).andExpect(status().isUnauthorized());
        verifyNoInteractions(catalog, assignments, services);
    }

    @ParameterizedTest
    @EnumSource(value = RoleCode.class, names = "ADMIN", mode = EnumSource.Mode.EXCLUDE)
    void nonAdminsCannotManageCatalogOrAssignments(RoleCode role) throws Exception {
        role(role);
        for (var request : adminRequests()) mvc.perform(request.contentType(MediaType.APPLICATION_JSON)
                .with(jwt().authorities(() -> "ROLE_" + role.name()))).andExpect(status().isForbidden());
        verifyNoInteractions(catalog, assignments, services);
    }

    @Test void adminCanUseEveryAdminEndpoint() throws Exception {
        role(RoleCode.ADMIN);
        for (var request : adminRequests()) {
            int expected = request.buildRequest(context.getServletContext()).getMethod().equals("POST") ? 201 : 200;
            mvc.perform(request.contentType(MediaType.APPLICATION_JSON).with(jwt().authorities(() -> "ROLE_ADMIN")))
                    .andExpect(status().is(expected));
        }
    }

    @Test void customerCanReadActiveTemplate() throws Exception {
        role(RoleCode.CUSTOMER);
        mvc.perform(get("/api/services/s1/checklists").with(jwt().authorities(() -> "ROLE_CUSTOMER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray());
        verify(assignments).getByService("s1", true);
    }

    @Test void customerPricingEstimateUsesNonZeroConfiguredDefaults() throws Exception {
        role(RoleCode.CUSTOMER);
        mvc.perform(get("/api/services/pricing-estimate")
                        .param("serviceId", "s1")
                        .with(jwt().authorities(() -> "ROLE_CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.servicePrice").value(3200000))
                .andExpect(jsonPath("$.data.additionalRequirements").isEmpty())
                .andExpect(jsonPath("$.data.totalPrice").value(3200000));
    }

    @Test void pricingEstimateKeepsItsShapeButFollowsTheChecklistDefaultPrice() throws Exception {
        role(RoleCode.CUSTOMER);
        when(priceResolver.forDisplay(any())).thenReturn(ServicePriceResolver.of(new BigDecimal("470000")));
        mvc.perform(get("/api/services/pricing-estimate").param("serviceId", "s1")
                        .with(jwt().authorities(() -> "ROLE_CUSTOMER")))
                .andExpect(status().isOk())
                // the legacy catalog price is NOT added on top of priced checklists
                .andExpect(jsonPath("$.data.servicePrice").value(470000))
                .andExpect(jsonPath("$.data.additionalRequirements").isEmpty())
                .andExpect(jsonPath("$.data.totalPrice").value(470000))
                .andExpect(jsonPath("$.data.serviceId").value("s1"));
    }

    @Test void serviceReadsExposeTheLegacyPriceAndTheChecklistDefaultPriceSeparately() throws Exception {
        role(RoleCode.CUSTOMER);
        var response = ServiceResponse.builder().id("s1").name("Service").basePrice(new BigDecimal("3200000")).build();
        when(services.getById("s1")).thenReturn(response);
        when(services.getAllActive()).thenReturn(List.of(ServiceResponse.builder().id("s1").name("Service").basePrice(new BigDecimal("3200000")).build()));
        when(priceResolver.forDisplay(any())).thenReturn(ServicePriceResolver.of(new BigDecimal("470000")));
        mvc.perform(get("/api/services/s1").with(jwt().authorities(() -> "ROLE_CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.basePrice").value(3200000))
                .andExpect(jsonPath("$.data.defaultPrice").value(470000))
                .andExpect(jsonPath("$.data.priceSource").value("CHECKLISTS"));
        mvc.perform(get("/api/services").param("activeOnly", "true").with(jwt().authorities(() -> "ROLE_CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].defaultPrice").value(470000));
    }

    @Test void emptySearchIsOptionalAndPaginationBindsAsQueryParameters() throws Exception {
        role(RoleCode.ADMIN);
        mvc.perform(get("/api/admin/checklists").param("page", "0").param("size", "6")
                .param("sort", "createdAt,desc").with(jwt().authorities(() -> "ROLE_ADMIN")))
                .andExpect(status().isOk());
        verify(catalog).getAll(isNull(), isNull(), argThat(p -> p.getPageSize() == 6
                && p.getSort().getOrderFor("createdAt").isDescending()));
    }

    @Test void invalidBodiesRejectedBeforeService() throws Exception {
        role(RoleCode.ADMIN);
        for (var request : List.of(
                post("/api/admin/checklists").content("{\"content\":\" \"}"),
                post("/api/admin/checklists").content("{\"content\":\"" + "x".repeat(501) + "\"}"),
                post("/api/admin/checklists").content("{\"content\":\"Check\",\"basePrice\":-1}"),
                post("/api/admin/checklists").content("{\"content\":\"Check\",\"includedImageCount\":-1}"),
                post("/api/admin/checklists").content("{\"content\":\"Check\",\"includedVideoCount\":501}"),
                put("/api/admin/checklists/c1").content("{\"content\":\"Check\",\"standardVideoDurationSeconds\":0}"),
                put("/api/admin/checklists/c1").content("{\"content\":\"Check\",\"pricingProfile\":\"bad profile!\"}"),
                put("/api/admin/services/s1/checklists/c1/pricing").content("{\"basePriceOverride\":-5}"),
                put("/api/admin/services/s1/checklists/c1/pricing").content("{\"includedImageCountOverride\":-1}"),
                patch("/api/admin/checklists/c1/status").content("{}"),
                post("/api/admin/services/s1/checklists").content("{\"checklistId\":\"c1\",\"displayOrder\":-1}"),
                patch("/api/admin/services/s1/checklists/c1").content("{}"),
                put("/api/admin/services/s1/checklists/order").content("{\"items\":[{\"checklistId\":\"c1\"}]}"))) {
            mvc.perform(request.contentType(MediaType.APPLICATION_JSON).with(jwt().authorities(() -> "ROLE_ADMIN")))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(catalog, assignments);
    }

    @Test void knownConstraintMapsTo409ButUnknownConstraintRemains500() throws Exception {
        role(RoleCode.ADMIN);
        when(catalog.create(any())).thenThrow(new DataIntegrityViolationException("duplicate",
                new ConstraintViolationException("duplicate", new SQLException("duplicate"), "uk_checklist_normalized_content")));
        mvc.perform(post("/api/admin/checklists").contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"Check exits\"}").with(jwt().authorities(() -> "ROLE_ADMIN")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHECKLIST_ALREADY_EXISTS"));
        doThrow(new DataIntegrityViolationException("unknown")).when(catalog).create(any());
        mvc.perform(post("/api/admin/checklists").contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"Check exits\"}").with(jwt().authorities(() -> "ROLE_ADMIN")))
                .andExpect(status().isInternalServerError());
    }

    private void role(RoleCode role) {
        when(resolver.getCurrentUser()).thenReturn(User.builder().isActive(true)
                .role(Role.builder().code(role).active(true).build()).build());
    }

    @Configuration
    @EnableWebMvc
    @EnableSpringDataWebSupport
    static class Beans {
        @Bean IChecklistService catalog() { return mock(IChecklistService.class); }
        @Bean IServiceChecklistService assignments() { return mock(IServiceChecklistService.class); }
        @Bean IServiceService services() { return mock(IServiceService.class); }
        @Bean AuthenticatedUserResolver resolver() { return mock(AuthenticatedUserResolver.class); }
        @Bean ServicePriceResolver priceResolver() { return mock(ServicePriceResolver.class); }
        @Bean @Qualifier("cognitoAccessTokenDecoder") JwtDecoder decoder() { return mock(JwtDecoder.class); }
    }
}
