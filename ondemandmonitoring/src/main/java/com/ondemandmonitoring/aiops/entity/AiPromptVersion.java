package com.ondemandmonitoring.aiops.entity;

import com.ondemandmonitoring.common.entity.BaseEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
        name = "ai_prompt_versions",
        uniqueConstraints = @UniqueConstraint(columnNames = {"prompt_key", "version"})
)
@AttributeOverrides({
        @AttributeOverride(name = "version", column = @Column(name = "entity_version", nullable = false, columnDefinition = "bigint default 0")),
        @AttributeOverride(name = "createdAt", column = @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "timestamptz default now()"))
})
@Getter
@Setter
@NoArgsConstructor
public class AiPromptVersion extends BaseEntity {

    @Column(name = "prompt_key", length = 80, nullable = false)
    private String promptKey;

    @Column(name = "version", length = 40, nullable = false)
    private String promptVersion;

    @Column(name = "model_code", length = 80, nullable = false)
    private String modelCode;

    @Column(name = "content", columnDefinition = "text", nullable = false)
    private String content;

    @Column(name = "status", length = 32, nullable = false)
    private String status = "DRAFT";

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(name = "approved_by", length = 255)
    private String approvedBy;

    @Column(name = "activated_at")
    private Instant activatedAt;
}
