package com.ondemandmonitoring.resourcelifecycle.repository;

import com.ondemandmonitoring.resourcelifecycle.entity.ResourceSpecificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResourceSpecificationRepository extends JpaRepository<ResourceSpecificationEntity, String> {
}
