package com.ondemandmonitoring.stafflicense.dto.response;

public record StaffLicenseDocumentResponse(String url, long expiresInSeconds, String fileName) {}
