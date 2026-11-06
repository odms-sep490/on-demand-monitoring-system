package com.ondemandmonitoring.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.*;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.domain.UserIdentity;
import com.ondemandmonitoring.user.repository.UserIdentityRepository;
import com.ondemandmonitoring.user.service.impl.UserIdentityServiceImpl;
import java.util.List;
import com.ondemandmonitoring.user.service.IdentityLinkLock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserIdentityServiceImplTest {

    @Mock
    private UserIdentityRepository identityRepository;

    @Mock
    private IdentityLinkLock identityLinkLock;

    @InjectMocks
    private UserIdentityServiceImpl identityService;

    @Test
    void findUserByCognitoUsername_returnsLinkedUser() {
        User expected = User.builder().build();
        expected.setId(UUID.randomUUID().toString());
        when(identityRepository.findAllByCognitoUsername("cognito-user"))
                .thenReturn(List.of(UserIdentity.builder().user(expected).build()));

        User actual = identityService.findUserByCognitoUsername("cognito-user");

        assertThat(actual).isSameAs(expected);
    }

    @Test
    void findUserByCognitoUsername_whenMissing_throwsUserNotFound() {
        when(identityRepository.findAllByCognitoUsername("missing-user"))
                .thenReturn(List.of());

        assertThatThrownBy(() -> identityService.findUserByCognitoUsername("missing-user"))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.USER_NOT_FOUND));
    }

    @Test
    void googleAndLocalWithSameSubResolveToOneUser() {
        User user = user();
        when(identityRepository.findAllByCognitoSub("sub"))
                .thenReturn(List.of(identity(user), identity(user)));
        assertThat(identityService.findUserByCognitoSub("sub")).isSameAs(user);
    }

    @Test
    void googleAndLocalWithSameUsernameResolveToOneUser() {
        User user = user();
        when(identityRepository.findAllByCognitoUsername("username"))
                .thenReturn(List.of(identity(user), identity(user)));
        assertThat(identityService.findUserByCognitoUsername("username")).isSameAs(user);
    }

    @Test
    void missingSubFailsWithUserNotFound() {
        assertError(() -> identityService.findUserByCognitoSub("missing"), ErrorCode.USER_NOT_FOUND);
    }

    @Test
    void subOwnedByDifferentUsersFailsClosed() {
        when(identityRepository.findAllByCognitoSub("sub"))
                .thenReturn(List.of(identity(user()), identity(user())));
        assertError(() -> identityService.findUserByCognitoSub("sub"), ErrorCode.IDENTITY_OWNERSHIP_CONFLICT);
    }

    @Test
    void usernameOwnedByDifferentUsersFailsClosed() {
        when(identityRepository.findAllByCognitoUsername("username"))
                .thenReturn(List.of(identity(user()), identity(user())));
        assertError(() -> identityService.findUserByCognitoUsername("username"), ErrorCode.IDENTITY_OWNERSHIP_CONFLICT);
    }

    @Test
    void createRejectsSubOwnedByAnotherUserBeforeSaving() {
        User owner = user();
        when(identityRepository.findAllByCognitoSub("sub")).thenReturn(List.of(identity(owner)));
        User claimant = user();
        assertError(() -> identityService.create(claimant,
                com.ondemandmonitoring.user.enumeration.IdentityProvider.LOCAL, "username", "sub"),
                ErrorCode.IDENTITY_OWNERSHIP_CONFLICT);
        verify(identityLinkLock).lock(claimant.getId(), "sub", "username");
        verify(identityRepository, never()).saveAndFlush(any());
    }

    @Test
    void linkRejectsUsernameOwnedByAnotherUser() {
        when(identityRepository.findAllByCognitoUsername("username")).thenReturn(List.of(identity(user())));
        assertError(() -> identityService.link(user(),
                com.ondemandmonitoring.user.enumeration.IdentityProvider.LOCAL, "username", "sub"),
                ErrorCode.IDENTITY_OWNERSHIP_CONFLICT);
        verify(identityRepository, never()).saveAndFlush(any());
    }

    private static User user() {
        User user = User.builder().build();
        user.setId(UUID.randomUUID().toString());
        return user;
    }

    private static UserIdentity identity(User user) {
        return UserIdentity.builder().user(user).build();
    }

    private static void assertError(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}
