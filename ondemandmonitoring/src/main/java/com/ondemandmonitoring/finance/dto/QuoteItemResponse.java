package com.ondemandmonitoring.finance.dto;

import com.ondemandmonitoring.finance.enums.QuoteItemType;
import java.math.BigDecimal;

public record QuoteItemResponse(String id, String orderChecklistItemId, QuoteItemType type,
                                String description, BigDecimal quantity, BigDecimal unitPrice,
                                BigDecimal amount, int displayOrder) {}
