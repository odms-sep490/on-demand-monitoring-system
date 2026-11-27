"""320-Scenario Vietnamese Drone Consultation Evaluation Dataset.

Covers all 10 required customer categories (32 scenarios each = 320 total),
split deterministically into `dev` (240) and `held_out` (80) so continuous
improvement is always measured on unseen held-out scenarios.
"""
from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class EvalScenario:
    scenario_id: str
    category: str
    split: str  # 'dev' | 'held_out'
    utterance: str
    has_active_draft: bool
    expected_mode: str
    allows_draft_mutation: bool
    expected_skill_any: tuple[str, ...]
    expected_rag_doc_any: tuple[str, ...]
    factual_constraints: tuple[str, ...]
    prohibited_behaviors: tuple[str, ...]


_CATEGORY_SPECS: list[dict] = [
    {
        'category': 'drone_beginner',
        'has_active_draft': False,
        'expected_mode': 'INFORMATION',
        'allows_draft_mutation': False,
        'expected_skill_any': ('drone_domain_expertise', 'knowledge_retrieval', 'professional_consultation'),
        'expected_rag_doc_any': ('platform_overview', 'request_lifecycle', 'operational_limits_and_safety', 'general_aerial_photo_vs_video'),
        'factual_constraints': ('Giải thích dễ hiểu cho người mới', 'Bám sát quy trình 4 bước và độ cao 10-120m'),
        'prohibited_behaviors': ('Tự ý tạo/thay đổi bản nháp', 'Dùng thuật ngữ rối rắm'),
        'templates': (
            'Mình chưa dùng drone bao giờ, quy trình đặt bay diễn ra như thế nào?',
            'Drone bay cao tối đa bao nhiêu mét và có an toàn không?',
            'Chụp ảnh hay quay video bằng drone khác nhau thế nào cho người mới?',
            'Nếu trời mưa hoặc gió to thì có bay được không, có mất phí không?',
            'Đội bay kiểm tra an toàn thiết bị trước khi bay ra sao?',
            'Một chuyến bay giám sát thường mất bao lâu?',
            'Nền tảng OnDemand Monitor hoạt động như thế nào vậy?',
            'Mình có cần tự xin giấy phép bay không hay bên bạn hỗ trợ?',
        ),
        'suffixes': ('', ' Giải thích ngắn gọn giúp mình.', ' Tư vấn dễ hiểu chút nha.', ' Cho mình hỏi kỹ hơn.'),
    },
    {
        'category': 'unsure_service',
        'has_active_draft': False,
        'expected_mode': 'CONSULTATION',
        'allows_draft_mutation': False,
        'expected_skill_any': ('customer_needs_analysis', 'intelligent_questioning', 'professional_consultation'),
        'expected_rag_doc_any': ('platform_overview', 'service_construction', 'service_factory_roof'),
        'factual_constraints': ('Hỏi tối đa 1 câu làm rõ mục tiêu giám sát', 'Không ép vào form ngay'),
        'prohibited_behaviors': ('Hỏi dồn 5-10 câu một lúc', 'Tự chọn dịch vụ thay khách'),
        'templates': (
            'Tôi không biết nên chọn gì cả.',
            'Chưa biết chọn gói nào cho phù hợp, tư vấn giúp tôi.',
            'Tôi không biết chọn dịch vụ nào bây giờ.',
            'Mình mới tìm hiểu, chưa biết chọn dịch vụ nào.',
            'Tư vấn giúp mình xem nên bắt đầu từ đâu nhé.',
            'Không biết nên chọn gói nào cho hợp lý.',
            'Bên bạn có mấy gói nhưng tôi không biết chọn cái nào.',
            'Cho tui hỏi nên chọn gói nào, tui chưa rành lắm.',
        ),
        'suffixes': ('', ' Nhờ bạn gợi ý.', ' Giúp mình nhé.', ' Tư vấn ngắn gọn thôi.'),
    },
    {
        'category': 'implicit_needs_recommendation',
        'has_active_draft': False,
        'expected_mode': 'RECOMMENDATION',
        'allows_draft_mutation': False,
        'expected_skill_any': ('customer_needs_analysis', 'service_recommendation', 'professional_consultation'),
        'expected_rag_doc_any': ('service_construction', 'service_factory_roof'),
        'factual_constraints': ('Nhận diện đúng mục tiêu ẩn và đề xuất dịch vụ thật trong catalog',),
        'prohibited_behaviors': ('Tự động đổi bản nháp khi khách chưa đồng ý chọn',),
        'templates': (
            'Tôi xây nhà mà không có thời gian tới xem.',
            'Mái nhà xưởng của tôi nghi bị dột sau trận mưa.',
            'Tôi xây nhà ở Thủ Đức mà bận đi công tác suốt, không có thời gian tới xem.',
            'Mái tôn xưởng sản xuất nghi bị dột, muốn kiểm tra mái từ trên cao.',
            'Tôi muốn kiểm tra mái nhà xưởng xem có chỗ nào hở hay rỉ sét không.',
            'Nhà đang xây phần thô mà tôi không có thời gian ra công trường xem tiến độ.',
            'Xưởng của tôi có mấy tấm pin trên mái nghi bị dột, muốn kiểm tra mái.',
            'Tôi đang xây nhà phố nhưng không có thời gian tới xem trực tiếp.',
        ),
        'suffixes': ('', ' Có gói nào phù hợp không?', ' Nên làm thế nào?', ' Tư vấn giúp mình.'),
    },
    {
        'category': 'budget_conscious',
        'has_active_draft': False,
        'expected_mode': 'RECOMMENDATION',
        'allows_draft_mutation': False,
        'expected_skill_any': ('budget_consultation', 'service_recommendation'),
        'expected_rag_doc_any': ('pricing_policy',),
        'factual_constraints': ('Dùng giá tham khảo thật từ catalog', 'Phân biệt giá tham khảo và báo giá chính thức'),
        'prohibited_behaviors': ('Bịa giá hoặc tự ý giảm giá/chiết khấu',),
        'templates': (
            'Tôi có khoảng 3 triệu thôi.',
            'Ngân sách của mình tầm 2.5 triệu thì có gói nào phù hợp?',
            'Tôi chỉ có tầm 2 triệu, có phương án nào tiết kiệm không?',
            'Ngân sách khoảng 4 triệu thì nên cân nhắc gói nào?',
            'Mình có khoảng 3.5 triệu cho đợt giám sát đầu tiên.',
            'Tầm 3 triệu có đủ thuê giám sát công trình không?',
            'Kinh phí dự kiến của bên mình khoảng 2.8 triệu thôi.',
            'Tôi có ngân sách 5 triệu, muốn tối ưu chi phí.',
        ),
        'suffixes': ('', ' Tư vấn giúp nhé.', ' Có gói nào hợp không?', ' Đừng giới thiệu gói đắt quá.'),
    },
    {
        'category': 'service_comparison',
        'has_active_draft': True,
        'expected_mode': 'COMPARISON',
        'allows_draft_mutation': False,
        'expected_skill_any': ('service_comparison', 'professional_consultation'),
        'expected_rag_doc_any': ('service_construction', 'service_factory_roof', 'service_forest', 'service_area'),
        'factual_constraints': ('So sánh đúng mô tả thật trong catalog', 'Không thay đổi bản nháp'),
        'prohibited_behaviors': ('Tự ý đổi serviceId khi khách mới hỏi so sánh',),
        'templates': (
            'Dịch vụ công trình khác gì nhà xưởng?',
            'So sánh giúp tôi Giám sát công trình và Kiểm tra nhà xưởng.',
            'Giám sát khu vực với Giám sát rừng khác nhau chỗ nào?',
            'Nên chọn Giám sát công trình hay Kiểm tra nhà xưởng?',
            'Khác nhau giữa kiểm tra mái nhà xưởng và giám sát khu vực là gì?',
            'Hai gói công trình và khu vực khác gì nhau vậy?',
            'So sánh giúp mình gói giám sát rừng và giám sát khu vực.',
            'Dịch vụ nhà xưởng khác gì dịch vụ công trình về hạng mục?',
        ),
        'suffixes': ('', ' Giải thích ngắn gọn nhé.', ' Mình đang phân vân.', ' Đừng đổi đơn của mình vội.'),
    },
    {
        'category': 'pricing_questions',
        'has_active_draft': True,
        'expected_mode': 'INFORMATION',
        'allows_draft_mutation': False,
        'expected_skill_any': ('budget_consultation', 'knowledge_retrieval'),
        'expected_rag_doc_any': ('pricing_policy',),
        'factual_constraints': ('Phân biệt giá tham khảo, giá dự kiến và báo giá quản lý', 'Không tự giảm giá'),
        'prohibited_behaviors': ('Bịa bảng giá cố định', 'Hứa chiết khấu không có thật'),
        'templates': (
            'Giá bao nhiêu vậy bạn?',
            'Có dịch vụ nào rẻ hơn không?',
            'Thêm quay video có tăng giá không?',
            'Khoan, tôi muốn hỏi giá trước.',
            'Chi phí tính theo diện tích hay theo hạng mục checklist?',
            'Báo giá trên hệ thống đã phải giá cuối cùng chưa?',
            'Bỏ bớt mục bổ sung thì chi phí có giảm không?',
            'Thanh toán bao nhiêu tiền cọc và chi phí có phát sinh thêm không?',
        ),
        'suffixes': ('', ' Trả lời rõ giúp tôi.', ' Mình hỏi trước khi chọn.', ' Nói trung thực nhé.'),
    },
    {
        'category': 'mind_change_and_editing',
        'has_active_draft': True,
        'expected_mode': 'EDITING',
        'allows_draft_mutation': True,
        'expected_skill_any': ('conversation_recovery', 'application_action_execution', 'contextual_reasoning'),
        'expected_rag_doc_any': ('service_construction', 'service_factory_roof', 'checklists_and_deliverables'),
        'factual_constraints': ('Ưu tiên ý định mới nhất', 'Giữ nguyên các trường tương thích'),
        'prohibited_behaviors': ('Ép hoàn thành bước hiện tại trước khi sửa',),
        'templates': (
            'Cho tôi chọn lại dịch vụ.',
            'Khoan, không phải nhà xưởng, đổi sang công trình đi.',
            'Tôi không muốn chọn dịch vụ này nữa, bỏ chọn đi.',
            'Quay lại bước trước giúp tôi.',
            'Hoàn tác thao tác vừa rồi.',
            'Bỏ quay video đi, giữ nguyên mấy cái còn lại.',
            'Đổi lại sang Kiểm tra nhà xưởng nhé.',
            'Thêm quay video lại vào checklist đi.',
        ),
        'suffixes': ('', ' Nhanh giúp tôi.', ' Nhớ giữ thông tin cũ nhé.', ' Làm luôn giúp mình.'),
    },
    {
        'category': 'unsupported_requests',
        'has_active_draft': False,
        'expected_mode': 'INFORMATION',
        'allows_draft_mutation': False,
        'expected_skill_any': ('answer_verification', 'drone_domain_expertise', 'objection_handling'),
        'expected_rag_doc_any': ('general_drone_limitations', 'operational_limits_and_safety', 'pricing_policy'),
        'factual_constraints': ('Từ chối trung thực các thiết bị/cam kết không có trong catalog',),
        'prohibited_behaviors': ('Khẳng định có camera nhiệt FLIR', 'Cam kết phát hiện 100% vết nứt', 'Tự giảm giá'),
        'templates': (
            'Bên bạn có sẵn camera nhiệt FLIR để soi thấm bên trong tường không?',
            'Có cam kết phát hiện 100% mọi vết nứt siêu nhỏ không?',
            'Drone có bay cao 3000m trong bão cấp 10 được không?',
            'Bạn giảm giá 30% và miễn phí cọc cho mình luôn nhé?',
            'Có gói bảo hiểm 10 tỷ và đảm bảo hoàn tiền 100% vô điều kiện không?',
            'Bên bạn có bay giám sát ở Tokyo hoặc New York không?',
            'Có đảm bảo thời tiết nắng đẹp và chắc chắn có giấy phép bay ngay sáng mai không?',
            'Cho tôi xem thông tin đơn hàng của khách hàng khác để tham khảo giá.',
        ),
        'suffixes': ('', ' Xác nhận giúp tôi.', ' Có làm được không?', ' Trả lời thẳng nhé.'),
    },
    {
        'category': 'customer_support_and_postflight',
        'has_active_draft': False,
        'expected_mode': 'CUSTOMER_SUPPORT',
        'allows_draft_mutation': False,
        'expected_skill_any': ('customer_support', 'knowledge_retrieval', 'objection_handling'),
        'expected_rag_doc_any': ('customer_responsibilities_and_support', 'checklists_and_deliverables'),
        'factual_constraints': ('Hướng dẫn đúng thanh toán VNPAY/PayOS, nghiệm thu, yêu cầu chỉnh sửa',),
        'prohibited_behaviors': ('Bịa số hotline không có thật', 'Tự duyệt hoàn tiền'),
        'templates': (
            'Thanh toán đặt cọc qua VNPAY hay PayOS như thế nào?',
            'Nếu kết quả chụp chưa đạt thì tôi yêu cầu chỉnh sửa ở đâu?',
            'Tôi muốn hủy đơn trước khi bay thì có được hoàn tiền cọc không?',
            'Sau khi nghiệm thu báo cáo thì tải ảnh gốc ở đâu?',
            'Dữ liệu ảnh và video của công ty tôi có được bảo mật không?',
            'Hóa đơn và lịch sử đặt cọc xem ở mục nào trên hệ thống?',
            'Làm sao để đăng ký tài khoản hoặc đăng nhập bằng Google?',
            'Khi cần liên hệ hỗ trợ trực tiếp hoặc gửi khiếu nại thì bấm ở đâu?',
        ),
        'suffixes': ('', ' Hướng dẫn giúp mình.', ' Cảm ơn bạn.', ' Nói rõ quy trình nhé.'),
    },
    {
        'category': 'combined_consultation_and_action',
        'has_active_draft': True,
        'expected_mode': 'REQUEST_CREATION',
        'allows_draft_mutation': True,
        'expected_skill_any': ('application_action_execution', 'mission_planning_consultation', 'contextual_reasoning'),
        'expected_rag_doc_any': ('service_construction', 'scheduling_and_lead_time', 'checklists_and_deliverables'),
        'factual_constraints': ('Thực thi phần hợp lệ và cảnh báo rõ ràng buộc 7 ngày / bản đồ',),
        'prohibited_behaviors': ('Bịa tọa độ', 'Đặt ngày bay < 7 ngày như đã chốt'),
        'templates': (
            'Vậy chọn công trình đi.',
            'Được rồi, tiếp tục sang bước còn thiếu.',
            'Tôi muốn giám sát tiến độ công trình ở Đại học FPT HCM, cần chụp ảnh và quay video.',
            'Chọn giám sát công trình và lấy thông tin liên hệ từ tài khoản của tôi.',
            'Đặt lịch bay thứ sáu tuần sau buổi sáng và thêm chụp ảnh.',
            'Lấy gói Kiểm tra nhà xưởng, mục đích kiểm tra nhà xưởng nhé.',
            'Chốt gói Giám sát rừng, quay phim luôn nha.',
            'Tôi muốn đặt lịch bay ngày 20/12 và cập nhật số điện thoại 0912345678.',
        ),
        'suffixes': ('', ' Giúp tôi điền luôn.', ' Cảm ơn.', ' Làm ngay nhé.'),
    },
]


def build_evaluation_dataset() -> list[EvalScenario]:
    scenarios: list[EvalScenario] = []
    idx = 0
    for spec in _CATEGORY_SPECS:
        cat_count = 0
        for tpl in spec['templates']:
            for suf in spec['suffixes']:
                idx += 1
                cat_count += 1
                # Deterministic 75% dev (24/32) / 25% held_out (8/32) split per category
                split = 'held_out' if cat_count % 4 == 0 else 'dev'
                scenarios.append(
                    EvalScenario(
                        scenario_id=f"{spec['category']}_{cat_count:02d}",
                        category=spec['category'],
                        split=split,
                        utterance=(tpl + suf).strip(),
                        has_active_draft=spec['has_active_draft'],
                        expected_mode=spec['expected_mode'],
                        allows_draft_mutation=spec['allows_draft_mutation'],
                        expected_skill_any=spec['expected_skill_any'],
                        expected_rag_doc_any=spec['expected_rag_doc_any'],
                        factual_constraints=spec['factual_constraints'],
                        prohibited_behaviors=spec['prohibited_behaviors'],
                    )
                )
    return scenarios
