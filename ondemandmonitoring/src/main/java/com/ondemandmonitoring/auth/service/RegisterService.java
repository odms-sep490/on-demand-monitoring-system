package com.ondemandmonitoring.auth.service;

import com.ondemandmonitoring.auth.dto.request.RegisterRequest;
import com.ondemandmonitoring.auth.dto.request.ResendOtpRequest;
import com.ondemandmonitoring.auth.dto.request.VerifyOtpRequest;
import com.ondemandmonitoring.auth.dto.response.RegisterResponse;
import com.ondemandmonitoring.auth.port.out.IdentityProviderPort;
import com.ondemandmonitoring.auth.port.out.AuthCompensationPort;
import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.user.service.IUserService;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.enumeration.IdentityProvider;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CognitoIdentityProviderException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CodeMismatchException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ExpiredCodeException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InvalidPasswordException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.NotAuthorizedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UsernameExistsException;

import java.util.Locale;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class RegisterService {

    IUserService userService;
    IdentityProviderPort identityProvider;
    AuthCompensationPort compensationPort;

    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.getEmail());
        request.setEmail(email);

        ensureEmailCanBeRegistered(email);

        try {
            String cognitoSub = identityProvider.signUp(request);
            try {
                userService.createLocalUser(
                        email,
                        request.getFullName(),
                        RoleCode.CUSTOMER,
                        cognitoSub,
                        cognitoSub);
                identityProvider.addUserToGroup(cognitoSub, RoleCode.CUSTOMER.name());
            } catch (RuntimeException exception) {
                compensationPort.scheduleCognitoCleanup(cognitoSub, cognitoSub);
                throw exception;
            }
            return RegisterResponse.builder().otpRequired(true).build();
        } catch (UsernameExistsException exception) {
            throw new ApiException(ErrorCode.EMAIL_ALREADY_EXISTS);
        } catch (InvalidPasswordException exception) {
            throw new ApiException(ErrorCode.PASSWORD_POLICY_VIOLATED);
        } catch (CognitoIdentityProviderException exception) {
            throw providerError(exception);
        }
    }

    private void ensureEmailCanBeRegistered(String email) {
        userService.findOptionalByEmail(email).ifPresent(existingUser -> {
            boolean hasGoogleIdentity = userService.hasIdentity(
                    existingUser.getId(), IdentityProvider.GOOGLE);
            boolean hasLocalIdentity = userService.hasIdentity(
                    existingUser.getId(), IdentityProvider.LOCAL);

            if (hasGoogleIdentity && !hasLocalIdentity) {
                throw new ApiException(ErrorCode.LOCAL_IDENTITY_LINK_REQUIRED);
            }
            throw new ApiException(ErrorCode.EMAIL_ALREADY_EXISTS);
        });
    }

    public void verifyOtp(VerifyOtpRequest request) {
        String email = normalizeEmail(request.getEmail());
        String username = userService.getCognitoUsername(email, IdentityProvider.LOCAL);
        try {
            identityProvider.confirmSignUp(username, request.getOtpCode());
            userService.markEmailVerified(email);
        } catch (CodeMismatchException exception) {
            throw new ApiException(ErrorCode.OTP_INVALID);
        } catch (ExpiredCodeException exception) {
            throw new ApiException(ErrorCode.OTP_EXPIRED);
        } catch (NotAuthorizedException exception) {
            throw new ApiException(ErrorCode.USER_ALREADY_CONFIRMED);
        } catch (UserNotFoundException exception) {
            throw new ApiException(ErrorCode.USER_NOT_FOUND);
        } catch (CognitoIdentityProviderException exception) {
            throw providerError(exception);
        }
    }

    public void resendOtp(ResendOtpRequest request) {
        String email = normalizeEmail(request.getEmail());
        String username = userService.getCognitoUsername(email, IdentityProvider.LOCAL);
        try {
            identityProvider.resendConfirmationCode(username);
        } catch (UserNotFoundException exception) {
            throw new ApiException(ErrorCode.USER_NOT_FOUND);
        } catch (NotAuthorizedException exception) {
            throw new ApiException(ErrorCode.USER_ALREADY_CONFIRMED);
        } catch (CognitoIdentityProviderException exception) {
            throw providerError(exception);
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
