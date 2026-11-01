package com.ondemandmonitoring.finance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Immutable field-level history for administrator changes to expiration policy. */
@Entity
@Table(name = "commercial_expiration_setting_audits", indexes =
        @Index(name = "ix_commercial_expiration_audit_changed_at", columnList = "changed_at"))
@Getter
@Setter
@NoArgsConstructor
public class CommercialExpirationSettingsAudit {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private String id;

    @Column(name = "setting_name", nullable = false, length = 80, updatable = false)
    private String settingName;

    @Column(name = "previous_value", nullable = false, updatable = false)
    private int previousValue;

    @Column(name = "new_value", nullable = false, updatable = false)
    private int newValue;

    @Column(name = "actor", nullable = false, length = 255, updatable = false)
    private String actor;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;
}
