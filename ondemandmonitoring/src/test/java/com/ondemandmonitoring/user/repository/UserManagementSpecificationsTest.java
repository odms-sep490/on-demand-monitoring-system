package com.ondemandmonitoring.user.repository;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.user.domain.User;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.Test;

class UserManagementSpecificationsTest {

    private final Root<User> root = mock(Root.class);
    private final CriteriaQuery<?> query = mock(CriteriaQuery.class);
    private final CriteriaBuilder criteriaBuilder = mock(CriteriaBuilder.class);

    @Test
    void noFiltersDoesNotAddAnyFieldPredicates() {
        UserManagementSpecifications.filter(null, null, null, null)
                .toPredicate(root, query, criteriaBuilder);

        verify(root, never()).get(anyString());
        verify(root, never()).join(anyString());
        verify(criteriaBuilder).and(new jakarta.persistence.criteria.Predicate[0]);
    }

    @Test
    void blankSearchDoesNotAddSearchPredicate() {
        UserManagementSpecifications.filter("   ", null, null, null)
                .toPredicate(root, query, criteriaBuilder);

        verify(root, never()).get(anyString());
    }

    @Test
    void searchIsTrimmedCaseInsensitiveAndEscapesLikeWildcards() {
        Path<String> fullName = mock(Path.class);
        Path<String> email = mock(Path.class);
        Expression<String> loweredName = mock(Expression.class);
        Expression<String> loweredEmail = mock(Expression.class);
        when(root.<String>get("fullName")).thenReturn(fullName);
        when(root.<String>get("email")).thenReturn(email);
        when(criteriaBuilder.lower(fullName)).thenReturn(loweredName);
        when(criteriaBuilder.lower(email)).thenReturn(loweredEmail);

        UserManagementSpecifications.filter("  A_%  ", null, null, null)
                .toPredicate(root, query, criteriaBuilder);

        verify(criteriaBuilder).like(loweredName, "%a\\_\\%%", '\\');
        verify(criteriaBuilder).like(loweredEmail, "%a\\_\\%%", '\\');
    }

    @Test
    void optionalRoleAndBooleanFiltersAreAddedOnlyWhenProvided() {
        Join<User, Role> roleJoin = mock(Join.class);
        Path<RoleCode> roleCode = mock(Path.class);
        Path<Boolean> active = mock(Path.class);
        Path<Boolean> emailVerified = mock(Path.class);
        when(root.<User, Role>join("role")).thenReturn(roleJoin);
        when(roleJoin.<RoleCode>get("code")).thenReturn(roleCode);
        when(root.<Boolean>get("isActive")).thenReturn(active);
        when(root.<Boolean>get("emailVerified")).thenReturn(emailVerified);

        UserManagementSpecifications.filter(null, RoleCode.CUSTOMER, false, true)
                .toPredicate(root, query, criteriaBuilder);

        verify(criteriaBuilder).equal(roleCode, RoleCode.CUSTOMER);
        verify(criteriaBuilder).equal(active, false);
        verify(criteriaBuilder).equal(emailVerified, true);
    }
}
