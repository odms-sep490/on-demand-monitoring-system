package com.ondemandmonitoring.resourcelifecycle.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "resource_allocation_evaluations")
@Getter
@Setter
@NoArgsConstructor
public class ResourceAllocationEvaluationEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "mission_id", length = 255)
    private String missionId;

    @Column(name = "component_type", nullable = false, length = 40)
    private String componentType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_snapshot", nullable = false, columnDefinition = "jsonb")
    private String inputSnapshotJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_snapshot", nullable = false, columnDefinition = "jsonb")
    private String outputSnapshotJson;

    @Column(name = "actor_id", nullable = false, length = 255)
    private String actorId;

    @Column(name = "algorithm_version", nullable = false, length = 40)
    private String algorithmVersion;

    @Column(name = "elapsed_ms", nullable = false)
    private long elapsedMs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
