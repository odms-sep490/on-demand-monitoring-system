package com.ondemandmonitoring.aiops.entity;

import com.ondemandmonitoring.common.entity.BaseEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "ai_models")
@AttributeOverrides({
        @AttributeOverride(name = "id", column = @Column(name = "id", length = 64, nullable = false, updatable = false)),
        @AttributeOverride(name = "version", column = @Column(name = "entity_version", nullable = false, columnDefinition = "bigint default 0")),
        @AttributeOverride(name = "createdAt", column = @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "timestamptz default now()"))
})
@Getter
@Setter
@NoArgsConstructor
public class AiModel extends BaseEntity {

    @Column(name = "code", length = 80, nullable = false, unique = true)
    private String code;

    @Column(name = "name", length = 160, nullable = false)
    private String name;

    @Column(name = "version", length = 64, nullable = false)
    private String modelVersion;

    @Column(name = "model_type", length = 48, nullable = false)
    private String modelType;

    @Column(name = "capability", length = 120, nullable = false)
    private String capability;

    @Column(name = "source", length = 120, nullable = false)
    private String source;

    @Column(name = "license", length = 80, nullable = false)
    private String license;

    @Column(name = "checksum_sha256", length = 128, nullable = false)
    private String checksumSha256 = "UNVERIFIED";

    @Column(name = "runtime", length = 64, nullable = false)
    private String runtime;

    @Column(name = "device", length = 32, nullable = false)
    private String device = "CPU";

    @Column(name = "endpoint", length = 255, nullable = false)
    private String endpoint;

    @Column(name = "model_size", length = 48, nullable = false)
    private String modelSize = "N/A";

    @Column(name = "status", length = 32, nullable = false)
    private String status = "IDLE";

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_json", nullable = false, columnDefinition = "jsonb")
    private String configJson = "{}";

    @Column(name = "last_evaluated_at")
    private Instant lastEvaluatedAt;
}
