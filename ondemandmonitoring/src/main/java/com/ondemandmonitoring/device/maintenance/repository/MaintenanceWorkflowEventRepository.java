package com.ondemandmonitoring.device.maintenance.repository;

import com.ondemandmonitoring.device.maintenance.domain.MaintenanceWorkflowEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MaintenanceWorkflowEventRepository extends JpaRepository<MaintenanceWorkflowEvent, String> {

    List<MaintenanceWorkflowEvent> findByTicket_IdOrderByOccurredAtAsc(String ticketId);
}
