package com.ondemandmonitoring.weather.repository;

import com.ondemandmonitoring.weather.domain.MissionWeatherCheck;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MissionWeatherCheckRepository extends JpaRepository<MissionWeatherCheck, String> {
    List<MissionWeatherCheck> findByMissionIdOrderByCreatedAtDesc(String missionId);

    Optional<MissionWeatherCheck> findFirstByMissionIdOrderByCreatedAtDesc(String missionId);
}
