"""Structured Drone Domain & Platform Knowledge Base.

Explicitly separates:
- `PLATFORM_SERVICE`: verified OnDemand Monitor services, policies, workflows, constraints.
- `GENERAL_DRONE_KNOWLEDGE`: general aerial monitoring/drone principles (never claimed as
  guaranteed platform extras unless present in the live catalog).

Volatile business data (live service list, active checklist items, exact indicative prices)
is NEVER hardcoded here — it is overlaid dynamically from the live backend catalog snapshot.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Literal

KnowledgeScope = Literal['PLATFORM_SERVICE', 'GENERAL_DRONE_KNOWLEDGE']


@dataclass(frozen=True)
class KnowledgeDocument:
    doc_id: str
    title: str
    category: str
    scope: KnowledgeScope
    entities: tuple[str, ...]
    keywords: tuple[str, ...]
    content_vi: str
    content_en: str


DRONE_KNOWLEDGE_DOCS: tuple[KnowledgeDocument, ...] = (
    # --- PLATFORM SERVICES & CAPABILITIES ---
    KnowledgeDocument(
        doc_id='platform_overview',
        title='Tổng quan nền tảng OnDemand Monitor',
        category='overview',
        scope='PLATFORM_SERVICE',
        entities=('platform', 'workflow', 'ai_review'),
        keywords=('gioi thieu', 'he thong', 'nen tang', 'la gi', 'tong quan', 'ondemand monitor'),
        content_vi=(
            'OnDemand Monitor là nền tảng giám sát hiện trường bằng drone theo yêu cầu. '
            'Khách hàng chọn dịch vụ, khoanh vùng trên bản đồ và mô tả mục tiêu; đội vận hành lập kế hoạch bay, '
            'điều phối phi công có chứng chỉ và drone đạt chuẩn; AI kiểm duyệt chất lượng ảnh và hỗ trợ tổng hợp báo cáo nghiệm thu.'
        ),
        content_en=(
            'OnDemand Monitor is an on-demand drone inspection platform where customers select a service, '
            'draw the site on the map, and receive AI-reviewed inspection reports from certified crews.'
        ),
    ),
    KnowledgeDocument(
        doc_id='service_construction',
        title='Giám sát công trình xây dựng',
        category='service',
        scope='PLATFORM_SERVICE',
        entities=('construction_monitoring', 'recurring', 'aerial_photo', 'aerial_video'),
        keywords=('cong trinh', 'xay dung', 'xay nha', 'tien do', 'thi cong', 'cong truong', 'ha tang'),
        content_vi=(
            'Dịch vụ Giám sát công trình giúp theo dõi tiến độ thi công, toàn cảnh mặt bằng, kết cấu đang xây và các mốc hoàn thiện '
            'mà chủ đầu tư hoặc chủ nhà không cần trực tiếp tới công trường. Hỗ trợ giám sát một lần hoặc định kỳ tuần/tháng với so sánh thay đổi giữa các đợt.'
        ),
        content_en='Construction monitoring tracks building progress, site layout, and milestones, supporting one-off or recurring runs.',
    ),
    KnowledgeDocument(
        doc_id='service_factory_roof',
        title='Kiểm tra nhà xưởng & mái công nghiệp',
        category='service',
        scope='PLATFORM_SERVICE',
        entities=('industrial_inspection', 'roof_inspection', 'solar_panel', 'safety'),
        keywords=('nha xuong', 'mai', 'mai ton', 'dot', 'ro ri', 'ket cau', 'pin mat troi', 'nha may', 'kho bai'),
        content_vi=(
            'Dịch vụ Kiểm tra nhà xưởng phù hợp khảo sát mái tôn, dấu hiệu hư hỏng/nghi dột bề mặt mái, kết cấu ngoài trời và hệ thống pin mặt trời trên mái '
            'mà không cần leo giàn giáo nguy hiểm. Lưu ý: drone ghi nhận hình ảnh quang học độ nét cao để quan sát trực quan, không thay thế kiểm định kết cấu chuyên sâu.'
        ),
        content_en='Factory inspection covers industrial roofs, exterior structures, and rooftop solar panels safely from the air.',
    ),
    KnowledgeDocument(
        doc_id='service_forest',
        title='Giám sát rừng & lâm nghiệp',
        category='service',
        scope='PLATFORM_SERVICE',
        entities=('forest_monitoring', 'area_coverage'),
        keywords=('rung', 'lam nghiep', 'chay rung', 'lan chiem', 'tai nguyen rung', 'cay rung'),
        content_vi=(
            'Dịch vụ Giám sát rừng hỗ trợ quan sát diện rộng khu vực rừng, phát hiện sớm dấu hiệu bất thường bề mặt, nguy cơ cháy rừng hoặc lấn chiếm đất rừng '
            'thông qua ảnh và video từ trên cao.'
        ),
        content_en='Forest monitoring provides wide-area aerial visibility for fire risk and encroachment observation.',
    ),
    KnowledgeDocument(
        doc_id='service_area',
        title='Giám sát khu vực & khảo sát hiện trạng',
        category='service',
        scope='PLATFORM_SERVICE',
        entities=('area_monitoring', 'survey_monitoring'),
        keywords=('khu vuc', 'an ninh', 'bai vat lieu', 'duong day', 'khao sat', 'hien trang', 'dat trong'),
        content_vi=(
            'Dịch vụ Giám sát khu vực phục vụ quan sát tổng thể bãi vật liệu, hạ tầng ngoài trời, hành lang tuyến hoặc khu đất rộng theo checklist tùy chỉnh.'
        ),
        content_en='Area monitoring covers stockyards, corridors, security perimeters, and open land surveys.',
    ),
    KnowledgeDocument(
        doc_id='service_agriculture_note',
        title='Ghi chú về giám sát nông nghiệp',
        category='service',
        scope='PLATFORM_SERVICE',
        entities=('agriculture', 'catalog_check'),
        keywords=('nong nghiep', 'cay trong', 'ruong', 'vuon', 'lua', 'canh tac'),
        content_vi=(
            'Hệ thống có mục đích bay Nông nghiệp theo Nghị định 288/2025/NĐ-CP; tuy nhiên khả năng đặt gói Nông nghiệp phụ thuộc vào việc dịch vụ tương ứng có đang bật trong catalog hiện tại hay không. '
            'Nếu catalog chưa có gói riêng, khách có thể trao đổi với quản lý hoặc xem xét Giám sát khu vực nếu phù hợp.'
        ),
        content_en='Agricultural flight purpose is recognized, subject to whether an active agriculture service exists in the live catalog.',
    ),
    KnowledgeDocument(
        doc_id='checklists_and_deliverables',
        title='Checklist hạng mục & sản phẩm bàn giao',
        category='deliverables',
        scope='PLATFORM_SERVICE',
        entities=('checklist', 'aerial_photo', 'aerial_video', 'pdf_report', 'ai_review'),
        keywords=('checklist', 'hang muc', 'san pham', 'ban giao', 'anh', 'video', 'pdf', 'bao cao', 'nghiem thu', 'anh goc'),
        content_vi=(
            'Mỗi dịch vụ đi kèm checklist hạng mục chuẩn trong gói và các mục bổ sung tùy chọn. '
            'Sản phẩm bàn giao gồm ảnh độ nét cao, video (theo cấu hình checklist), ghi chú hiện trường và báo cáo PDF. '
            'Ảnh được AI kiểm duyệt độ nét trước khi bàn giao; khách xem trước, nghiệm thu rồi tải toàn bộ dữ liệu gốc.'
        ),
        content_en='Each service includes package checklists and optional add-ons, delivering AI-checked photos, videos, and PDF reports.',
    ),
    KnowledgeDocument(
        doc_id='pricing_policy',
        title='Chính sách tính giá & báo giá chính thức',
        category='pricing',
        scope='PLATFORM_SERVICE',
        entities=('pricing_policy', 'quote', 'payment'),
        keywords=('gia', 'chi phi', 'bao gia', 'bao nhieu tien', 're hon', 'tang gia', 'phi', 'ngan sach', 'trieu', 'giam gia', 'chiet khau'),
        content_vi=(
            'Chi phí phụ thuộc dịch vụ, diện tích khu vực giám sát, các hạng mục checklist và số ảnh/video vượt định mức gói. '
            'Con số hiển thị khi tạo yêu cầu là giá tham khảo/dự kiến; quản lý sẽ rà soát phạm vi và gửi báo giá chính thức để khách xác nhận trước khi triển khai. '
            'Không phát sinh chi phí ngoài báo giá đã duyệt. Trợ lý không tự ý giảm giá hoặc bịa bảng giá cố định.'
        ),
        content_en='Pricing depends on service, area, checklists, and media volume. Estimates are indicative until confirmed by a manager.',
    ),
    KnowledgeDocument(
        doc_id='request_lifecycle',
        title='Quy trình 4 bước & vòng đời yêu cầu',
        category='workflow',
        scope='PLATFORM_SERVICE',
        entities=('workflow', 'quote', 'payment', 'preflight', 'ai_review'),
        keywords=('quy trinh', '4 buoc', 'vong doi', 'cac buoc', 'theo doi', 'trang thai', 'hoat dong'),
        content_vi=(
            'Quy trình gồm 4 bước: (1) Khách tạo yêu cầu, chọn dịch vụ và khoanh vùng trên bản đồ; '
            '(2) Quản lý duyệt phạm vi, gửi báo giá, ký hợp đồng, khách đặt cọc trực tuyến (VNPAY/PayOS) và phân công đội bay; '
            '(3) Đội bay kiểm tra 11 hạng mục trước bay, thực hiện bay và kiểm tra sau bay; '
            '(4) AI kiểm duyệt ảnh, khách xem báo cáo, nghiệm thu và tải dữ liệu gốc.'
        ),
        content_en='4-step lifecycle: request & map area -> manager quote/contract/deposit -> 11-item preflight & flight -> AI QA & handover.',
    ),
    KnowledgeDocument(
        doc_id='scheduling_and_lead_time',
        title='Quy định lịch bay & thời gian chuẩn bị',
        category='scheduling',
        scope='PLATFORM_SERVICE',
        entities=('scheduling', 'lead_time', 'permit', 'recurring'),
        keywords=('lich bay', 'ngay bay', '7 ngay', 'bao lau', 'khi nao', 'khung gio', 'dinh ky', 'tuan', 'thang', 'sang mai'),
        content_vi=(
            'Yêu cầu đặt lịch bay mới cần chọn ngày bắt đầu cách hiện tại tối thiểu 7 ngày để đội vận hành kiểm tra vùng bay, thời tiết và thủ tục pháp lý. '
            'Một chuyến bay thường kéo dài 30–60 phút tùy diện tích. Nền tảng hỗ trợ giám sát một lần hoặc định kỳ hằng tuần/hằng tháng.'
        ),
        content_en='Preferred start date must be at least 7 days ahead for airspace and permit checks. Flights typically take 30–60 minutes.',
    ),
    KnowledgeDocument(
        doc_id='operational_limits_and_safety',
        title='Giới hạn vận hành, an toàn bay & pháp lý',
        category='safety',
        scope='PLATFORM_SERVICE',
        entities=('safety', 'preflight', 'permit', 'weather', 'service_area'),
        keywords=('an toan', 'do cao', '120m', 'giay phep', 'vung cam bay', 'thoi tiet', 'mua', 'gio', 'bao tri', '11 hang muc', 'tp hcm'),
        content_vi=(
            'Độ cao bay hợp lệ trong biểu mẫu là 10–120m. Khu vực phục vụ ưu tiên là TP. Hồ Chí Minh (và các tỉnh lân cận như Đồng Nai, Bình Dương, Long An khi quản lý xem xét). '
            'Mỗi drone đều qua kiểm tra 11 hạng mục trước bay và tự khóa bảo trì khi chạm ngưỡng giờ bay. '
            'Đội vận hành kiểm tra vùng cấm bay và hỗ trợ thủ tục phép bay; nếu thời tiết mưa/gió mạnh, chuyến bay được hoãn/dời lịch miễn phí theo lựa chọn của khách.'
        ),
        content_en='Valid altitude is 10–120m; HCMC primary service area; 11-item preflight checks; weather delays incur no fee.',
    ),
    KnowledgeDocument(
        doc_id='customer_responsibilities_and_support',
        title='Trách nhiệm khách hàng, thanh toán, hoàn tiền & chỉnh sửa',
        category='support',
        scope='PLATFORM_SERVICE',
        entities=('payment', 'refund', 'revision', 'privacy', 'customer_responsibilities'),
        keywords=('trach nhiem', 'quyen giam sat', 'thanh toan', 'vnpay', 'payos', 'dat coc', 'hoan tien', 'huy don', 'chinh sua', 'bay lai', 'bao mat'),
        content_vi=(
            'Khách hàng cần xác nhận quyền hợp pháp đối với khu vực giám sát (chủ sở hữu/được ủy quyền/nhà thầu...) và cung cấp người liên hệ hiện trường. '
            'Đặt cọc trực tuyến qua VNPAY hoặc PayOS sau khi duyệt báo giá/hợp đồng. Hủy trước khi triển khai được hoàn cọc theo điều khoản hợp đồng. '
            'Nếu kết quả chưa đạt, khách bấm "Yêu cầu chỉnh sửa" theo từng hạng mục để đội ngũ xử lý lại. Dữ liệu được bảo mật riêng tư theo đơn.'
        ),
        content_en='Customers confirm monitoring authorization and site contact, pay deposit via VNPAY/PayOS, and can request item-level revisions.',
    ),
    # --- GENERAL DRONE KNOWLEDGE (CLEARLY SEPARATED FROM PLATFORM GUARANTEES) ---
    KnowledgeDocument(
        doc_id='general_aerial_photo_vs_video',
        title='Kiến thức chung: Khi nào nên chọn chụp ảnh hay quay video drone',
        category='general_drone',
        scope='GENERAL_DRONE_KNOWLEDGE',
        entities=('aerial_photo', 'aerial_video', 'roof_inspection', 'construction_monitoring'),
        keywords=('chup anh', 'quay video', 'khac nhau', 'nen chup hay quay', 'do net', 'goc quay', 'moi dung'),
        content_vi=(
            '[Kiến thức drone tổng quát] Chụp ảnh tĩnh độ phân giải cao phù hợp soi chi tiết bề mặt (mái tôn, mối nối, hiện trạng công trường, so sánh giữa các đợt). '
            'Quay video phù hợp xem tổng quan không gian, đường đi, bối cảnh xung quanh hoặc báo cáo trực quan cho lãnh đạo. '
            'Với người mới chưa rõ, kết hợp ảnh tĩnh cho hạng mục chính + 1 video tổng quan thường hiệu quả nhất.'
        ),
        content_en='High-res still photos excel at surface detail inspection; video provides spatial context and executive overviews.',
    ),
    KnowledgeDocument(
        doc_id='general_drone_limitations',
        title='Kiến thức chung: Giới hạn kỹ thuật của kiểm tra bằng drone',
        category='general_drone',
        scope='GENERAL_DRONE_KNOWLEDGE',
        entities=('safety', 'roof_inspection', 'weather'),
        keywords=('gioi han', 'han che', 'ben trong', 'chong tham', 'vet nut nho', 'nhiet', 'flir', 'ban dem', 'trong nha'),
        content_vi=(
            '[Kiến thức drone tổng quát] Drone quang học ngoài trời quan sát bề mặt phía trên và mặt ngoài công trình; '
            'không nhìn xuyên qua lớp vật liệu để chẩn đoán thấm dột bên trong kết cấu, không bay trong mưa to/gió mạnh và không thay thế kiểm định kỹ thuật chuyên ngành.'
        ),
        content_en='Optical outdoor drones inspect visible exteriors and cannot see through materials or replace certified structural engineering audits.',
    ),
)

# Lightweight Knowledge Graph edges (LightRAG-inspired entity expansion)
KNOWLEDGE_GRAPH_EDGES: dict[str, tuple[str, ...]] = {
    'roof_inspection': ('industrial_inspection', 'aerial_photo', 'safety', 'pricing_policy'),
    'construction_monitoring': ('recurring', 'aerial_photo', 'aerial_video', 'scheduling', 'pricing_policy'),
    'forest_monitoring': ('area_coverage', 'scheduling', 'safety'),
    'industrial_inspection': ('roof_inspection', 'solar_panel', 'checklist', 'pricing_policy'),
    'pricing_policy': ('quote', 'payment', 'checklist'),
    'scheduling': ('lead_time', 'permit', 'weather'),
    'safety': ('preflight', 'permit', 'weather'),
    'payment': ('refund', 'quote'),
}
