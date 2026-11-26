package com.ondemandmonitoring.device.maintenance.repository;

import com.ondemandmonitoring.device.maintenance.domain.MaintenanceWorkItem;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MaintenanceWorkItemRepository extends JpaRepository<MaintenanceWorkItem, String> {

    List<MaintenanceWorkItem> findByTicket_IdOrderByRoundNumberAscCreatedAtAsc(String ticketId);

    List<MaintenanceWorkItem> findByTicket_IdAndRoundNumberOrderByCreatedAtAsc(String ticketId, Integer roundNumber);

    Optional<MaintenanceWorkItem> findByIdAndTicket_Id(String id, String ticketId);
}
