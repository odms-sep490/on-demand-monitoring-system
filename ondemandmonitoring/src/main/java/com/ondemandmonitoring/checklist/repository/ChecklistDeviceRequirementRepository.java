package com.ondemandmonitoring.checklist.repository;

import com.ondemandmonitoring.checklist.domain.ChecklistDeviceRequirement;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChecklistDeviceRequirementRepository
        extends JpaRepository<ChecklistDeviceRequirement, String> {

    boolean existsByChecklistDefinitionIdAndDeviceTypeId(String checklistId, String deviceTypeId);

    Optional<ChecklistDeviceRequirement> findByChecklistDefinitionIdAndDeviceTypeId(
            String checklistId, String deviceTypeId);

    @EntityGraph(attributePaths = {"deviceType"})
    @Query("select requirement from ChecklistDeviceRequirement requirement "
            + "where requirement.checklistDefinition.id in :checklistIds "
            + "order by requirement.checklistDefinition.id, requirement.deviceType.name, requirement.deviceType.id")
    List<ChecklistDeviceRequirement> findAllByChecklistIds(
            @Param("checklistIds") Collection<String> checklistIds);
}
