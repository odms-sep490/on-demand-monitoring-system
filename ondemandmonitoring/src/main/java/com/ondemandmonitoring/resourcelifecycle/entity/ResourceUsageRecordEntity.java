package com.ondemandmonitoring.resourcelifecycle.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
        name = "resource_usage_records",
        indexes = {
            @Index(
                    name = "ix_resource_usage_lookup",
                    columnList = "resource_kind,resource_id,status,started_at,ended_at")
        })
@Getter
@Setter
@NoArgsConstructor
public class ResourceUsageRecordEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "resource_id", nullable = false, length = 255)
    private String resourceId;

    @Column(name = "resource_kind", nullable = false, length = 12)
    private String resourceKind;

    @Column(name = "mission_id", length = 255)
    private String missionId;

    @Column(name = "assignment_id", length = 255)
    private String assignmentId;

    @Column(name = "role", length = 24)
    private String role;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at", nullable = false)
    private Instant endedAt;

    @Column(name = "actual_seconds", nullable = false)
    private long actualSeconds;

    @Column(name = "estimated_seconds", nullable = false)
    private long estimatedSeconds;

    @Column(name = "cycles")
    private Integer cycles;

    @Column(name = "distance_m")
    private Double distanceM;

    @Column(name = "source", nullable = false, length = 32)
    private String source;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "evidence_reference", columnDefinition = "text")
    private String evidenceReference;

    @Column(name = "reason", nullable = false, columnDefinition = "text")
    private String reason;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 200)
    private String idempotencyKey;

    @Column(name = "created_by", nullable = false, length = 255)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "verified_by", length = 255)
    private String verifiedBy;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "supersedes")
    private UUID supersedes;
}
