package com.ondemandmonitoring.finance.service.impl;

import com.ondemandmonitoring.finance.domain.Quote;
import com.ondemandmonitoring.order.domain.OrderChecklistItem;
import com.ondemandmonitoring.order.enums.*;
import com.ondemandmonitoring.order.repository.OrderChecklistItemRepository;
import com.ondemandmonitoring.pricing.service.PricingEngine;
import java.math.BigDecimal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly=true)
public class QuoteScenarioCalculator {
    private final PricingEngine engine;
    private final OrderChecklistItemRepository items;
        public record MediaOption(String itemId, String label, int images, int videos, int includedImages, int includedVideos, boolean automaticPricing) {}
    public List<MediaOption> available(Quote quote) {
        if(quote.getOrder()==null) return List.of();
        return items.findAllByOrderIdAndChecklistRevisionOrderByDisplayOrderAscIdAsc(quote.getOrder().getId(),quote.getChecklistRevision()).stream()
            .filter(i -> i.getReviewStatus()!=ChecklistReviewStatus.REJECTED && i.getReviewStatus()!=ChecklistReviewStatus.DUPLICATE)
            .map(i -> new MediaOption(i.getId(),i.getContent(),i.getRequestedImageCount(),i.getRequestedVideoCount(),
                i.getIncludedImageCountSnapshot(),i.getIncludedVideoCountSnapshot(),i.getPricingSnapshotVersion()>=2 && i.getSourceType()!=OrderChecklistSourceType.CUSTOMER_CUSTOM)).toList();
    }
    public record Change(@jakarta.validation.constraints.NotBlank String itemId, @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(500) Integer images, @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(500) Integer videos, @jakarta.validation.constraints.Min(1) Integer durationSeconds) {}
    public record Calculation(BigDecimal total, String reason) {}
        public Calculation calculate(Quote quote, Change change) {
        try { return calculateSupported(quote,change); }
        catch(com.ondemandmonitoring.common.exception.ApiException e) {
            return new Calculation(null,"Quy tắc giá chưa đủ để tính phương án. Cần nhân viên báo giá lại.");
        }
    }
    private Calculation calculateSupported(Quote quote, Change change) {
        if (change == null || change.durationSeconds() != null)
            return new Calculation(null, "Thay đổi thời lượng hoặc phạm vi cần nhân viên báo giá lại.");
        var originals = items.findAllByOrderIdAndChecklistRevisionOrderByDisplayOrderAscIdAsc(
                quote.getOrder().getId(), quote.getChecklistRevision());
        if (originals.isEmpty() || originals.stream().anyMatch(i -> i.getPricingSnapshotVersion() < 2
                || i.getSourceType() == OrderChecklistSourceType.CUSTOMER_CUSTOM))
            return new Calculation(null, "Báo giá có hạng mục thủ công hoặc dữ liệu giá cũ.");
        // A baseline that differs from the signed commercial quote must not lose manual adjustments.
        var baseline = engine.calculate(quote.getOrder(), originals);
        if (!baseline.assessmentComplete() || !baseline.pendingQuoteItems().isEmpty()
                || baseline.systemCalculatedAmount().compareTo(quote.getTotalAmount()) != 0)
            return new Calculation(null, "Báo giá có điều chỉnh hoặc quy tắc giá đã thay đổi.");
        var copies = new ArrayList<OrderChecklistItem>();
        boolean found = false;
        for (var original : originals) {
            var copy = new OrderChecklistItem();
            BeanUtils.copyProperties(original, copy);
            if (original.getId().equals(change.itemId())) {
                found = true;
                if (original.getReviewStatus() == ChecklistReviewStatus.REJECTED || original.getReviewStatus() == ChecklistReviewStatus.DUPLICATE)
                    return new Calculation(null, "Hạng mục không còn trong phạm vi báo giá.");
                int images = change.images() == null ? original.getRequestedImageCount() : change.images();
                int videos = change.videos() == null ? original.getRequestedVideoCount() : change.videos();
                if (images < 0 || videos < 0 || images > 500 || videos > 500)
                    return new Calculation(null, "Số lượng ảnh/video phải từ 0 đến 500.");
                if ((images > original.getIncludedImageCountSnapshot() && original.getExtraImageUnitPriceSnapshot() == null)
                        || (videos > original.getIncludedVideoCountSnapshot() && original.getExtraVideoUnitPriceSnapshot() == null))
                    return new Calculation(null, "Chưa có đơn giá bổ sung cho hạng mục này.");
                copy.setRequestedImageCount(images); copy.setRequestedVideoCount(videos);
            }
            copies.add(copy);
        }
        if (!found) return new Calculation(null, "Hạng mục không thuộc báo giá.");
        var result = engine.calculate(quote.getOrder(), copies);
        if (!result.assessmentComplete() || !result.pendingQuoteItems().isEmpty())
            return new Calculation(null, "Phương án cần nhân viên xác nhận giá.");
        return new Calculation(result.systemCalculatedAmount(), "Giá tham khảo từ quy tắc giá hiện hành; cần xác nhận báo giá lại trước khi áp dụng.");
    }
}



