package com.ondemandmonitoring.checklist.config;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Starting prices for the bootstrap checklists in {@link ChecklistDefaults}, keyed by number (1-based, same as
 * {@link ChecklistDefaults#code(int)}). Calibrated so each default service keeps its current catalog price:
 * construction 3.5M, factory 3.0M, area 2.5M, forest 4.0M (checklists 4 and 6 are shared by several services).
 * Media prices come from the DEFAULT pricing profile, so only the base price and the included allowance live here.
 * They are starting values an Admin is expected to adjust; the initializer never overwrites a checklist that already
 * has a price or allowance.
 */
public final class ChecklistDefaultPricing {
    private ChecklistDefaultPricing() {}

    public record Pricing(BigDecimal basePrice, int includedImages, int includedVideos, Integer videoSeconds) {
        static Pricing of(long basePrice, int images, int videos) {
            return new Pricing(BigDecimal.valueOf(basePrice), images, videos, videos > 0 ? 60 : null);
        }
    }

    private static final Map<Integer, Pricing> BY_NUMBER = Map.ofEntries(
            Map.entry(1, Pricing.of(800_000, 10, 1)),   // Kiểm tra tình trạng tổng thể công trình
            Map.entry(2, Pricing.of(800_000, 10, 1)),   // Ghi nhận tiến độ các khu vực đang thi công
            Map.entry(3, Pricing.of(700_000, 15, 1)),   // Kiểm tra mặt ngoài công trình
            Map.entry(4, Pricing.of(400_000, 10, 1)),   // Quan sát khu vực khó tiếp cận (shared)
            Map.entry(5, Pricing.of(500_000, 4, 0)),    // Chụp ảnh tổng quan công trình
            Map.entry(6, Pricing.of(300_000, 5, 1)),    // Ghi nhận hiện trạng sau khi hoàn tất kiểm tra (shared)
            Map.entry(7, Pricing.of(700_000, 10, 1)),   // Kiểm tra tình trạng tổng thể nhà xưởng
            Map.entry(8, Pricing.of(700_000, 15, 1)),   // Kiểm tra mái nhà xưởng
            Map.entry(9, Pricing.of(500_000, 10, 1)),   // Kiểm tra bề mặt và kết cấu phía trên
            Map.entry(10, Pricing.of(400_000, 15, 0)),  // Chụp ảnh các vị trí bất thường
            Map.entry(11, Pricing.of(600_000, 10, 1)),  // Ghi nhận toàn cảnh khu vực
            Map.entry(12, Pricing.of(600_000, 10, 1)),  // Ghi nhận các khu vực chính
            Map.entry(13, Pricing.of(500_000, 15, 0)),  // Chụp ảnh các vị trí khách hàng yêu cầu
            Map.entry(14, Pricing.of(400_000, 5, 1)),   // Ghi nhận hiện trạng khu vực
            Map.entry(15, Pricing.of(900_000, 10, 1)),  // Ghi nhận toàn cảnh khu vực rừng
            Map.entry(16, Pricing.of(800_000, 10, 1)),  // Quan sát tình trạng khu vực cây xanh
            Map.entry(17, Pricing.of(700_000, 10, 1)),  // Quan sát khu vực có dấu hiệu bất thường
            Map.entry(18, Pricing.of(600_000, 15, 0)),  // Chụp ảnh các vị trí được chỉ định
            Map.entry(19, Pricing.of(600_000, 5, 1)),   // Ghi nhận hiện trạng sau khi hoàn tất giám sát
            Map.entry(20, Pricing.of(1_750_000, 4, 0)),  // Chụp ảnh tổng quan công trình
            Map.entry(21, Pricing.of(1_750_000, 0, 1))); // Quay video quanh công trình

    public static Pricing forNumber(int number) { return BY_NUMBER.get(number); }
}
