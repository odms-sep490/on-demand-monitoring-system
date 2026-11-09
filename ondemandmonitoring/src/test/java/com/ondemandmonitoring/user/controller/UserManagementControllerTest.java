package com.ondemandmonitoring.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ondemandmonitoring.common.api.PageResponse;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.dto.response.UserManagementSummaryResponse;
import com.ondemandmonitoring.user.dto.response.UserManagementDetailResponse;
import com.ondemandmonitoring.user.dto.response.CustomerProfileResponse;
import com.ondemandmonitoring.user.enumeration.IdentityProvider;
import com.ondemandmonitoring.user.service.IUserManagementService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class UserManagementControllerTest {

    private IUserManagementService userManagementService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        userManagementService = mock(IUserManagementService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new UserManagementController(userManagementService))
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();
    }

    @Test
    void controllerRequiresAdminRole() {
        PreAuthorize authorization = UserManagementController.class
                .getAnnotation(PreAuthorize.class);

        assertThat(authorization).isNotNull();
        assertThat(authorization.value()).isEqualTo("hasRole('ADMIN')");
    }

    @Test
    void getUsers_returnsFilteredPage() throws Exception {
        String userId = UUID.randomUUID().toString();
        PageResponse<UserManagementSummaryResponse> page = PageResponse.<UserManagementSummaryResponse>builder()
                .items(List.of(UserManagementSummaryResponse.builder()
                        .id(userId)
                        .fullName("Customer Name")
                        .email("customer@example.com")
                        .role(RoleCode.CUSTOMER)
                        .active(true)
                        .emailVerified(true)
                        .build()))
                .page(0)
                .size(20)
                .totalItems(1)
                .totalPages(1)
                .first(true)
                .last(true)
                .build();
        when(userManagementService.getUsers(
                any(Pageable.class),
                eq("customer"),
                eq(RoleCode.CUSTOMER),
                eq(true),
                eq(true)))
                .thenReturn(page);

        mockMvc.perform(get("/api/admin/users")
                        .param("search", "customer")
                        .param("role", "CUSTOMER")
                        .param("active", "true")
                        .param("emailVerified", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items[0].id").value(userId.toString()))
                .andExpect(jsonPath("$.data.items[0].active").value(true))
                .andExpect(jsonPath("$.data.totalItems").value(1));
    }

    @Test
    void getUsers_withoutQueryParametersUsesDefaultPageAndNoFilters() throws Exception {
        when(userManagementService.getUsers(any(Pageable.class),
                isNull(), isNull(), isNull(), isNull()))
                .thenReturn(PageResponse.<UserManagementSummaryResponse>builder()
                        .items(List.of())
                        .page(0)
                        .size(20)
                        .totalItems(0)
                        .totalPages(0)
                        .first(true)
                        .last(true)
                        .build());

        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(userManagementService).getUsers(pageableCaptor.capture(),
                isNull(), isNull(), isNull(), isNull());
        assertThat(pageableCaptor.getValue().getPageNumber()).isZero();
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(20);
        assertThat(pageableCaptor.getValue().getSort().getOrderFor("createdAt").getDirection())
                .isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void getUser_returnsManagementDetail() throws Exception {
        String userId = UUID.randomUUID().toString();
        when(userManagementService.getUser(userId)).thenReturn(
                UserManagementDetailResponse.builder()
                        .id(userId)
                        .fullName("Customer Name")
                        .email("customer@example.com")
                        .role(RoleCode.CUSTOMER)
                        .active(true)
                        .emailVerified(true)
                        .linkedProviders(List.of(IdentityProvider.LOCAL, IdentityProvider.GOOGLE))
                        .customerProfile(CustomerProfileResponse.builder()
                                .companyName("Customer Company")
                                .build())
                        .build());

        mockMvc.perform(get("/api/admin/users/{userId}", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(userId.toString()))
                .andExpect(jsonPath("$.data.linkedProviders[0]").value("LOCAL"))
                .andExpect(jsonPath("$.data.customerProfile.companyName")
                        .value("Customer Company"));
    }

    @Test
    void updateStatus_returnsUpdatedAccount() throws Exception {
        String userId = "user-123";

        when(userManagementService.updateStatus(userId, false))
                .thenReturn(UserManagementDetailResponse.builder()
                        .id(userId)
                        .fullName("Customer Name")
                        .email("customer@example.com")
                        .role(RoleCode.CUSTOMER)
                        .active(false)
                        .emailVerified(true)
                        .linkedProviders(List.of(IdentityProvider.LOCAL))
                        .build());

        mockMvc.perform(patch("/api/admin/users/{userId}/status", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message")
                        .value("Account status updated successfully"))
                .andExpect(jsonPath("$.data.id")
                        .value(userId))
                .andExpect(jsonPath("$.data.active")
                        .value(false));
    }
    @Test
    void updateStatus_rejectsMissingStatus() throws Exception {
        mockMvc.perform(patch("/api/admin/users/{userId}/status", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
