package com.ondemandmonitoring.order.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.order.dto.OrderAuthorizationDocument;
import com.ondemandmonitoring.order.dto.OrderPermitDocument;
import com.ondemandmonitoring.order.enums.MonitoringRelationship;
import com.ondemandmonitoring.order.enums.OrderPermitStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Customer-supplied flight permit and site-authorization information. */
@Entity
@Table(name = "order_flight_compliance")
@Getter
@Setter
@NoArgsConstructor
public class OrderFlightCompliance extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(name = "permit_status", length = 20)
    private OrderPermitStatus permitStatus;

    @Column(name = "permit_number", length = 80)
    private String permitNumber;

    /** Last day the customer's permit is valid; must cover the preferred flight window. */
    @Column(name = "permit_expires_on")
    private LocalDate permitExpiresOn;

    /** Permit files the customer attached (PDF or image). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "permit_documents", columnDefinition = "jsonb")
    private List<OrderPermitDocument> permitDocuments;

    @Enumerated(EnumType.STRING)
    @Column(name = "monitoring_relationship", length = 40)
    private MonitoringRelationship monitoringRelationship;

    @Column(name = "monitoring_authorization_confirmed", nullable = false)
    private Boolean monitoringAuthorizationConfirmed = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "monitoring_authorization_documents", columnDefinition = "jsonb")
    private List<OrderAuthorizationDocument> monitoringAuthorizationDocuments;

    @Column(name = "permit_required")
    private Boolean permitRequired;

    @Column(name = "permit_zone_name", length = 120)
    private String permitZoneName;
}
