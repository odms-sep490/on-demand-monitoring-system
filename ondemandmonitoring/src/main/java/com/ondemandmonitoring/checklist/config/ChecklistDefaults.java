package com.ondemandmonitoring.checklist.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bootstrap catalog data; only the construction service's exact legacy template is migrated. */
public final class ChecklistDefaults {
    private ChecklistDefaults() {}

    public static final List<String> CONTENTS = List.of(
            "Kiểm tra tình trạng tổng thể công trình",
            "Ghi nhận tiến độ các khu vực đang thi công",
            "Kiểm tra mặt ngoài công trình",
            "Quan sát khu vực khó tiếp cận",
            "Chụp ảnh tổng quan công trình",
            "Ghi nhận hiện trạng sau khi hoàn tất kiểm tra",
            "Kiểm tra tình trạng tổng thể nhà xưởng",
            "Kiểm tra mái nhà xưởng",
            "Kiểm tra bề mặt và kết cấu phía trên",
            "Chụp ảnh các vị trí bất thường",
            "Ghi nhận toàn cảnh khu vực",
            "Ghi nhận các khu vực chính",
            "Chụp ảnh các vị trí khách hàng yêu cầu",
            "Ghi nhận hiện trạng khu vực",
            "Ghi nhận toàn cảnh khu vực rừng",
            "Quan sát tình trạng khu vực cây xanh",
            "Quan sát khu vực có dấu hiệu bất thường",
            "Chụp ảnh các vị trí được chỉ định",
            "Ghi nhận hiện trạng sau khi hoàn tất giám sát",
            "Chụp ảnh tổng quan công trình",
            "Quay video quanh công trình");

    public static String code(int number) { return "S4%03d".formatted(number); }

    public static Map<String, List<Integer>> templates() {
        var mappings = new LinkedHashMap<String, List<Integer>>();
        mappings.put("Giám sát công trình", List.of(20, 21));
        mappings.put("Kiểm tra nhà xưởng", List.of(7, 8, 9, 4, 10, 6));
        mappings.put("Giám sát khu vực", List.of(11, 12, 4, 13, 14));
        mappings.put("Giám sát rừng", List.of(15, 16, 17, 4, 18, 19));
        return mappings;
    }
}
