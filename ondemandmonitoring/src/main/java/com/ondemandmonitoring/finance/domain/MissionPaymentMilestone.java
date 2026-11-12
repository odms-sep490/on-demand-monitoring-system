package com.ondemandmonitoring.finance.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.finance.enums.MissionPaymentMilestoneStatus;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "mission_payment_milestones",
        uniqueConstraints = @UniqueConstraint(name = "uk_mission_payment_milestone_mission", columnNames = "mission_id"),
        indexes = {
                @Index(name = "ix_mission_payment_milestone_invoice_status", columnList = "invoice_id,status"),
                @Index(name = "ix_mission_payment_milestone_expiration", columnList = "payment_expires_at,status")
        })
@Getter
@Setter
public class MissionPaymentMilestone extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false)
    private Invoice invoice;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mission_id", nullable = false)
    private Mission mission;

    @Column(nullable = false, precision = 19, scale = 0)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency = "VND";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private MissionPaymentMilestoneStatus status = MissionPaymentMilestoneStatus.NOT_YET_DUE;

    @Column(name = "handed_over_at")
    private Instant handedOverAt;

    @Column(name = "payment_expires_at")
    private Instant paymentExpiresAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "priced_by_id", nullable = false)
    private User pricedBy;

    @Column(name = "priced_at", nullable = false)
    private Instant pricedAt;
}
