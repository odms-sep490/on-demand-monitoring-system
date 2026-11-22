package com.ondemandmonitoring.finance.service.impl;

import com.ondemandmonitoring.common.exception.*;
import com.ondemandmonitoring.finance.dto.*;
import com.ondemandmonitoring.finance.repository.QuoteRepository;
import com.ondemandmonitoring.finance.service.IQuoteService;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only commercial projection. Never reprices or mutates an approved quote. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SmartQuoteService {
    private final IQuoteService quotes;
    private final QuoteRepository repository;
    private final AuthenticatedUserResolver users;
    private final QuoteScenarioCalculator scenarios;

    public record Analysis(String quoteId, int version, String revision, String status, String currency,
            BigDecimal total, List<QuoteItemResponse> items, Instant readAt, List<String> warnings,
            String aiStatus, List<QuoteScenarioCalculator.MediaOption> mediaOptions, List<String> factors) {}
    public record Option(@NotBlank @Size(max=100) String label, @Size(max=500) String requestedChange, @jakarta.validation.Valid QuoteScenarioCalculator.Change change) { public Option(String label, String requestedChange) { this(label, requestedChange, null); } }
    public record Compare(@NotBlank String revision, @NotNull @Size(min=2,max=3) List<@NotNull @jakarta.validation.Valid Option> options) {}
    public record Result(String label, String status, BigDecimal total, BigDecimal difference, String explanation) {}
    public record Comparison(String revision, List<Result> options) {}

    public Analysis read(String orderId, String quoteId, boolean customer) {
        // Ownership and publication checks reuse the authoritative customer workflow.
        if (customer) {
            var q = quotes.getCurrentForCustomer(orderId);
            if (!q.id().equals(quoteId)) throw new ApiException(ErrorCode.QUOTE_NOT_FOUND);
            return projection(q.id(), q.version(), token(quoteId), q.status().name(), q.finalOrderAmount(), q.items());
        }
        var actor = users.getCurrentUser();
        String role = actor.getRole() == null ? "" : actor.getRole().getCode().name();
        if (!Set.of("MANAGER", "ADMIN").contains(role)) throw new ApiException(ErrorCode.ACCESS_DENIED);
        var q = quotes.getPricingReview(orderId).quoteHistory().stream()
                .filter(row -> row.id().equals(quoteId)).findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.QUOTE_NOT_FOUND));
        return projection(q.id(), q.version(), token(quoteId), q.status().name(), q.totalAmount(), q.items());
    }

    private String token(String id) {
        var q = repository.findById(id).orElseThrow(() -> new ApiException(ErrorCode.QUOTE_NOT_FOUND));
        return q.getQuoteVersion() + ":" + q.getPricingRevision() + ":" + q.getChecklistRevision() + ":" + q.getUpdatedAt();
    }
    private Analysis projection(String id, int version, String revision, String status, BigDecimal total,
            List<QuoteItemResponse> items) {
        var sum = items.stream().map(QuoteItemResponse::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<String> warnings = sum.compareTo(total) == 0 ? List.of() :
                List.of("Các dòng chi phí chưa khớp tổng báo giá. Nhân viên cần kiểm tra lại.");
        return new Analysis(id, version, revision, status, "VND", total, items, Instant.now(), warnings, "ON_REQUEST", scenarios.available(repository.findById(id).orElseThrow()), List.of("Hạng mục và số lượng đã lưu trong báo giá", "Ảnh/video trong gói và số lượng bổ sung", "Điều chỉnh chính thức được giữ nguyên; giá phương án cần xác nhận lại"));
    }
    public Comparison compare(String orderId, String quoteId, boolean customer, Compare request) {
        var base = read(orderId, quoteId, customer);
        if (!base.revision().equals(request.revision()))
            throw new ApiException(ErrorCode.CONCURRENT_UPDATE, "Báo giá đã thay đổi. Vui lòng tải lại.");
        if (request.options() == null || request.options().size() < 2 || request.options().size() > 3)
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        return new Comparison(base.revision(), request.options().stream().map(option -> {
            boolean unchanged = option.change() == null && (option.requestedChange() == null || option.requestedChange().isBlank());
            var effectiveChange = option.change();
            if (effectiveChange == null && "Bỏ video".equalsIgnoreCase(option.requestedChange())) {
                var ids = base.items().stream().map(QuoteItemResponse::orderChecklistItemId).filter(Objects::nonNull).distinct().toList();
                if (ids.size() == 1) effectiveChange = new QuoteScenarioCalculator.Change(ids.get(0), null, 0, null);
            }
            if (effectiveChange != null) {
                var calculation = scenarios.calculate(repository.findById(quoteId).orElseThrow(), effectiveChange);
                return new Result(option.label(), calculation.total() == null ? "NEEDS_MANUAL_QUOTE" : "ESTIMATE", calculation.total(), calculation.total() == null ? null : calculation.total().subtract(base.total()), calculation.reason());
            }
            return new Result(option.label(), unchanged ? "CURRENT_QUOTE" : "NEEDS_MANUAL_QUOTE",
                    unchanged ? base.total() : null, unchanged ? BigDecimal.ZERO : null,
                    unchanged ? "Giữ nguyên nội dung báo giá." : "Cần nhân viên báo giá lại theo phạm vi mới.");
        }).toList());
    }
}




