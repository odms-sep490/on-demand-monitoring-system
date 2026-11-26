package com.ondemandmonitoring.resourcelifecycle.repository;

import com.ondemandmonitoring.resourcelifecycle.entity.ResourcePolicyEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResourcePolicyRepository extends JpaRepository<ResourcePolicyEntity, UUID> {

    List<ResourcePolicyEntity> findByFamilyOrderByUpdatedAtDesc(String family);

    List<ResourcePolicyEntity> findAllByOrderByUpdatedAtDesc();

    Optional<ResourcePolicyEntity> findFirstByFamilyAndTargetIdAndActiveTrue(String family, String targetId);

    List<ResourcePolicyEntity> findByFamilyAndActiveTrue(String family);

    long countByActiveTrue();
}
