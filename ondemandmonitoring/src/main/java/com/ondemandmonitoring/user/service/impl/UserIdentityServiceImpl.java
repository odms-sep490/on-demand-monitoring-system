package com.ondemandmonitoring.user.service.impl;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import com.ondemandmonitoring.user.enumeration.IdentityProvider;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.domain.UserIdentity;
import com.ondemandmonitoring.user.repository.UserIdentityRepository;
import com.ondemandmonitoring.user.service.IUserIdentityService;
import com.ondemandmonitoring.user.service.IdentityLinkLock;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class UserIdentityServiceImpl implements IUserIdentityService {

    UserIdentityRepository identityRepository;
    IdentityLinkLock identityLinkLock;

    @Override
    @Transactional
    public UserIdentity create(User user, IdentityProvider provider,
                               String cognitoUsername, String cognitoSub) {
        identityLinkLock.lock(user.getId(), cognitoSub, cognitoUsername);
        ensureOwnership(user, cognitoSub, cognitoUsername);
        if (identityRepository.existsByCognitoSubAndProvider(cognitoSub, provider)) {
            throw new ApiException(ErrorCode.RESOURCE_ALREADY_EXISTS,
                    "Cognito identity is already linked to an account");
        }
        return identityRepository.saveAndFlush(UserIdentity.builder()
                .user(user)
                .provider(provider)
                .cognitoUsername(cognitoUsername)
                .cognitoSub(cognitoSub)
                .build());
    }

    @Override
    @Transactional
    public UserIdentity link(User user, IdentityProvider provider,
                             String cognitoUsername, String cognitoSub) {
        identityLinkLock.lock(user.getId(), cognitoSub, cognitoUsername);
        ensureOwnership(user, cognitoSub, cognitoUsername);
        return identityRepository.findByUserIdAndProvider(user.getId(), provider)
                .map(existing -> {
                    if (!existing.getCognitoSub().equals(cognitoSub)
                            || !existing.getCognitoUsername().equals(cognitoUsername)) {
                        throw new ApiException(ErrorCode.RESOURCE_ALREADY_EXISTS,
                                "An identity for this provider is already linked to this account");
                    }
                    return existing;
                })
                .orElseGet(() -> create(user, provider, cognitoUsername, cognitoSub));
    }

    @Override
    @Transactional
    public void unlink(User user, IdentityProvider provider) {
        identityRepository.findByUserIdAndProvider(user.getId(), provider)
                .ifPresent(identity -> {
                    identityLinkLock.lock(user.getId(), identity.getCognitoSub(), identity.getCognitoUsername());
                    identityRepository.delete(identity);
                });
    }

    @Override
    @Transactional(readOnly = true)
    public String getUsername(String userId, IdentityProvider provider) {
        return identityRepository.findByUserIdAndProvider(userId, provider)
                .map(UserIdentity::getCognitoUsername)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND,
                        "Authentication identity is not linked to this account"));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasIdentity(String userId, IdentityProvider provider) {
        return identityRepository.findByUserIdAndProvider(userId, provider).isPresent();
    }

    @Override
    @Transactional(readOnly = true)
    public User findUserByCognitoSub(String cognitoSub) {
        return resolveUniqueUser(identityRepository.findAllByCognitoSub(cognitoSub));
    }

    @Override
    @Transactional(readOnly = true)
    public User findUserByCognitoUsername(String cognitoUsername) {
        return resolveUniqueUser(identityRepository.findAllByCognitoUsername(cognitoUsername));
    }

    private User resolveUniqueUser(List<UserIdentity> identities) {
        if (identities.isEmpty()) {
            throw new ApiException(ErrorCode.USER_NOT_FOUND);
        }
        User user = identities.getFirst().getUser();
        if (identities.stream().anyMatch(identity -> !user.getId().equals(identity.getUser().getId()))) {
            throw new ApiException(ErrorCode.IDENTITY_OWNERSHIP_CONFLICT);
        }
        return user;
    }

    private void ensureOwnership(User user, String cognitoSub, String cognitoUsername) {
        if (java.util.stream.Stream.concat(
                        identityRepository.findAllByCognitoSub(cognitoSub).stream(),
                        identityRepository.findAllByCognitoUsername(cognitoUsername).stream())
                .anyMatch(identity -> !user.getId().equals(identity.getUser().getId()))) {
            throw new ApiException(ErrorCode.IDENTITY_OWNERSHIP_CONFLICT);
        }
    }
}
