package com.ondemandmonitoring.checklist.config;

import java.util.List;
import java.util.stream.Stream;

/** Default description and work guidance for the bootstrap checklists, indexed like {@link ChecklistDefaults#CONTENTS}. */
final class ChecklistDefaultGuidance {
    private ChecklistDefaultGuidance() {}

    record Guidance(String description, List<String> steps, List<String> inputs, List<String> outputs) {}

    private static final List<String> COMMON_INPUTS = List.of(
            "Khu vực giám sát và ranh giới đã được xác nhận trong đơn",
            "Thời tiết đủ điều kiện bay (gió, tầm nhìn, không mưa)");

    private static Guidance g(String description, List<String> steps, List<String> outputs) {
        return new Guidance(description, steps, Stream.of(COMMON_INPUTS).flatMap(List::stream).toList(), outputs);
    }

    static final List<Guidance> BY_INDEX = List.of(
            g("Đánh giá nhanh hiện trạng chung của công trình từ trên cao để phát hiện hạng mục bất thường.",
                    List.of("Bay một vòng ở độ cao đủ quan sát toàn bộ công trình", "Quan sát các hạng mục chính theo thứ tự cố định", "Ghi chú dấu hiệu hư hỏng, thiếu an toàn hoặc chậm tiến độ"),
                    List.of("Ảnh tổng quan các hướng", "Ghi chú đánh giá tình trạng chung")),
            g("Ghi nhận mức độ hoàn thành của từng khu vực đang thi công để so sánh với kế hoạch.",
                    List.of("Xác định các khu vực đang thi công", "Chụp từng khu vực từ cùng một góc và độ cao", "Đối chiếu với tiến độ khách cung cấp nếu có"),
                    List.of("Ảnh từng khu vực thi công", "Ghi chú mức độ hoàn thành")),
            g("Kiểm tra bề mặt bên ngoài công trình để phát hiện nứt, hư hại hoặc lỗi hoàn thiện.",
                    List.of("Bay song song với từng mặt đứng", "Chụp cận các vị trí nghi ngờ", "Ghi chú vị trí bất thường"),
                    List.of("Ảnh từng mặt ngoài", "Ảnh cận vị trí bất thường")),
            g("Quan sát những khu vực người khó tiếp cận trực tiếp như mái, mép cao hoặc khe hẹp.",
                    List.of("Xác định các khu vực khó tiếp cận cùng khách hàng", "Bay quan sát ở khoảng cách an toàn", "Ghi chú những gì không quan sát được"),
                    List.of("Ảnh hoặc video các khu vực khó tiếp cận", "Ghi chú hạn chế quan sát")),
            g("Cung cấp bộ ảnh tổng quan công trình dùng cho báo cáo và lưu hồ sơ.",
                    List.of("Chụp từ các hướng chính ở độ cao ổn định", "Đảm bảo thấy trọn công trình trong khung hình"),
                    List.of("Bộ ảnh tổng quan công trình")),
            g("Ghi nhận hiện trạng cuối cùng sau khi hoàn tất các mục kiểm tra.",
                    List.of("Rà lại các khu vực đã kiểm tra", "Chụp bổ sung nếu còn thiếu", "Xác nhận đã hoàn tất kiểm tra"),
                    List.of("Ảnh hiện trạng sau kiểm tra", "Ghi chú kết luận")),
            g("Đánh giá nhanh tình trạng chung của nhà xưởng từ trên cao.",
                    List.of("Bay một vòng quanh nhà xưởng", "Quan sát mái, tường và khu vực xung quanh", "Ghi chú dấu hiệu bất thường"),
                    List.of("Ảnh tổng quan nhà xưởng", "Ghi chú đánh giá tình trạng chung")),
            g("Kiểm tra mái nhà xưởng để phát hiện thấm dột, hư hỏng tấm lợp hoặc tích tụ vật thể.",
                    List.of("Bay theo lưới song song phía trên mái", "Chụp cận các vị trí nghi ngờ", "Đánh dấu vị trí trên ảnh hoặc ghi chú"),
                    List.of("Ảnh toàn mái", "Ảnh cận vị trí hư hỏng")),
            g("Kiểm tra bề mặt và kết cấu phía trên như xà, giàn, thiết bị đặt trên mái.",
                    List.of("Quan sát từng cụm kết cấu", "Chụp cận mối nối và thiết bị", "Ghi chú dấu hiệu ăn mòn, biến dạng"),
                    List.of("Ảnh các cụm kết cấu", "Ghi chú tình trạng")),
            g("Chụp lại các vị trí bất thường đã phát hiện để làm bằng chứng và theo dõi.",
                    List.of("Quay lại từng vị trí đã đánh dấu", "Chụp rõ vị trí từ ít nhất hai góc"),
                    List.of("Ảnh từng vị trí bất thường", "Mô tả ngắn từng vị trí")),
            g("Ghi nhận toàn cảnh khu vực giám sát để làm tư liệu nền.",
                    List.of("Bay ở độ cao đủ bao quát toàn bộ khu vực", "Chụp hoặc quay theo các hướng chính"),
                    List.of("Ảnh hoặc video toàn cảnh khu vực")),
            g("Ghi nhận các khu vực chính trong phạm vi giám sát.",
                    List.of("Xác định các khu vực chính cùng khách hàng", "Chụp hoặc quay từng khu vực"),
                    List.of("Ảnh hoặc video từng khu vực chính", "Ghi chú tình trạng")),
            g("Chụp các vị trí cụ thể mà khách hàng yêu cầu.",
                    List.of("Xác nhận danh sách vị trí khách yêu cầu", "Chụp từng vị trí với góc nhìn rõ ràng"),
                    List.of("Ảnh từng vị trí được yêu cầu")),
            g("Ghi nhận hiện trạng khu vực tại thời điểm giám sát.",
                    List.of("Quan sát toàn bộ khu vực", "Chụp các điểm tiêu biểu", "Ghi chú thay đổi đáng chú ý"),
                    List.of("Ảnh hiện trạng khu vực", "Ghi chú hiện trạng")),
            g("Ghi nhận toàn cảnh khu vực rừng để theo dõi tình trạng chung.",
                    List.of("Bay ở độ cao đủ bao quát tán rừng", "Chụp hoặc quay theo các hướng chính"),
                    List.of("Ảnh hoặc video toàn cảnh khu vực rừng")),
            g("Quan sát tình trạng cây xanh để phát hiện cây chết, cháy hoặc sâu bệnh.",
                    List.of("Bay theo tuyến song song qua khu vực cây xanh", "Chụp cận các vị trí cây có dấu hiệu bất thường"),
                    List.of("Ảnh khu vực cây xanh", "Ghi chú vị trí cây bất thường")),
            g("Quan sát khu vực có dấu hiệu bất thường như khói, cháy, phá rừng hoặc sạt lở.",
                    List.of("Tiếp cận khu vực bất thường ở khoảng cách an toàn", "Chụp hoặc quay rõ dấu hiệu", "Ghi chú vị trí và mức độ"),
                    List.of("Ảnh hoặc video khu vực bất thường", "Ghi chú vị trí và mức độ")),
            g("Chụp các vị trí được chỉ định trong đơn.",
                    List.of("Xác nhận danh sách vị trí được chỉ định", "Chụp từng vị trí với góc nhìn rõ ràng"),
                    List.of("Ảnh từng vị trí được chỉ định")),
            g("Ghi nhận hiện trạng cuối cùng sau khi hoàn tất giám sát.",
                    List.of("Rà lại các khu vực đã giám sát", "Chụp bổ sung nếu còn thiếu", "Xác nhận hoàn tất giám sát"),
                    List.of("Ảnh hiện trạng sau giám sát", "Ghi chú kết luận")),
            g("Drone bay một vòng quanh công trình theo chiều kim đồng hồ và chụp ảnh các mặt bên ngoài.",
                    List.of("Cất cánh và giữ độ cao ổn định.",
                            "Bay theo đường bao quanh công trình theo chiều kim đồng hồ.",
                            "Chụp ảnh các mặt bên ngoài trong lúc bay, sau đó quay về điểm xuất phát."),
                    List.of("Ảnh các mặt bên ngoài công trình.")),
            g("Drone quay video liên tục khi bay một vòng quanh công trình theo chiều kim đồng hồ.",
                    List.of("Cất cánh và bắt đầu quay video.",
                            "Bay theo đường bao quanh công trình, giữ độ cao ổn định.",
                            "Kết thúc quay và đưa drone về điểm xuất phát."),
                    List.of("1 video toàn cảnh công trình.")));
}
