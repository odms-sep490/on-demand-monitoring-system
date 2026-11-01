package com.ondemandmonitoring.user.repository;

import com.ondemandmonitoring.user.enumeration.IdentityProvider;
import com.ondemandmonitoring.user.domain.UserIdentity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, String> {

    Optional<UserIdentity> findByUserIdAndProvider(String userId, IdentityProvider provider);

    @EntityGraph(attributePaths = {"user", "user.role"})
    Optional<UserIdentity> findByCognitoSub(String cognitoSub);

    @EntityGraph(attributePaths = {"user"})
    List<UserIdentity> findAllByCognitoSubIn(List<String> cognitoSubs);

    @EntityGraph(attributePaths = {"user", "user.role"})
    Optional<UserIdentity> findByCognitoUsername(String cognitoUsername);

    boolean existsByCognitoSubAndProvider(String cognitoSub, IdentityProvider provider);

    List<UserIdentity> findAllByUserId(String userId);
}
