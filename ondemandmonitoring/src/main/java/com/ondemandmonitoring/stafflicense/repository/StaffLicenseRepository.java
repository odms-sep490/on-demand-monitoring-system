package com.ondemandmonitoring.stafflicense.repository;

import com.ondemandmonitoring.stafflicense.domain.StaffLicense;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StaffLicenseRepository extends JpaRepository<StaffLicense, String> {
    @EntityGraph(attributePaths = {"user", "user.role", "verifiedBy"})
    List<StaffLicense> findAllByUser_IdOrderByLicenseTypeAscIssuedDateDesc(String userId);

    @EntityGraph(attributePaths = {"user", "user.role", "verifiedBy"})
    Optional<StaffLicense> findWithUserById(String id);

    Optional<StaffLicense> findByUser_IdAndLicenseNumberIgnoreCase(String userId, String licenseNumber);

    boolean existsByUser_IdAndLicenseNumberIgnoreCase(String userId, String licenseNumber);
}
