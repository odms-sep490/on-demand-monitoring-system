package com.ondemandmonitoring.ai.repository;

import com.ondemandmonitoring.ai.domain.ChecklistImageAiSettings;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ChecklistImageAiSettingsRepository extends JpaRepository<ChecklistImageAiSettings, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ChecklistImageAiSettings s where s.id = :id")
    Optional<ChecklistImageAiSettings> findByIdForUpdate(@Param("id") String id);
}
