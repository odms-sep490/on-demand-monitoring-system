package com.ondemandmonitoring.resourcelifecycle.repository;

import com.ondemandmonitoring.resourcelifecycle.entity.ResourcePolicyVersionEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResourcePolicyVersionRepository
        extends JpaRepository<ResourcePolicyVersionEntity, ResourcePolicyVersionEntity.PolicyVersionKey> {

    List<ResourcePolicyVersionEntity> findByPolicyIdOrderByRevisionDesc(UUID policyId);
}
