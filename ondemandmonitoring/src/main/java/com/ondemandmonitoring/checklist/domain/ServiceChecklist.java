package com.ondemandmonitoring.checklist.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.service.domain.Service;
import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.*;

@Entity
@Table(name = "service_checklists",
        uniqueConstraints = @UniqueConstraint(name = "uk_service_checklist", columnNames = {"service_id", "checklist_id"}),
        indexes = {
            @Index(name = "ix_service_checklist_reverse", columnList = "checklist_id"),
            @Index(name = "ix_service_checklist_order", columnList = "service_id,display_order,id")
        })
@Getter
@Setter
@NoArgsConstructor
@org.hibernate.annotations.Check(constraints = "display_order >= 0")
public class ServiceChecklist extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_id", nullable = false, foreignKey = @ForeignKey(name = "fk_service_checklist_service"))
    private Service service;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "checklist_id", nullable = false, foreignKey = @ForeignKey(name = "fk_service_checklist_definition"))
    private ChecklistDefinition checklist;
    @Column(name = "display_order", nullable = false)
    private int displayOrder;
    // Evidence requirements can vary by service; Base Checklist prices are global.
    @Column(name = "included_image_count_override")
    private Integer includedImageCountOverride;
    @Column(name = "included_video_count_override")
    private Integer includedVideoCountOverride;
    /** A required checklist cannot be removed from a customer's order. */
    @Column(name = "is_required", nullable = false, columnDefinition = "boolean default false")
    private boolean required;
}
