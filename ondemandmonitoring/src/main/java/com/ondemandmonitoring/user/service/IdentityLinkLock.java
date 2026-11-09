package com.ondemandmonitoring.user.service;

import com.ondemandmonitoring.common.exception.ApiException;
import com.ondemandmonitoring.common.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.stream.Stream;

/** Serializes identity claims even when no identity row exists yet. */
@Component
@RequiredArgsConstructor
public class IdentityLinkLock {
    private final EntityManager entityManager;

    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(String userId, String cognitoSub, String cognitoUsername) {
        if (Stream.of(userId, cognitoSub, cognitoUsername)
                .anyMatch(value -> value == null || value.isBlank())) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        // A single acquisition order avoids deadlocks. Transaction end releases the locks.
        Stream.of("identity:sub:" + cognitoSub, "identity:username:" + cognitoUsername,
                        "identity:user:" + userId)
                .sorted()
                .forEach(key -> entityManager.createNativeQuery(
                                "select 1 from pg_advisory_xact_lock(hashtextextended(:key, 0))")
                        .setParameter("key", key)
                        .getSingleResult());
    }
}
