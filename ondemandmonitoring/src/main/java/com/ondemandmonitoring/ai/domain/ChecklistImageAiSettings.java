package com.ondemandmonitoring.ai.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "checklist_image_ai_settings")
@Getter
@Setter
public class ChecklistImageAiSettings {
    public static final String DEFAULT_ID = "default";

    @Id
    @Column(length = 36, updatable = false)
    private String id = DEFAULT_ID;

    @Column(nullable = false)
    private boolean enabled = true;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.EPOCH;

    @Column(name = "updated_by", nullable = false, length = 255)
    private String updatedBy = "system";
}
