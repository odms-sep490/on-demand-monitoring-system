package com.ondemandmonitoring.stafflicense.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import com.ondemandmonitoring.stafflicense.enumeration.LicenseStatus;
import com.ondemandmonitoring.stafflicense.enumeration.LicenseType;
import com.ondemandmonitoring.stafflicense.enumeration.VerificationStatus;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "staff_licenses",
        uniqueConstraints = @UniqueConstraint(name = "uk_staff_license_user_number",
                columnNames = {"user_id", "license_number"}),
        indexes = @Index(name = "idx_staff_licenses_user", columnList = "user_id"))
@Getter
@Setter
@NoArgsConstructor
public class StaffLicense extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "license_type", nullable = false, length = 24)
    private LicenseType licenseType;

    @Column(name = "license_number", nullable = false, length = 100)
    private String licenseNumber;

    @Column(name = "license_name", nullable = false, length = 200)
    private String licenseName;

    @Column(name = "issuing_authority", nullable = false, length = 200)
    private String issuingAuthority;

    @Column(name = "issued_date", nullable = false)
    private LocalDate issuedDate;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "license_status", nullable = false, length = 24)
    private LicenseStatus licenseStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 24)
    private VerificationStatus verificationStatus;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "verified_by_id")
    private User verifiedBy;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Column(name = "document_storage_path", length = 700)
    private String documentStoragePath;

    @Column(name = "document_file_name", length = 255)
    private String documentFileName;

    @Column(name = "notes", length = 2000)
    private String notes;
}
