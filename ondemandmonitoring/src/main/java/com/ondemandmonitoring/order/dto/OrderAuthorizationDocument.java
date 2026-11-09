package com.ondemandmonitoring.order.dto;

import com.ondemandmonitoring.order.enums.CustomerAuthorizationDocumentType;
import com.ondemandmonitoring.order.enums.DocumentVerificationStatus;
import java.time.Instant;

public record OrderAuthorizationDocument(
        String id,
        CustomerAuthorizationDocumentType documentType,
        String documentName,
        String fileName,
        String contentType,
        long sizeBytes,
        String dataUrl,
        String notes,
        DocumentVerificationStatus verificationStatus,
        String verifiedBy,
        Instant verifiedAt,
        String rejectionReason) {}
