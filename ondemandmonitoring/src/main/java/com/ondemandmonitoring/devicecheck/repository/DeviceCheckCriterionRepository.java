package com.ondemandmonitoring.devicecheck.repository;

import com.ondemandmonitoring.devicecheck.domain.DeviceCheckCriterion;
import com.ondemandmonitoring.devicecheck.enums.DeviceCheckPhase;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceCheckCriterionRepository extends JpaRepository<DeviceCheckCriterion, String> {

    List<DeviceCheckCriterion> findByPhaseOrderBySortOrderAscCreatedAtAsc(DeviceCheckPhase phase);

    boolean existsByPhase(DeviceCheckPhase phase);

    boolean existsByPhaseAndCode(DeviceCheckPhase phase, String code);

    long countByPhaseAndActiveTrue(DeviceCheckPhase phase);

    default int nextSortOrder(DeviceCheckPhase phase) {
        return findByPhaseOrderBySortOrderAscCreatedAtAsc(phase).stream()
                .mapToInt(DeviceCheckCriterion::getSortOrder).max().orElse(0) + 1;
    }
}
