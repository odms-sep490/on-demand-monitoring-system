package com.ondemandmonitoring.resourcelifecycle.repository;

import com.ondemandmonitoring.resourcelifecycle.entity.ResourceHealthAlertEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResourceHealthAlertRepository extends JpaRepository<ResourceHealthAlertEntity, UUID> {

    Optional<ResourceHealthAlertEntity> findByDedupKey(String dedupKey);

    List<ResourceHealthAlertEntity> findByStatusOrderByDetectedAtDesc(String status);

    List<ResourceHealthAlertEntity> findTop100ByOrderByDetectedAtDesc();

    List<ResourceHealthAlertEntity> findByResourceIdOrderByDetectedAtDesc(String resourceId);

    long countByStatus(String status);
}
