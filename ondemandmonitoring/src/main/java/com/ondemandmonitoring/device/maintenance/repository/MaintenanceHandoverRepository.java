package com.ondemandmonitoring.device.maintenance.repository;

import com.ondemandmonitoring.device.maintenance.domain.MaintenanceHandover;
import com.ondemandmonitoring.device.maintenance.enums.HandoverDirection;
import com.ondemandmonitoring.device.maintenance.enums.HandoverStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MaintenanceHandoverRepository extends JpaRepository<MaintenanceHandover, String> {

    List<MaintenanceHandover> findByTicket_IdOrderByRoundNumberDescRevisionDescCreatedAtDesc(String ticketId);

    Optional<MaintenanceHandover> findFirstByTicket_IdAndDirectionOrderByRoundNumberDescRevisionDescCreatedAtDesc(
            String ticketId,
            HandoverDirection direction);

    Optional<MaintenanceHandover> findFirstByTicket_IdAndDirectionAndStatusOrderByRoundNumberDescRevisionDescCreatedAtDesc(
            String ticketId,
            HandoverDirection direction,
            HandoverStatus status);

    Optional<MaintenanceHandover> findByIdempotencyKey(String idempotencyKey);

    List<MaintenanceHandover> findByToUser_IdAndDirectionAndStatusOrderBySubmittedAtDesc(
            String toUserId,
            HandoverDirection direction,
            HandoverStatus status,
            Pageable pageable);
}
