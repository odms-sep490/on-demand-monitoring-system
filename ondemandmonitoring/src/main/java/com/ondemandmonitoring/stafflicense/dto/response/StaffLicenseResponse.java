package com.ondemandmonitoring.stafflicense.dto.response;

import com.ondemandmonitoring.stafflicense.enumeration.LicenseStatus;
import com.ondemandmonitoring.stafflicense.enumeration.LicenseType;
import com.ondemandmonitoring.stafflicense.enumeration.VerificationStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record StaffLicenseResponse(
        String id,
        String userId,
        String staffName,
        LicenseType licenseType,
        String licenseNumber,
        String licenseName,
        String issuingAuthority,
        LocalDate issuedDate,
        LocalDate expiryDate,
        LicenseStatus licenseStatus,
        VerificationStatus verificationStatus,
        String verifiedByName,
        LocalDateTime verifiedAt,
        String documentFileName,
        String notes,
        boolean demoRecord) {}
