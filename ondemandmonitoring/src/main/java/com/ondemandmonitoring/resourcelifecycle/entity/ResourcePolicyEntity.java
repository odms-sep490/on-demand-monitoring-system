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
@Table(name = "resource_policies")
@Getter
@Setter
@NoArgsConstructor
public class ResourcePolicyEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "family", nullable = false, length = 20)
    private String family;

    @Column(name = "target_id", nullable = false, length = 255)
    private String targetId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "revision", nullable = false)
    private long revision;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "effective_at", nullable = false)
    private Instant effectiveAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rules", nullable = false, columnDefinition = "jsonb")
    private String rulesJson;

    @Column(name = "reason", nullable = false, columnDefinition = "text")
    private String reason;

    @Column(name = "created_by", nullable = false, length = 255)
    private String createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
