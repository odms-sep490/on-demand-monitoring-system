package com.ondemandmonitoring.device.maintenance.repository;

import com.ondemandmonitoring.device.maintenance.domain.MaintenanceAcceptanceDecision;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MaintenanceAcceptanceDecisionRepository extends JpaRepository<MaintenanceAcceptanceDecision, String> {

    List<MaintenanceAcceptanceDecision> findByTicket_IdOrderByDecidedAtDesc(String ticketId);

    Optional<MaintenanceAcceptanceDecision> findByIdempotencyKey(String idempotencyKey);

    List<MaintenanceAcceptanceDecision> findByTicket_AssignedStaff_IdOrderByDecidedAtDesc(
            String staffId,
            Pageable pageable);
}
