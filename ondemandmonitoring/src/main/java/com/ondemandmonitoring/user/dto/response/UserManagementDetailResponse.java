package com.ondemandmonitoring.user.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.enumeration.IdentityProvider;
import java.time.OffsetDateTime;
import java.util.List;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserManagementDetailResponse {

    private String id;

    private String fullName;

    private String email;

    private RoleCode role;

    private boolean active;

    private boolean emailVerified;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;

    private OffsetDateTime lastLoginAt;

    private List<IdentityProvider> linkedProviders;

    private CustomerProfileResponse customerProfile;
}
