package com.ondemandmonitoring.resourcelifecycle.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "resource_specifications")
@Getter
@Setter
@NoArgsConstructor
public class ResourceSpecificationEntity {

    @Id
    @Column(name = "model_id", nullable = false, updatable = false, length = 255)
    private String modelId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "manufacturer_specs", nullable = false, columnDefinition = "jsonb")
    private String manufacturerSpecsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "operating_specs", nullable = false, columnDefinition = "jsonb")
    private String operatingSpecsJson;

    @Column(name = "source_reference", nullable = false, columnDefinition = "text")
    private String sourceReference;

    @Column(name = "verified_by", length = 255)
    private String verifiedBy;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "revision", nullable = false)
    private long revision;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
