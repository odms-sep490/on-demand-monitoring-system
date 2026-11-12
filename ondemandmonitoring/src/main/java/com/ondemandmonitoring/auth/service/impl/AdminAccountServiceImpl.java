package com.ondemandmonitoring.auth.service.impl;

import com.ondemandmonitoring.auth.dto.request.CreateManagedAccountRequest;
import com.ondemandmonitoring.auth.dto.response.ManagedAccountResponse;
import com.ondemandmonitoring.auth.port.out.AuthCompensationPort;
import com.ondemandmonitoring.auth.port.out.IdentityProviderPort;
import com.ondemandmonitoring.auth.port.out.ManagedIdentity;
import com.ondemandmonitoring.auth.service.IAdminAccountService;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.user.service.IUserService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CognitoIdentityProviderException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UsernameExistsException;

import java.util.Locale;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class AdminAccountServiceImpl implements IAdminAccountService {

    IUserService userService;
    IdentityProviderPort identityProvider;
    AuthCompensationPort compensationPort;

    @Override
    @Transactional
    public ManagedAccountResponse create(CreateManagedAccountRequest request) {
        validateManagedRole(request);
        String email = normalizeEmail(request.getEmail());
        request.setEmail(email);

        if (userService.findOptionalByEmail(email).isPresent()) {
            throw new ApiException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        ManagedIdentity identity;
        try {
            identity = identityProvider.createInvitedUser(request);
            try {
                userService.createManagedUser(
                        email, request.getFullName(), request.getRole(),
                        identity.username(), identity.subject());
                identityProvider.addUserToGroup(identity.username(), request.getRole().name());
            } catch (RuntimeException exception) {
                compensationPort.scheduleCognitoCleanup(identity.username(), identity.subject());
                throw exception;
            }
        } catch (UsernameExistsException exception) {
            throw new ApiException(ErrorCode.EMAIL_ALREADY_EXISTS);
        } catch (CognitoIdentityProviderException exception) {
            throw providerError(exception);
        }

        return ManagedAccountResponse.builder()
                .email(email)
                .role(request.getRole())
                .invitationSent(true)
                .passwordChangeRequired(true)
                .build();
    }

    private void validateManagedRole(CreateManagedAccountRequest request) {
        if (request.getRole() == null || !request.getRole().isEmployeeRole()) {
            throw new ApiException(ErrorCode.ACCESS_DENIED,
                    "This endpoint is only available for employee roles");
        }
    }

    private ApiException providerError(CognitoIdentityProviderException exception) {
        String message = exception.awsErrorDetails() == null
                ? exception.getMessage() : exception.awsErrorDetails().errorMessage();
        return new ApiException(ErrorCode.AUTH_PROVIDER_ERROR, message);
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
