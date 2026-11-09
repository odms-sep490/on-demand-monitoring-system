package com.ondemandmonitoring.order.dto;

/**
 * A flight-permit file the customer attached to an order. Stored inline as base64
 * ({@code dataUrl}) the same way the order's area files and reference images are.
 */
public record OrderPermitDocument(String id, String fileName, String contentType, long sizeBytes, String dataUrl) {}
