"""Adaptive Customer Interview Planner, Follow-up Depth & Stopping Policy."""
from .knowledge import build_business_capability_graph, strip_accents
from .models import Extraction, ReadinessState

FIELD_QUESTIONS: dict[str, str] = {
    'subject': 'Anh/chị muốn drone giám sát đối tượng hoặc khu vực cụ thể nào (ví dụ: tên nhà xưởng, khu rừng, hạng mục công trình)?',
    'scope': 'Phạm vi cần ghi nhận là toàn bộ khu vực hay tập trung vào một phần/vị trí cụ thể nào?',
    'objective': 'Mục tiêu chính của đợt giám sát này là gì (theo dõi hiện trạng, kiểm tra hư hỏng hay phục vụ báo cáo)?',
    'concerns': 'Có dấu hiệu bất thường hoặc vị trí ưu tiên nào đội bay cần chú ý đặc biệt không?',
    'outputs': 'Anh/chị muốn nhận kết quả ở dạng nào trong các đầu ra dịch vụ hỗ trợ?',
    'constraints': 'Có lưu ý hiện trường nào đội vận hành cần biết trước khi khảo sát không (nếu không có có thể bỏ qua)?',
}

FIELD_DEPTH_LEVEL: dict[str, int] = {
    'objective': 1,
    'subject': 2,
    'scope': 2,
    'concerns': 3,
    'directions': 3,
    'outputs': 4,
    'constraints': 5,
}

# Staff-only or separate-form topics that the description planner must never ask about
FORBIDDEN_QUESTION_TOPICS = (
    'tọa độ', 'bản đồ', 'kinh độ', 'vĩ độ', 'lịch bay', 'ngày bay', 'khung giờ',
    'giấy phép', 'độ cao bay', 'cấu hình drone', 'phi công', 'giá tiền', 'báo giá',
)


def _is_forbidden_question(question: str) -> bool:
    q = strip_accents((question or '').lower())
    return any(strip_accents(topic) in q for topic in FORBIDDEN_QUESTION_TOPICS)


def plan_next_step(
    service: dict,
    facts: dict[str, dict],
    extraction: Extraction,
    uncertain_turns: list[int],
    rejected_facts: list[dict],
    contradictions: list[str],
    unsupported: list[str],
    action: str = 'review',
) -> dict:
    """Select the single highest-value next clarification or decide to stop asking."""
    graph = build_business_capability_graph(service)
    confirmed_fields = set(facts.keys())
    critical_dims = graph['criticalDimensions']

    # Determine truly missing critical dimensions (never re-ask confirmed fields)
    missing_critical = [f for f in critical_dims if f not in confirmed_fields]
    # Also respect model-identified critical gaps only if not already confirmed
    for item in extraction.missingCritical:
        norm = strip_accents(item.lower())
        if 'đối tượng' in item.lower() or 'doi tuong' in norm:
            if 'subject' not in confirmed_fields and 'subject' not in missing_critical:
                missing_critical.append('subject')
        elif 'phạm vi' in item.lower() or 'pham vi' in norm or 'phần cần phủ' in item.lower():
            if 'scope' not in confirmed_fields and 'scope' not in missing_critical:
                missing_critical.append('scope')

    # Determine optional improvements (only unconfirmed important/optional dimensions)
    optional_improvements = [
        f for f in graph['importantDimensions']
        if f not in confirmed_fields and f not in missing_critical
    ]

    # Explicit stopping policy
    has_operational_blocker = bool(contradictions or unsupported or rejected_facts)
    if has_operational_blocker:
        readiness_state: ReadinessState = 'NEEDS_OPERATIONAL_REVIEW'
        legacy_readiness = 'NEEDS_HUMAN_REVIEW'
    elif not confirmed_fields:
        readiness_state = 'INSUFFICIENT_INFORMATION'
        legacy_readiness = 'NEEDS_CLARIFICATION'
    elif missing_critical:
        readiness_state = 'MATERIAL_CLARIFICATION_REQUIRED'
        legacy_readiness = 'NEEDS_CLARIFICATION'
    elif action == 'generate':
        readiness_state = 'READY_FOR_CUSTOMER_REVIEW'
        legacy_readiness = 'READY_FOR_CUSTOMER_REVIEW'
    else:
        readiness_state = 'SUFFICIENT_FOR_DRAFT'
        legacy_readiness = 'READY_FOR_DRAFT'

    # Select next question (maximize info gain, zero repetition, respect stopping policy)
    next_field: str | None = None
    next_question = ''
    quick_replies: list[str] = []

    if missing_critical:
        next_field = missing_critical[0]
        # Prefer model's contextual question if it is valid, non-forbidden, and targets an unconfirmed field
        model_q = (extraction.nextQuestion or '').strip()
        model_field = (extraction.questionField or '').strip()
        if model_q and not _is_forbidden_question(model_q) and model_field not in confirmed_fields:
            next_question = model_q
            if model_field in FIELD_QUESTIONS:
                next_field = model_field
        else:
            next_question = FIELD_QUESTIONS.get(next_field, FIELD_QUESTIONS['scope'])

        # Grounded quick replies from model or service capability graph
        if extraction.quickReplies:
            quick_replies = [r.strip() for r in extraction.quickReplies if r.strip()][:4]
        else:
            quick_replies = graph['clarificationHints'].get(next_field, [])[:3]
            if next_field == 'outputs' and graph['supportedDeliverables']:
                quick_replies = graph['supportedDeliverables'][:3]

        # If customer recently expressed uncertainty, add gentle explanation
        if uncertain_turns and 'bỏ qua' not in next_question.lower() and 'ví dụ' not in next_question.lower():
            next_question += ' (Nếu chưa chắc chắn, anh/chị có thể chọn gợi ý bên dưới hoặc để đội vận hành tư vấn thêm.)'

    depth_level = FIELD_DEPTH_LEVEL.get(next_field, 6) if next_field else 6

    return {
        'readinessState': readiness_state,
        'readiness': legacy_readiness,
        'missingCritical': missing_critical,
        'optionalImprovements': optional_improvements,
        'nextQuestionField': next_field,
        'nextQuestion': next_question,
        'quickReplies': quick_replies,
        'interviewDepthLevel': depth_level,
    }
