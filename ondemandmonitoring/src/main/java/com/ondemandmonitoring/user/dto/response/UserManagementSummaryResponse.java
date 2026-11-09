package com.ondemandmonitoring.user.dto.response;

import com.ondemandmonitoring.role.enumeration.RoleCode;
import java.time.OffsetDateTime;

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
public class UserManagementSummaryResponse {

    private String id;

    private String fullName;

    private String email;

    private RoleCode role;

    private boolean active;

    private boolean emailVerified;

    private OffsetDateTime createdAt;

    private OffsetDateTime lastLoginAt;
}
