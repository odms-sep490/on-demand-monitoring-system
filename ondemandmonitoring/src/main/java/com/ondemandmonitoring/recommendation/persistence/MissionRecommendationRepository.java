package com.ondemandmonitoring.recommendation.persistence;

import com.ondemandmonitoring.recommendation.domain.MissionRecommendation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MissionRecommendationRepository extends JpaRepository<MissionRecommendation, String> {
}
