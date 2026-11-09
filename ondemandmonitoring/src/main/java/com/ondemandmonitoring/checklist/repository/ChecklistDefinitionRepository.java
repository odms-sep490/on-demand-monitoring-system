package com.ondemandmonitoring.checklist.repository;

import com.ondemandmonitoring.checklist.domain.ChecklistDefinition;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ChecklistDefinitionRepository extends JpaRepository<ChecklistDefinition, String>,
        JpaSpecificationExecutor<ChecklistDefinition> {
    boolean existsByNormalizedContent(String normalizedContent);
    Optional<ChecklistDefinition> findByNormalizedContent(String normalizedContent);
    Optional<ChecklistDefinition> findBySeedCode(String seedCode);
    boolean existsByNormalizedContentAndIdNot(String normalizedContent, String id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ChecklistDefinition c where c.id = :id")
    Optional<ChecklistDefinition> findByIdForUpdate(@Param("id") String id);
}
