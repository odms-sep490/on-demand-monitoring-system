package com.ondemandmonitoring.resourcelifecycle.repository;

import com.ondemandmonitoring.resourcelifecycle.entity.ResourceAllocationEvaluationEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResourceAllocationEvaluationRepository
        extends JpaRepository<ResourceAllocationEvaluationEntity, UUID> {

    List<ResourceAllocationEvaluationEntity> findByMissionIdOrderByCreatedAtDesc(String missionId);

    List<ResourceAllocationEvaluationEntity> findTop50ByOrderByCreatedAtDesc();
}
