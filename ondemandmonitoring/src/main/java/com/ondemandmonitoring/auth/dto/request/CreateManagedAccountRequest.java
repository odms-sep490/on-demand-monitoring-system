package com.ondemandmonitoring.auth.dto.request;

import com.ondemandmonitoring.role.enumeration.RoleCode;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
public class CreateManagedAccountRequest {

    @NotBlank
    @Email
    String email;

    @NotBlank
    @Size(max = 100)
    String fullName;

    @NotNull
    RoleCode role;
}
