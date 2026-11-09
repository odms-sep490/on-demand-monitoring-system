package com.ondemandmonitoring.user.repository;

import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.user.domain.User;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

public final class UserManagementSpecifications {

    private UserManagementSpecifications() {
        /* This utility class should not be instantiated */
    }


    public static Specification<User> filter(
            String search, RoleCode role, Boolean active, Boolean emailVerified) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (search != null && !search.isBlank()) {
                String pattern = "%" + escapeLike(search.trim().toLowerCase(Locale.ROOT)) + "%";
                predicates.add(criteriaBuilder.or(
                        criteriaBuilder.like(criteriaBuilder.lower(root.get("fullName")), pattern, '\\'),
                        criteriaBuilder.like(criteriaBuilder.lower(root.get("email")), pattern, '\\')));
            }
            if (role != null) {
                predicates.add(criteriaBuilder.equal(root.join("role").get("code"), role));
            }
            if (active != null) {
                predicates.add(criteriaBuilder.equal(root.get("isActive"), active));
            }
            if (emailVerified != null) {
                predicates.add(criteriaBuilder.equal(root.get("emailVerified"), emailVerified));
            }

            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
