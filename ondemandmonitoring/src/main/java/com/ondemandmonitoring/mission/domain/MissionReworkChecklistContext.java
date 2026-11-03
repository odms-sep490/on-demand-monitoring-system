package com.ondemandmonitoring.mission.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.delivery.domain.ResultRevisionCaseItem;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "mission_rework_checklist_contexts", uniqueConstraints =
        @UniqueConstraint(name = "uk_mission_rework_context_item", columnNames = {"mission_id", "case_item_id"}))
@Getter
@Setter
public class MissionReworkChecklistContext extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mission_id", nullable = false, updatable = false)
    private Mission mission;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "case_item_id", nullable = false, updatable = false)
    private ResultRevisionCaseItem caseItem;

    @Column(name = "order_checklist_item_id_snapshot", nullable = false, updatable = false, length = 255)
    private String orderChecklistItemIdSnapshot;

    @Column(name = "checklist_title_snapshot", nullable = false, updatable = false, length = 500)
    private String checklistTitleSnapshot;

    @Column(name = "manager_instructions_snapshot", updatable = false, length = 2000)
    private String managerInstructionsSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_feedback_item_ids", nullable = false, updatable = false, columnDefinition = "jsonb")
    private List<String> sourceFeedbackItemIds;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "guidance_snapshot", updatable = false, columnDefinition = "jsonb")
    private JsonNode guidanceSnapshot;
}
