package com.ondemandmonitoring.stafflicense.dto.request;

import com.ondemandmonitoring.stafflicense.enumeration.LicenseStatus;
import com.ondemandmonitoring.stafflicense.enumeration.LicenseType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record StaffLicenseRequest(
        @NotBlank String userId,
        @NotNull LicenseType licenseType,
        @NotBlank @Size(max = 100) String licenseNumber,
        @NotBlank @Size(max = 200) String licenseName,
        @NotBlank @Size(max = 200) String issuingAuthority,
        @NotNull LocalDate issuedDate,
        LocalDate expiryDate,
        @NotNull LicenseStatus licenseStatus,
        @Size(max = 2000) String notes) {}
