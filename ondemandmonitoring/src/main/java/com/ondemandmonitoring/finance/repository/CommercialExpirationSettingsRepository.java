package com.ondemandmonitoring.finance.repository;

import com.ondemandmonitoring.finance.domain.CommercialExpirationSettings;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommercialExpirationSettingsRepository extends JpaRepository<CommercialExpirationSettings, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select settings from CommercialExpirationSettings settings where settings.id = :id")
    Optional<CommercialExpirationSettings> findByIdForUpdate(@Param("id") String id);
}
