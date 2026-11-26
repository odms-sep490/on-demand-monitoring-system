package com.ondemandmonitoring.device.maintenance.repository;

import com.ondemandmonitoring.device.maintenance.domain.MaintenanceReportVersion;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MaintenanceReportVersionRepository extends JpaRepository<MaintenanceReportVersion, String> {

    List<MaintenanceReportVersion> findByTicket_IdOrderByRevisionDesc(String ticketId);

    Optional<MaintenanceReportVersion> findFirstByTicket_IdOrderByRevisionDesc(String ticketId);

    Optional<MaintenanceReportVersion> findByTicket_IdAndRevision(String ticketId, Integer revision);
}
