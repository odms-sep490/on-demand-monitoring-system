package com.ondemandmonitoring.user.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.enumeration.IdentityProvider;
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
public class UserProfileResponse {

    String id;
    String fullName;
    String email;
    RoleCode role;
    List<IdentityProvider> linkedProviders;
    String avatarUrl;
    CustomerProfileResponse customerProfile;
}
