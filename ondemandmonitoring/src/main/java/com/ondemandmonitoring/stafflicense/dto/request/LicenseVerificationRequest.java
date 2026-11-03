package com.ondemandmonitoring.stafflicense.dto.request;

import com.ondemandmonitoring.stafflicense.enumeration.VerificationStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record LicenseVerificationRequest(
        @NotNull VerificationStatus status,
        @Size(max = 2000) String notes) {}
