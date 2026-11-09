package com.ondemandmonitoring.auth.infrastructure.cognito;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class CognitoRoleSynchronizationTest {
    private final CognitoIdentityProviderClient client = mock(CognitoIdentityProviderClient.class);
    private final CognitoIdentityProviderAdapter adapter = new CognitoIdentityProviderAdapter(client,
            new CognitoProperties("ap-southeast-1", "pool", "client", null, "https://example.com"));

    @Test
    void addsRequestedRoleWithoutListingOrRemovingExistingGroups() {
        adapter.addUserToGroup("canonical-username", "MANAGER");

        verify(client).adminAddUserToGroup(argThat((AdminAddUserToGroupRequest request) ->
                request.groupName().equals("MANAGER") && request.username().equals("canonical-username")
                        && request.userPoolId().equals("pool")));
        verifyNoMoreInteractions(client);
    }

    @Test
    void rejectsLegacyRoleBeforeWritingToCognito() {
        assertThatThrownBy(() -> adapter.addUserToGroup("user", "DRONE_OPERATOR"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client);
    }

}
