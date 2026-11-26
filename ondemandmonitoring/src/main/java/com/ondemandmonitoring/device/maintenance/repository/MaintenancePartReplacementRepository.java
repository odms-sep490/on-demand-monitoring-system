package com.ondemandmonitoring.device.maintenance.repository;

import com.ondemandmonitoring.device.maintenance.domain.MaintenancePartReplacement;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MaintenancePartReplacementRepository extends JpaRepository<MaintenancePartReplacement, String> {

    List<MaintenancePartReplacement> findByTicket_IdOrderByRoundNumberAscCreatedAtAsc(String ticketId);

    Optional<MaintenancePartReplacement> findByIdAndTicket_Id(String id, String ticketId);
}
