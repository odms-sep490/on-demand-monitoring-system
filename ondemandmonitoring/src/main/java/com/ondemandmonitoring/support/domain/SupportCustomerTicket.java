package com.ondemandmonitoring.support.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.FieldDefaults;

import java.time.Instant;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import com.ondemandmonitoring.support.enums.SupportTicketKind;

@Getter
@Setter
@Entity
@Table(name = "support_customer_tickets")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class SupportCustomerTicket extends BaseEntity {

    @Column(name = "ticket_code", nullable = false, unique = true, length = 100)
    String ticketCode;

    @Column(name = "customer_id", nullable = false)
    String customerId;

    @Column(name = "customer_name", length = 150)
    String customerName;

    @Column(name = "order_id")
    String orderId;

    @Column(name = "mission_id")
    String missionId;

    @Column(name = "category", nullable = false, length = 100)
    String category; // ORDERS, MISSIONS, RESULTS, MEDIA, SCHEDULING, ACCOUNT, TECHNICAL

    @Enumerated(EnumType.STRING)
    @Column(name = "ticket_kind", nullable = false, length = 40)
    SupportTicketKind ticketKind = SupportTicketKind.GENERAL;

    @Column(name = "subject", nullable = false, length = 255)
    String subject;

    @Column(name = "description", length = 3000)
    String description;

    @Column(name = "priority", nullable = false, length = 50)
    String priority = "NORMAL"; // NORMAL, HIGH, URGENT

    @Column(name = "status", nullable = false, length = 50)
    String status = "OPEN"; // OPEN, ASSIGNED, IN_PROGRESS, WAITING_FOR_CUSTOMER, WAITING_FOR_STAFF, RESOLVED, CLOSED, CANCELLED

    @Column(name = "assigned_staff_id")
    String assignedStaffId;

    @Column(name = "assigned_staff_name", length = 150)
    String assignedStaffName;

    @Column(name = "opened_at", nullable = false)
    Instant openedAt = Instant.now();

    @Column(name = "resolved_at")
    Instant resolvedAt;

    @Column(name = "resolution_notes", length = 2000)
    String resolutionNotes;
}
