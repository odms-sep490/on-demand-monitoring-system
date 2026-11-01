package com.ondemandmonitoring.finance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Singleton values used only when a new quote or deposit deadline is assigned. */
@Entity
@Table(name = "commercial_expiration_settings")
@Getter
@Setter
@NoArgsConstructor
public class CommercialExpirationSettings {
    public static final String DEFAULT_ID = "default";

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id = DEFAULT_ID;

    @Column(name = "quotation_acceptance_timeout_hours", nullable = false)
    private int quotationAcceptanceTimeoutHours = 48;

    @Column(name = "deposit_payment_timeout_hours", nullable = false)
    private int depositPaymentTimeoutHours = 24;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.EPOCH;

    @Column(name = "updated_by", nullable = false, length = 255)
    private String updatedBy = "system";

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
