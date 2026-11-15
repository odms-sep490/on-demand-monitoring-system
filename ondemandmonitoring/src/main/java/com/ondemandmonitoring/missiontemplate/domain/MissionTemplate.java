package com.ondemandmonitoring.missiontemplate.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.service.domain.Service;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "mission_templates",
        indexes = @Index(name = "ix_mission_template_service", columnList = "service_id"))
@Getter
@Setter
@NoArgsConstructor
public class MissionTemplate extends BaseEntity {

    @Column(name = "scope_label", nullable = false, length = 255)
    private String scopeLabel;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_mission_template_service"))
    private Service service;
}
