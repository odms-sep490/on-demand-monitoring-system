package com.ondemandmonitoring.resourcelifecycle.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
        name = "resource_maintenance_cycles",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_resource_maint_cycle_device_policy",
                    columnNames = {"device_id", "policy_id", "cycle"})
        })
@Getter
@Setter
@NoArgsConstructor
public class ResourceMaintenanceCycleEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "device_id", nullable = false, length = 255)
    private String deviceId;

    @Column(name = "policy_id", nullable = false)
    private UUID policyId;

    @Column(name = "cycle", nullable = false)
    private int cycle;

    @Column(name = "ticket_id", unique = true, length = 255)
    private String ticketId;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detected_snapshot", nullable = false, columnDefinition = "jsonb")
    private String detectedSnapshotJson;

    @Column(name = "work_notes", columnDefinition = "text")
    private String workNotes;

    @Column(name = "evidence_reference", columnDefinition = "text")
    private String evidenceReference;

    @Column(name = "completed_by", length = 255)
    private String completedBy;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "inspection_passed")
    private Boolean inspectionPassed;

    @Column(name = "inspected_by", length = 255)
    private String inspectedBy;

    @Column(name = "inspected_at")
    private Instant inspectedAt;

    @Column(name = "checkpoint_at")
    private Instant checkpointAt;

    @Column(name = "checkpoint_seconds")
    private Long checkpointSeconds;

    @Column(name = "checkpoint_cycles")
    private Long checkpointCycles;
}
