package com.ondemandmonitoring.mission.inspection.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.mission.domain.MissionChecklistExecution;
import jakarta.persistence.*;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Getter
@Setter
@Table(
    name = "mission_inspection_correction_items",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_inspection_correction_item",
            columnNames = {"correction_id", "execution_id"}))
public class InspectionCorrectionItem extends BaseEntity {
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "correction_id", nullable = false, updatable = false)
  private InspectionCorrection correction;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "execution_id", nullable = false, updatable = false)
  private MissionChecklistExecution execution;

  @Column(name = "requested_execution_version", nullable = false, updatable = false)
  private long requestedExecutionVersion;

  @Column(name = "content_snapshot", nullable = false, updatable = false, length = 4000)
  private String contentSnapshot;

  @Column(nullable = false, updatable = false, length = 2000)
  private String reason;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "media_ids", nullable = false, updatable = false, columnDefinition = "jsonb")
  private List<String> mediaIds = List.of();
}
