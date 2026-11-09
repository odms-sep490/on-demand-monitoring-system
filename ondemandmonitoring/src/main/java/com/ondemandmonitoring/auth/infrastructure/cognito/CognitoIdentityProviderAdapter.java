package com.ondemandmonitoring.auth.infrastructure.cognito;

import com.ondemandmonitoring.auth.dto.request.RegisterRequest;
import com.ondemandmonitoring.auth.dto.request.CreateManagedAccountRequest;
import com.ondemandmonitoring.auth.port.out.AuthenticationTokens;
import com.ondemandmonitoring.auth.port.out.IdentityProviderPort;
import com.ondemandmonitoring.auth.port.out.ManagedIdentity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;

import java.util.Map;
import com.ondemandmonitoring.role.enumeration.RoleCode;

/** Cognito adapter. The auth module depends on the port, never on this adapter. */
@Component
@RequiredArgsConstructor
public class CognitoIdentityProviderAdapter implements IdentityProviderPort {

    private final CognitoIdentityProviderClient client;
    private final CognitoProperties properties;

    @Override
    public String signUp(RegisterRequest request) {
        SignUpResponse response = client.signUp(SignUpRequest.builder()
                .clientId(properties.clientId())
                .secretHash(secretHash(request.getEmail()))
                .username(request.getEmail())
                .password(request.getPassword())
                .userAttributes(
                        AttributeType.builder().name("email").value(request.getEmail()).build(),
                        AttributeType.builder().name("name").value(request.getFullName()).build())
                .build());
        return response.userSub();
    }

    @Override
    public ManagedIdentity createInvitedUser(CreateManagedAccountRequest request) {
        AdminCreateUserResponse response = client.adminCreateUser(AdminCreateUserRequest.builder()
                .userPoolId(properties.userPoolId())
                .username(request.getEmail())
                .userAttributes(
                        AttributeType.builder().name("email").value(request.getEmail()).build(),
                        AttributeType.builder().name("email_verified").value("true").build(),
                        AttributeType.builder().name("name").value(request.getFullName()).build())
                .desiredDeliveryMediums(DeliveryMediumType.EMAIL)
        // MessageAction is intentionally omitted: Cognito sends the invitation email.
                .build());

        UserType user = response.user();
        return new ManagedIdentity(user.username(), attribute(user, "sub"));
    }

    @Override
    public void confirmSignUp(String username, String otpCode) {
        client.confirmSignUp(ConfirmSignUpRequest.builder()
                .clientId(properties.clientId())
                .secretHash(secretHash(username))
                .username(username)
                .confirmationCode(otpCode)
                .build());
    }

    @Override
    public void resendConfirmationCode(String username) {
        client.resendConfirmationCode(ResendConfirmationCodeRequest.builder()
                .clientId(properties.clientId())
                .secretHash(secretHash(username))
                .username(username)
                .build());
    }

    @Override
    public AuthenticationTokens authenticate(String username, String password) {
        AdminInitiateAuthResponse response = client.adminInitiateAuth(AdminInitiateAuthRequest.builder()
                .userPoolId(properties.userPoolId())
                .clientId(properties.clientId())
                .authFlow(AuthFlowType.ADMIN_USER_PASSWORD_AUTH)
                .authParameters(Map.of(
                        "USERNAME", username,
                        "PASSWORD", password,
                        "SECRET_HASH", secretHash(username)))
                .build());
        if (response.challengeName() != null && response.session() != null) {
            return AuthenticationTokens.challenge(username, response.challengeNameAsString(), response.session());
        }
        return tokens(response.authenticationResult(), username);
    }

    @Override
    public AuthenticationTokens respondToNewPasswordChallenge(String username, String session, String newPassword) {
        AdminRespondToAuthChallengeResponse response = client.adminRespondToAuthChallenge(
                AdminRespondToAuthChallengeRequest.builder()
                        .userPoolId(properties.userPoolId())
                        .clientId(properties.clientId())
                        .challengeName(ChallengeNameType.NEW_PASSWORD_REQUIRED)
                        .session(session)
                        .challengeResponses(Map.of(
                                "USERNAME", username,
                                "NEW_PASSWORD", newPassword,
                                "SECRET_HASH", secretHash(username)))
                        .build());
        return tokens(response.authenticationResult(), username);
    }

    @Override
    public AuthenticationTokens refresh(String refreshToken, String username) {
        AuthenticationResultType result = client.initiateAuth(InitiateAuthRequest.builder()
                .clientId(properties.clientId())
                .authFlow(AuthFlowType.REFRESH_TOKEN_AUTH)
                .authParameters(Map.of(
                        "USERNAME", username,
                        "REFRESH_TOKEN", refreshToken,
                        "SECRET_HASH", secretHash(username)))
                .build()).authenticationResult();
        return tokens(result, username);
    }

    @Override
    public void forgotPassword(String username) {
        client.forgotPassword(ForgotPasswordRequest.builder()
                .clientId(properties.clientId())
                .secretHash(secretHash(username))
                .username(username)
                .build());
    }

    @Override
    public void resetPassword(String username, String otpCode, String newPassword) {
        client.confirmForgotPassword(ConfirmForgotPasswordRequest.builder()
                .clientId(properties.clientId())
                .secretHash(secretHash(username))
                .username(username)
                .confirmationCode(otpCode)
                .password(newPassword)
                .build());
    }

    @Override
    public void revoke(String refreshToken) {
        client.revokeToken(RevokeTokenRequest.builder()
                .clientId(properties.clientId())
                .clientSecret(properties.clientSecret())
                .token(refreshToken)
                .build());
    }

    @Override
    public void globalSignOut(String accessToken) {
        client.globalSignOut(GlobalSignOutRequest.builder()
                .accessToken(accessToken)
                .build());
    }

    @Override
    public void addUserToGroup(String username, String role) {
        RoleCode.valueOf(role);
        client.adminAddUserToGroup(AdminAddUserToGroupRequest.builder()
                .userPoolId(properties.userPoolId())
                .username(username)
                .groupName(role)
                .build());
    }

    @Override
    public void setPermanentPassword(String username, String password) {
        client.adminSetUserPassword(AdminSetUserPasswordRequest.builder()
                .userPoolId(properties.userPoolId())
                .username(username)
                .password(password)
                .permanent(true)
                .build());
    }

    @Override
    public void deleteUser(String username) {
        client.adminDeleteUser(AdminDeleteUserRequest.builder()
                .userPoolId(properties.userPoolId())
                .username(username)
                .build());
    }

    @Override
    public void enableUser(String username) {
        client.adminEnableUser(AdminEnableUserRequest.builder()
                .userPoolId(properties.userPoolId())
                .username(username)
                .build());
    }

    @Override
    public void disableUser(String username) {
        client.adminDisableUser(AdminDisableUserRequest.builder()
                .userPoolId(properties.userPoolId())
                .username(username)
                .build());
    }

    private AuthenticationTokens tokens(AuthenticationResultType result, String username) {
        return new AuthenticationTokens(result.accessToken(), result.refreshToken(), result.expiresIn(), username);
    }

    private String attribute(UserType user, String name) {
        return user.attributes().stream()
                .filter(attribute -> name.equals(attribute.name()))
                .map(AttributeType::value)
                .findFirst()
                .orElse(null);
    }

    private String secretHash(String username) {
        return CognitoSecretHash.calculate(username, properties.clientId(), properties.clientSecret());
    }
}
