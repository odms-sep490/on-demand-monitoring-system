package com.ondemandmonitoring.resourcelifecycle.repository;

import com.ondemandmonitoring.resourcelifecycle.entity.ResourceLifecycleAuditEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResourceLifecycleAuditRepository extends JpaRepository<ResourceLifecycleAuditEntity, UUID> {

    List<ResourceLifecycleAuditEntity> findTop100ByOrderByCreatedAtDesc();

    List<ResourceLifecycleAuditEntity> findByEntityIdOrderByCreatedAtDesc(String entityId);
}
