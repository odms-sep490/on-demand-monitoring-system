package com.ondemandmonitoring.auth.dto.response;

import com.ondemandmonitoring.role.enumeration.RoleCode;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class AuthenticatedUserResponse {

    private UUID id;

    private String fullName;

    private String email;

    private RoleCode role;
}
