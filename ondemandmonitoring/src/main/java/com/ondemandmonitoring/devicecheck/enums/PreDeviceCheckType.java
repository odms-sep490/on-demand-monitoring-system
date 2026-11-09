package com.ondemandmonitoring.devicecheck.enums;

/** Business-facing pre-flight catalogue. Internal protocol names must not leak to clients. */
public enum PreDeviceCheckType {
    BATTERY_HEALTH("battery_health", "Pin máy bay", "Dung lượng và tình trạng pin theo dữ liệu thiết bị", "THIẾT BỊ BAY", true, PreflightCheckSource.TELEMETRY),
    PROPELLER_INSPECTION("propeller_inspection", "Cánh quạt", "Nứt, cong, lỏng hoặc hư hỏng", "THIẾT BỊ BAY", true, PreflightCheckSource.MANUAL),
    AIRFRAME_INSPECTION("airframe_inspection", "Thân máy bay", "Khung, càng đáp và các bộ phận lắp ghép", "THIẾT BỊ BAY", true, PreflightCheckSource.MANUAL),
    CAMERA_HEALTH("camera_health", "Hệ thống camera", "Kết nối, hoạt động và khả năng ghi hình", "THIẾT BỊ BAY", false, PreflightCheckSource.MANUAL),
    OBSTACLE_SENSOR_HEALTH("obstacle_sensor_health", "Cảm biến phát hiện vật cản", "Tình trạng cảm biến và khả năng phát hiện vật cản", "THIẾT BỊ BAY", false, PreflightCheckSource.MANUAL),
    SATELLITE_SIGNAL("satellite_signal", "Tín hiệu vệ tinh", "Chất lượng tín hiệu vệ tinh phục vụ định vị", "ĐỊNH VỊ VÀ DẪN ĐƯỜNG", true, PreflightCheckSource.TELEMETRY),
    POSITIONING_HEALTH("positioning_health", "Hệ thống định vị", "Độ chính xác vị trí và trạng thái bộ ước lượng", "ĐỊNH VỊ VÀ DẪN ĐƯỜNG", true, PreflightCheckSource.TELEMETRY),
    COMPASS_HEALTH("compass_health", "La bàn", "Tình trạng và hiệu chuẩn la bàn", "ĐỊNH VỊ VÀ DẪN ĐƯỜNG", true, PreflightCheckSource.TELEMETRY),
    IMU_HEALTH("imu_health", "Cảm biến chuyển động", "Tình trạng IMU và dữ liệu chuyển động", "ĐỊNH VỊ VÀ DẪN ĐƯỜNG", true, PreflightCheckSource.TELEMETRY),
    HOME_POSITION("home_position", "Điểm quay về", "Vị trí quay về hợp lệ cho phương án an toàn", "ĐỊNH VỊ VÀ DẪN ĐƯỜNG", true, PreflightCheckSource.TELEMETRY),
    ALTITUDE_SENSOR("altitude_sensor", "Cảm biến độ cao", "Nguồn đo độ cao phục vụ chuyến bay", "ĐỊNH VỊ VÀ DẪN ĐƯỜNG", true, PreflightCheckSource.TELEMETRY),
    AIRCRAFT_CONNECTION("aircraft_connection", "Kết nối máy bay", "Kết nối đang hoạt động và telemetry còn mới", "KẾT NỐI VÀ ĐIỀU KHIỂN", true, PreflightCheckSource.BACKEND),
    COMMAND_LINK("command_link", "Tín hiệu điều khiển", "Chất lượng đường truyền lệnh điều khiển", "KẾT NỐI VÀ ĐIỀU KHIỂN", true, PreflightCheckSource.TELEMETRY),
    FLIGHT_CONTROLLER("flight_controller", "Bộ điều khiển máy bay", "Tình trạng bộ điều khiển bay", "KẾT NỐI VÀ ĐIỀU KHIỂN", true, PreflightCheckSource.TELEMETRY),
    AIRCRAFT_READINESS("aircraft_readiness", "Trạng thái sẵn sàng bay", "Khả năng sẵn sàng bay do thiết bị xác nhận", "KẾT NỐI VÀ ĐIỀU KHIỂN", true, PreflightCheckSource.TELEMETRY),
    MONITORING_CONNECTION("monitoring_connection", "Kết nối giám sát", "Dữ liệu giám sát được cập nhật liên tục", "KẾT NỐI VÀ ĐIỀU KHIỂN", true, PreflightCheckSource.BACKEND),
    MANUAL_OVERRIDE("manual_override", "Điều khiển dự phòng", "Phương án điều khiển dự phòng đã sẵn sàng", "KẾT NỐI VÀ ĐIỀU KHIỂN", true, PreflightCheckSource.MANUAL),
    SYSTEM_SELF_CHECK("system_self_check", "Tự kiểm tra hệ thống", "Kết quả tự kiểm tra an toàn của thiết bị", "AN TOÀN BAY", true, PreflightCheckSource.TELEMETRY),
    CONNECTION_FAILSAFE("connection_failsafe", "Bảo vệ khi mất tín hiệu", "Cấu hình xử lý khi mất kết nối", "AN TOÀN BAY", true, PreflightCheckSource.TELEMETRY),
    RETURN_TO_HOME("return_to_home", "Quay về an toàn", "Cấu hình quay về phù hợp với nhiệm vụ", "AN TOÀN BAY", true, PreflightCheckSource.TELEMETRY),
    LOW_BATTERY_PROTECTION("low_battery_protection", "Bảo vệ pin yếu", "Cấu hình bảo vệ và hạ cánh khi pin yếu", "AN TOÀN BAY", true, PreflightCheckSource.TELEMETRY),
    FLIGHT_GEOFENCE("flight_geofence", "Giới hạn khu vực bay", "Giới hạn vùng và độ cao của chuyến bay", "AN TOÀN BAY", true, PreflightCheckSource.BACKEND),
    EMERGENCY_LANDING("emergency_landing", "Phương án hạ cánh khẩn cấp", "Khu vực và phương án hạ cánh khẩn cấp", "AN TOÀN BAY", true, PreflightCheckSource.MANUAL),
    WEATHER_CONDITIONS("weather_conditions", "Điều kiện thời tiết", "Thời tiết phù hợp với thiết bị và nhiệm vụ", "MÔI TRƯỜNG BAY", true, PreflightCheckSource.EXTERNAL_API),
    FLIGHT_VISIBILITY("flight_visibility", "Tầm nhìn", "Tầm nhìn thực tế tại khu vực bay", "MÔI TRƯỜNG BAY", true, PreflightCheckSource.MANUAL),
    TAKEOFF_AREA("takeoff_area", "Khu vực cất cánh", "Khu vực cất cánh thông thoáng và an toàn", "MÔI TRƯỜNG BAY", true, PreflightCheckSource.MANUAL),
    LANDING_AREA("landing_area", "Khu vực hạ cánh", "Khu vực hạ cánh thông thoáng và an toàn", "MÔI TRƯỜNG BAY", true, PreflightCheckSource.MANUAL),
    AIRSPACE_PERMISSION("airspace_permission", "Khu vực được phép bay", "Giấy phép hoặc xác nhận vùng trời hợp lệ", "MÔI TRƯỜNG BAY", true, PreflightCheckSource.BACKEND),
    FLIGHT_ROUTE("flight_route", "Lộ trình bay", "Lộ trình và vùng hoạt động đã được chốt", "NHIỆM VỤ BAY", true, PreflightCheckSource.BACKEND),
    FLIGHT_LIMITS("flight_limits", "Giới hạn độ cao và tốc độ", "Giới hạn vận hành phù hợp với nhiệm vụ", "NHIỆM VỤ BAY", true, PreflightCheckSource.BACKEND),
    MISSION_ENERGY("mission_energy", "Khả năng hoàn thành chuyến bay", "Năng lượng dự kiến đủ cho lộ trình và dự phòng", "NHIỆM VỤ BAY", true, PreflightCheckSource.BACKEND),
    LANDING_DESTINATION("landing_destination", "Điểm kết thúc chuyến bay", "Điểm kết thúc hoặc quay về đã xác định", "NHIỆM VỤ BAY", true, PreflightCheckSource.BACKEND),
    RENTAL_BOOKING("rental_booking", "Đơn thuê", "Đơn thuê còn hiệu lực cho thời gian thực hiện", "DỊCH VỤ CHO THUÊ", true, PreflightCheckSource.BACKEND),
    AIRCRAFT_PERMISSION("aircraft_permission", "Quyền sử dụng máy bay", "Người vận hành được phân công và có quyền", "DỊCH VỤ CHO THUÊ", true, PreflightCheckSource.BACKEND),
    AIRCRAFT_HANDOVER("aircraft_handover", "Tình trạng bàn giao", "Thiết bị đã được bàn giao cho người vận hành", "DỊCH VỤ CHO THUÊ", true, PreflightCheckSource.MANUAL),
    MAINTENANCE_STATUS("maintenance_status", "Tình trạng bảo trì", "Thiết bị không bị khóa bảo trì hoặc ngừng khai thác", "DỊCH VỤ CHO THUÊ", true, PreflightCheckSource.BACKEND),
    MANAGEMENT_CONNECTION("management_connection", "Kết nối hệ thống quản lý", "Hệ thống quản lý sẵn sàng tiếp nhận dữ liệu", "DỊCH VỤ CHO THUÊ", true, PreflightCheckSource.BACKEND),
    MEDIA_STORAGE("media_storage", "Lưu trữ hình ảnh và video", "Kho lưu trữ sẵn sàng cho dữ liệu nhiệm vụ", "DỊCH VỤ CHO THUÊ", true, PreflightCheckSource.BACKEND);

    private final String code;
    private final String displayName;
    private final String description;
    private final String category;
    private final boolean required;
    private final PreflightCheckSource source;

    PreDeviceCheckType(String code, String displayName, String description, String category,
                       boolean required, PreflightCheckSource source) {
        this.code = code; this.displayName = displayName; this.description = description;
        this.category = category; this.required = required; this.source = source;
    }

    public String code() { return code; }
    public String displayName() { return displayName; }
    public String description() { return description; }
    public String category() { return category; }
    public boolean required() { return required; }
    public PreflightCheckSource source() { return source; }
    public PreDeviceCheckLevel level() { return required ? PreDeviceCheckLevel.CRITICAL : PreDeviceCheckLevel.WARNING; }
}
