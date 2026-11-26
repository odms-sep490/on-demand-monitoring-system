package com.ondemandmonitoring.resourcelifecycle.repository;

import com.ondemandmonitoring.resourcelifecycle.entity.ResourceMaintenanceCycleEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResourceMaintenanceCycleRepository extends JpaRepository<ResourceMaintenanceCycleEntity, UUID> {

    Optional<ResourceMaintenanceCycleEntity> findByDeviceIdAndPolicyIdAndCycle(
            String deviceId, UUID policyId, int cycle);

    Optional<ResourceMaintenanceCycleEntity> findByTicketId(String ticketId);

    List<ResourceMaintenanceCycleEntity> findByDeviceIdOrderByDetectedAtDesc(String deviceId);

    List<ResourceMaintenanceCycleEntity> findByDeviceIdAndPolicyIdOrderByCycleDesc(
            String deviceId, UUID policyId);

    List<ResourceMaintenanceCycleEntity> findByCheckpointAtIsNullOrderByDetectedAtDesc();

    List<ResourceMaintenanceCycleEntity> findTop100ByOrderByDetectedAtDesc();

    boolean existsByDeviceIdAndCheckpointAtIsNull(String deviceId);

    long countByCheckpointAtIsNull();
}
