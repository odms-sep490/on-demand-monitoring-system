package com.ondemandmonitoring.missiontemplate.domain;

import com.ondemandmonitoring.checklist.domain.ChecklistDefinition;
import com.ondemandmonitoring.common.entity.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "mission_template_items",
        uniqueConstraints = @UniqueConstraint(name = "uk_mission_template_item",
                columnNames = {"mission_template_id", "checklist_definition_id"}),
        indexes = {
            @Index(name = "ix_mission_template_item_template", columnList = "mission_template_id"),
            @Index(name = "ix_mission_template_item_checklist", columnList = "checklist_definition_id")
        })
@Getter
@Setter
@NoArgsConstructor
public class MissionTemplateItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mission_template_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_mission_template_item_template"))
    private MissionTemplate missionTemplate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "checklist_definition_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_mission_template_item_checklist"))
    private ChecklistDefinition checklistDefinition;
}
