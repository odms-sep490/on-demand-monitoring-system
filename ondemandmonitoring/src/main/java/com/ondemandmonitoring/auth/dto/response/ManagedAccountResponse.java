package com.ondemandmonitoring.auth.dto.response;

import com.ondemandmonitoring.role.enumeration.RoleCode;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ManagedAccountResponse {

    private String email;
    private RoleCode role;
    private boolean invitationSent;
    private boolean passwordChangeRequired;
}
