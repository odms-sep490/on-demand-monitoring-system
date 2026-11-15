package com.ondemandmonitoring.contract.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.contract.enums.ContractSubmissionStatus;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@org.hibernate.annotations.Check(
    name = "ck_contract_submission_state",
    constraints =
        "submission_number > 0 and status in"
            + " ('PENDING','SUPERSEDED','CHANGES_REQUESTED','CONFIRMED') and ((status in"
            + " ('PENDING','SUPERSEDED') and reviewed_at is null and reviewed_by is null) or"
            + " (status in ('CHANGES_REQUESTED','CONFIRMED') and reviewed_at is not null and"
            + " reviewed_by is not null)) and (status <> 'CHANGES_REQUESTED' or (review_note is not"
            + " null and length(trim(review_note)) > 0)) and ((status = 'SUPERSEDED' and"
            + " superseded_at is not null and superseded_by is not null and cleanup_next_attempt_at"
            + " is not null) or (status <> 'SUPERSEDED' and superseded_at is null and superseded_by"
            + " is null and document_deleted_at is null and cleanup_next_attempt_at is null)) and"
            + " cleanup_attempts >= 0")
@Table(
    name = "contract_submissions",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_contract_submission_number",
            columnNames = {"contract_id", "submission_number"}),
    indexes =
        @Index(
            name = "ix_contract_submission_cleanup",
            columnList = "status,document_deleted_at,cleanup_next_attempt_at,id"))
public class ContractSubmission extends BaseEntity {
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "contract_id", nullable = false, updatable = false)
  private OrderContract contract;

  @Column(name = "submission_number", nullable = false, updatable = false)
  private int submissionNumber;

  @Embedded private ContractDocument document;

  @Column(name = "submitted_by", nullable = false, updatable = false)
  private String submittedBy;

  @Column(name = "submitted_at", nullable = false, updatable = false)
  private Instant submittedAt;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 30)
  private ContractSubmissionStatus status;

  @Column(name = "superseded_at")
  private Instant supersededAt;

  @Column(name = "superseded_by")
  private String supersededBy;

  @Column(name = "document_deleted_at")
  private Instant documentDeletedAt;

  @Column(name = "cleanup_next_attempt_at")
  private Instant cleanupNextAttemptAt;

  @Column(name = "cleanup_attempts", nullable = false, columnDefinition = "integer default 0")
  private int cleanupAttempts;

  @Column(name = "reviewed_by")
  private String reviewedBy;

  @Column(name = "reviewed_at")
  private Instant reviewedAt;

  @Column(name = "review_note", length = 2000)
  private String reviewNote;
}
