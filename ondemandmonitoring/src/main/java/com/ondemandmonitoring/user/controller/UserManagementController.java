package com.ondemandmonitoring.user.controller;

import com.ondemandmonitoring.common.api.ApiResponse;
import com.ondemandmonitoring.common.api.PageResponse;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.dto.request.UserStatusUpdateRequest;
import com.ondemandmonitoring.user.dto.response.UserManagementDetailResponse;
import com.ondemandmonitoring.user.dto.response.UserManagementSummaryResponse;
import com.ondemandmonitoring.user.service.IUserManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "User Management", description = "Admin APIs for managing user accounts and statuses")
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class UserManagementController {

    IUserManagementService userManagementService;

    @Operation(summary = "Get paginated user list", description = "Retrieves a paginated list of users with optional filtering")
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<UserManagementSummaryResponse>>> getUsers(
            @ParameterObject
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) RoleCode role,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) Boolean emailVerified) {
        return ResponseEntity.ok(ApiResponse.ok(userManagementService.getUsers(
                pageable, search, role, active, emailVerified)));
    }

    @Operation(summary = "Get user details by ID", description = "Retrieves detailed information of a user by UUID")
    @GetMapping("/{userId}")
    public ResponseEntity<ApiResponse<UserManagementDetailResponse>> getUser(
            @PathVariable String userId) {
        return ResponseEntity.ok(ApiResponse.ok(userManagementService.getUser(userId)));
    }

    @Operation(summary = "Update user status", description = "Activates or deactivates a user account")
    @PatchMapping("/{userId}/status")
    public ResponseEntity<ApiResponse<UserManagementDetailResponse>> updateStatus(
            @PathVariable String userId,
            @Valid @RequestBody UserStatusUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                "Account status updated successfully",
                userManagementService.updateStatus(userId, request.getActive())));
    }
}
