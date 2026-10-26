package com.ondemandmonitoring.auth.dto.request;

import com.ondemandmonitoring.auth.enumeration.AuthProvider;
import com.ondemandmonitoring.auth.enumeration.SocialAuthIntent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;


@Data
public class SocialSyncRequest {

    @NotBlank(message = "code is required")
    private String code;

    @NotBlank(message = "redirectUri is required")
    private String redirectUri;

    private SocialAuthIntent intent = SocialAuthIntent.LOGIN_OR_REGISTER;

    @NotNull(message = "provider is required")
    private AuthProvider provider;

    private boolean rememberMe;

}
