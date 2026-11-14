package com.ondemandmonitoring.contract.dto;

import java.time.Instant;

public record ContractDownloadResponse(String url, Instant expiresAt, String fileName) {}
