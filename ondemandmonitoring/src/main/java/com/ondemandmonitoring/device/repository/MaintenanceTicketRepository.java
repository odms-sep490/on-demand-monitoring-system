package com.ondemandmonitoring.device.repository;

import com.ondemandmonitoring.device.domain.MaintenanceTicket;
import com.ondemandmonitoring.device.maintenance.enums.MaintenanceWorkflowStage;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface MaintenanceTicketRepository extends JpaRepository<MaintenanceTicket, String> {

    /** All tickets for a given device — full fault history per device */
    List<MaintenanceTicket> findByDeviceId(String deviceId);

    /** All tickets assigned to a specific staff user */
    List<MaintenanceTicket> findByAssignedStaffId(java.util.UUID staffId);

    Optional<MaintenanceTicket> findByTicketCode(String ticketCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM MaintenanceTicket t WHERE t.id = :id")
    Optional<MaintenanceTicket> findByIdForUpdate(@Param("id") String id);

    List<MaintenanceTicket> findByAssignedStaff_IdAndWorkflowStageInOrderByWorkflowUpdatedAtDesc(
            String staffId,
            Collection<MaintenanceWorkflowStage> stages);

    List<MaintenanceTicket> findByReceivingManager_IdAndWorkflowStageInOrderByWorkflowUpdatedAtDesc(
            String managerId,
            Collection<MaintenanceWorkflowStage> stages);

    List<MaintenanceTicket> findByWorkflowStageInOrderByWorkflowUpdatedAtDesc(
            Collection<MaintenanceWorkflowStage> stages);

    /** Shared Technical queue: tickets waiting for receipt that nobody has claimed yet. */
    List<MaintenanceTicket> findByAssignedStaffIsNullAndWorkflowStageOrderByWorkflowUpdatedAtAsc(
            MaintenanceWorkflowStage stage);

    long countByAssignedStaff_IdAndWorkflowStageIn(String staffId, Collection<MaintenanceWorkflowStage> stages);

    @Query("""
            SELECT COUNT(t) > 0 FROM MaintenanceTicket t
            WHERE t.device.id = :deviceId
              AND (:excludeTicketId IS NULL OR t.id <> :excludeTicketId)
              AND (
                (t.workflowStage IS NOT NULL AND t.workflowStage NOT IN (
                    com.ondemandmonitoring.device.maintenance.enums.MaintenanceWorkflowStage.COMPLETED,
                    com.ondemandmonitoring.device.maintenance.enums.MaintenanceWorkflowStage.CANCELLED
                ))
                OR (t.workflowStage IS NULL AND UPPER(t.status) <> 'CLOSED')
              )
            """)
    boolean existsBlockingTicketForDevice(
            @Param("deviceId") String deviceId,
            @Param("excludeTicketId") String excludeTicketId);
}


