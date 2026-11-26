package com.ondemandmonitoring.device.maintenance.repository;

import com.ondemandmonitoring.device.maintenance.domain.MaintenanceEvidence;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MaintenanceEvidenceRepository extends JpaRepository<MaintenanceEvidence, String> {

    List<MaintenanceEvidence> findByTicket_IdOrderByUploadedAtDesc(String ticketId);

    Optional<MaintenanceEvidence> findByIdAndTicket_Id(String id, String ticketId);
}
