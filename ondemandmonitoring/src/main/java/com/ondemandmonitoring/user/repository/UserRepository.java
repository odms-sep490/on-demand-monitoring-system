package com.ondemandmonitoring.user.repository;

import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.EntityGraph;

public interface UserRepository extends JpaRepository<User, String>, JpaSpecificationExecutor<User> {

    @Override
    @EntityGraph(attributePaths = "role")
    Optional<User> findById(String id);

    @EntityGraph(attributePaths = "role")
    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    @EntityGraph(attributePaths = "role")
    List<User> findAllByRole_CodeAndIsActiveTrueOrderByFullNameAsc(RoleCode roleCode);
}
