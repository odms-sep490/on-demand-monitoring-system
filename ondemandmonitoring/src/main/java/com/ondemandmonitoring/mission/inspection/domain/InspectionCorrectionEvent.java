package com.ondemandmonitoring.mission.inspection.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(
    name = "mission_inspection_correction_events",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_inspection_event_sequence",
            columnNames = {"correction_id", "sequence_number"}),
    indexes =
        @Index(name = "ix_inspection_event_correction", columnList = "correction_id,occurred_at"))
public class InspectionCorrectionEvent extends BaseEntity {
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "correction_id", nullable = false, updatable = false)
  private InspectionCorrection correction;

  @Column(nullable = false, updatable = false, length = 30)
  private String action;

  @Column(name = "sequence_number", nullable = false, updatable = false)
  private long sequenceNumber;

  @Column(nullable = false, updatable = false)
  private String actor;

  @Column(name = "occurred_at", nullable = false, updatable = false)
  private Instant occurredAt;

  @Column(updatable = false, length = 2000)
  private String note;
}
