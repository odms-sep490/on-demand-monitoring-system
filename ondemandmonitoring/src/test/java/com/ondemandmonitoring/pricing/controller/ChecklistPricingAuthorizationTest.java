package com.ondemandmonitoring.pricing.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.common.exception.GlobalExceptionHandler;
import com.ondemandmonitoring.config.ActiveAccountFilter;
import com.ondemandmonitoring.config.SecurityConfig;
import com.ondemandmonitoring.pricing.dto.ServicePricePreviewResponse;
import com.ondemandmonitoring.pricing.service.ChecklistPricingPreviewService;
import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** API contract and authorization of the pricing preview endpoints. */
@SpringJUnitConfig(classes = {SecurityConfig.class, ActiveAccountFilter.class, ChecklistPricingController.class,
        GlobalExceptionHandler.class, ChecklistPricingAuthorizationTest.Beans.class})
@WebAppConfiguration
class ChecklistPricingAuthorizationTest {
    @Autowired WebApplicationContext context;
    @Autowired ChecklistPricingPreviewService preview;
    @Autowired AuthenticatedUserResolver resolver;
    MockMvc mvc;

    @BeforeEach void setup() {
        reset(preview, resolver);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var response = new ServicePricePreviewResponse("s1", "Inspection", "VND", "p1", 3, false, "CHECKLISTS", BigDecimal.ZERO,
                List.of(), new BigDecimal("1000000"), new BigDecimal("1000000"), true, List.of());
        when(preview.defaultPrice("s1")).thenReturn(response);
        when(preview.adminPreview(any())).thenReturn(response);
        when(preview.customerQuote(any())).thenReturn(response);
    }

    @Test void anonymousIsUnauthorized() throws Exception {
        mvc.perform(get("/api/admin/pricing/services/s1/default-price")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/pricing/checklist-quote").contentType(MediaType.APPLICATION_JSON).content("{\"serviceId\":\"s1\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(preview);
    }

    @ParameterizedTest
    @EnumSource(value = RoleCode.class, names = "ADMIN", mode = EnumSource.Mode.EXCLUDE)
    void onlyAdminsUseAdminPricingPreview(RoleCode role) throws Exception {
        role(role);
        mvc.perform(get("/api/admin/pricing/services/s1/default-price").with(jwt().authorities(() -> "ROLE_" + role.name())))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/pricing/service-preview").contentType(MediaType.APPLICATION_JSON)
                .content("{\"serviceId\":\"s1\"}").with(jwt().authorities(() -> "ROLE_" + role.name()))).andExpect(status().isForbidden());
        verifyNoInteractions(preview);
    }

    @Test void adminGetsTheBreakdownContract() throws Exception {
        role(RoleCode.ADMIN);
        mvc.perform(get("/api/admin/pricing/services/s1/default-price").with(jwt().authorities(() -> "ROLE_ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.packageFee").value(0))
                .andExpect(jsonPath("$.data.total").value(1000000)).andExpect(jsonPath("$.data.checklists").isArray())
                .andExpect(jsonPath("$.data.complete").value(true)).andExpect(jsonPath("$.data.policyVersion").value(3));
    }

    @ParameterizedTest
    @EnumSource(value = RoleCode.class, names = "CUSTOMER", mode = EnumSource.Mode.EXCLUDE)
    void onlyCustomersUseTheCustomerQuote(RoleCode role) throws Exception {
        role(role);
        mvc.perform(post("/api/pricing/checklist-quote").contentType(MediaType.APPLICATION_JSON).content("{\"serviceId\":\"s1\"}")
                .with(jwt().authorities(() -> "ROLE_" + role.name()))).andExpect(status().isForbidden());
        verifyNoInteractions(preview);
    }

    @Test void customerQuoteRejectsInvalidQuantitiesBeforeTheService() throws Exception {
        role(RoleCode.CUSTOMER);
        for (String body : List.of("{\"serviceId\":\"s1\",\"items\":[{\"sourceChecklistId\":\"c1\",\"requestedImageCount\":-1}]}",
                "{\"serviceId\":\"s1\",\"items\":[{\"sourceChecklistId\":\"c1\",\"requestedVideoCount\":501}]}", "{}")) {
            mvc.perform(post("/api/pricing/checklist-quote").contentType(MediaType.APPLICATION_JSON).content(body)
                    .with(jwt().authorities(() -> "ROLE_CUSTOMER"))).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(preview);
    }

    @Test void customerSuppliedPricesAreNotPartOfTheContractAndExtraPropertiesChangeNothing() throws Exception {
        role(RoleCode.CUSTOMER);
        mvc.perform(post("/api/pricing/checklist-quote").contentType(MediaType.APPLICATION_JSON)
                .content("{\"serviceId\":\"s1\",\"total\":1,\"items\":[{\"sourceChecklistId\":\"c1\",\"unitPrice\":1,\"amount\":1}]}")
                .with(jwt().authorities(() -> "ROLE_CUSTOMER"))).andExpect(status().isOk());
        verify(preview).customerQuote(argThat(request -> request.items().size() == 1
                && request.items().getFirst().sourceChecklistId().equals("c1")));
        for (var component : com.ondemandmonitoring.pricing.dto.ChecklistPricingPreviewRequests.CustomerItem.class.getRecordComponents())
            org.junit.jupiter.api.Assertions.assertFalse(component.getName().toLowerCase().matches(".*(price|amount|total|cost).*"), component.getName());
    }

    @Test void businessErrorsSurfaceAsConflictWithTheirCode() throws Exception {
        role(RoleCode.CUSTOMER);
        when(preview.customerQuote(any())).thenThrow(new ApiException(ErrorCode.CUSTOM_CHECKLIST_PRICING_NOT_CONFIGURED));
        mvc.perform(post("/api/pricing/checklist-quote").contentType(MediaType.APPLICATION_JSON).content("{\"serviceId\":\"s1\"}")
                .with(jwt().authorities(() -> "ROLE_CUSTOMER"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CUSTOM_CHECKLIST_PRICING_NOT_CONFIGURED"));
    }

    private void role(RoleCode role) {
        when(resolver.getCurrentUser()).thenReturn(User.builder().isActive(true)
                .role(Role.builder().code(role).active(true).build()).build());
    }

    @Configuration
    @EnableWebMvc
    static class Beans {
        @Bean ChecklistPricingPreviewService preview() { return mock(ChecklistPricingPreviewService.class); }
        @Bean AuthenticatedUserResolver resolver() { return mock(AuthenticatedUserResolver.class); }
        @Bean @Qualifier("cognitoAccessTokenDecoder") JwtDecoder decoder() { return mock(JwtDecoder.class); }
    }
}
