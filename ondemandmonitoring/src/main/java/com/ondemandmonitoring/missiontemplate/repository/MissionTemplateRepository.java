package com.ondemandmonitoring.missiontemplate.repository;

import com.ondemandmonitoring.missiontemplate.domain.MissionTemplate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MissionTemplateRepository extends JpaRepository<MissionTemplate, String> {
    List<MissionTemplate> findAllByServiceIdOrderByCreatedAtAscIdAsc(String serviceId);
    Optional<MissionTemplate> findByIdAndServiceId(String id, String serviceId);
}
