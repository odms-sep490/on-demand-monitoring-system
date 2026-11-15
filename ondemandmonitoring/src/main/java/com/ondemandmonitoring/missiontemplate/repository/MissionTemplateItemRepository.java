package com.ondemandmonitoring.missiontemplate.repository;

import com.ondemandmonitoring.missiontemplate.domain.MissionTemplateItem;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MissionTemplateItemRepository extends JpaRepository<MissionTemplateItem, String> {
    @EntityGraph(attributePaths = {"missionTemplate", "checklistDefinition"})
    List<MissionTemplateItem> findAllByMissionTemplateIdIn(Collection<String> templateIds);
    List<MissionTemplateItem> findAllByMissionTemplateId(String templateId);

    @Query("select count(item) from MissionTemplateItem item "
            + "where item.missionTemplate.service.id = :serviceId "
            + "and item.missionTemplate.id <> :templateId "
            + "and item.checklistDefinition.id in :checklistIds")
    long countOverlappingChecklistsExcludingTemplate(
            @Param("serviceId") String serviceId,
            @Param("templateId") String templateId,
            @Param("checklistIds") Collection<String> checklistIds);
}
