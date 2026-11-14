package com.ondemandmonitoring.mission.inspection.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.inspection.enums.InspectionCorrectionStatus;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(
    name = "mission_inspection_corrections",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uk_inspection_correction_command",
          columnNames = {"mission_id", "client_request_id"}),
      @UniqueConstraint(name = "uk_inspection_correction_active", columnNames = "active_mission_id")
    })
@org.hibernate.annotations.Check(
    name = "ck_inspection_correction_state",
    constraints =
        "status in ('OPEN','SUBMITTED','RESOLVED') and ((status = 'RESOLVED' and active_mission_id"
            + " is null and resolved_at is not null) or (status <> 'RESOLVED' and active_mission_id"
            + " is not null and active_mission_id = mission_id and resolved_at is null))")
public class InspectionCorrection extends BaseEntity {
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "mission_id", nullable = false, updatable = false)
  private Mission mission;

  @Column(name = "active_mission_id")
  private String activeMissionId;

  @Column(name = "client_request_id", nullable = false, updatable = false, length = 36)
  private String clientRequestId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private InspectionCorrectionStatus status;

  @Column(name = "requested_by", nullable = false, updatable = false)
  private String requestedBy;

  @Column(name = "requested_at", nullable = false, updatable = false)
  private Instant requestedAt;

  @Column(name = "resolved_at")
  private Instant resolvedAt;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "supplemental_mission_id")
  private Mission supplementalMission;
}
