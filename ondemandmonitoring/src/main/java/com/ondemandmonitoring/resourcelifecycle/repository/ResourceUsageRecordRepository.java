package com.ondemandmonitoring.resourcelifecycle.repository;

import com.ondemandmonitoring.resourcelifecycle.entity.ResourceUsageRecordEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResourceUsageRecordRepository extends JpaRepository<ResourceUsageRecordEntity, UUID> {

    Optional<ResourceUsageRecordEntity> findByIdempotencyKey(String idempotencyKey);

    List<ResourceUsageRecordEntity> findByResourceKindAndResourceIdOrderByStartedAtDesc(
            String resourceKind, String resourceId);

    List<ResourceUsageRecordEntity> findByResourceKindAndResourceIdAndStatusOrderByStartedAtAsc(
            String resourceKind, String resourceId, String status);

    List<ResourceUsageRecordEntity> findByStatusOrderByCreatedAtDesc(String status);

    List<ResourceUsageRecordEntity> findTop100ByOrderByCreatedAtDesc();
}
