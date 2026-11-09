package com.ondemandmonitoring.review.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Criteria a manager weighs before approving an order.
 *
 * <p>{@code AUTO} criteria are computed by the system from the order data.
 * {@code MANUAL} criteria can only be judged by a person, so the manager records
 * an answer. {@code required} means: for AUTO a FAIL blocks approval, for MANUAL
 * the manager must answer and the answer must not be FAIL.
 */
@Getter
@RequiredArgsConstructor
public enum ReviewCriterionCode {
    // ---- automatic -------------------------------------------------------
    SERVICE_AREA(ReviewCriterionType.AUTO, true, "Vùng phục vụ",
            "Vị trí và vùng giám sát nằm trong khu vực công ty phục vụ"),
    /** Checklist review is enforced by the pricing workflow, not duplicated in readiness. */
    @Deprecated
    CHECKLIST_REVIEWED(ReviewCriterionType.AUTO, true, "Checklist đã review",
            "Mọi mục yêu cầu đã được phân loại cách tính giá"),
    SITE_CONTACT(ReviewCriterionType.AUTO, false, "Liên hệ hiện trường",
            "Có người liên hệ tại hiện trường"),
    SCHEDULE_DATE(ReviewCriterionType.AUTO, false, "Thời gian thực hiện",
            "Ngày mong muốn còn hiệu lực và hạn nhận kết quả không quá sát"),
    /** Kept only so existing database rows can still be read; it is no longer part of review. */
    @Deprecated
    FLIGHT_SCALE(ReviewCriterionType.AUTO, false, "Quy mô chuyến bay",
            "Độ cao và chiều dài tuyến nằm trong ngưỡng một chuyến bay thông thường"),

    // ---- manager decides -------------------------------------------------
    /** Kept only so existing database rows can still be read; it is no longer part of review. */
    @Deprecated
    SENSITIVE_AREA(ReviewCriterionType.MANUAL, true, "Khu vực nhạy cảm",
            "Khu vực không thuộc quân sự, biên giới, hạ tầng trọng yếu hoặc nơi cấm quay chụp"),
    /** Kept only so existing database rows can still be read; it is no longer part of review. */
    @Deprecated
    AIRSPACE_PERMIT(ReviewCriterionType.MANUAL, true, "Phương án giấy phép bay",
            "Xác nhận tình trạng giấy phép bay trước khi thực hiện nhiệm vụ"),
    SITE_ACCESS(ReviewCriterionType.MANUAL, true, "Quyền truy cập hiện trường",
            "Đã xác nhận chủ đất hoặc đơn vị quản lý đồng ý cho bay và quay chụp"),
    CAPABILITY(ReviewCriterionType.MANUAL, true, "Năng lực thực hiện",
            "Công ty đủ thiết bị và nhân sự cho yêu cầu này"),
    REQUESTED_SCOPE_FEASIBLE(ReviewCriterionType.MANUAL, true,
            "Khả năng đáp ứng phạm vi và kết quả",
            "Phạm vi và kết quả khách yêu cầu có khả năng thực hiện bằng hệ thống hiện tại"),
    PURPOSE(ReviewCriterionType.MANUAL, false, "Mục đích sử dụng",
            "Mục đích sử dụng dữ liệu của khách chấp nhận được"),
    RISK_LEVEL(ReviewCriterionType.MANUAL, false, "Mức rủi ro vận hành",
            "Đánh giá mức rủi ro tổng thể sau khi xem khu vực, yêu cầu và nguồn lực"),
    /** Kept only so existing database rows can still be read; it is no longer part of review. */
    @Deprecated
    PRICE_REASONABLE(ReviewCriterionType.MANUAL, false, "Giá hợp lý",
            "Giá đề xuất tương xứng với độ khó của công việc"),
    /** Kept only so existing database rows can still be read; it is no longer part of review. */
    @Deprecated
    SURVEY_NEEDED(ReviewCriterionType.MANUAL, false, "Cần khảo sát hoặc gọi khách",
            "Xác định có cần khảo sát hiện trường hoặc liên hệ khách để làm rõ thông tin");

    private final ReviewCriterionType type;
    private final boolean required;
    private final String title;
    private final String description;

    public boolean isActive() {
        return this != CHECKLIST_REVIEWED && this != FLIGHT_SCALE
                && this != SENSITIVE_AREA && this != AIRSPACE_PERMIT
                && this != PRICE_REASONABLE && this != SURVEY_NEEDED;
    }
}
