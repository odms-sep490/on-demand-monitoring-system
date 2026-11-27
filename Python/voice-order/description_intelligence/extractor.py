"""Generalized Semantic Requirement Extraction with flat schema & tolerant span grounding."""
import json
import os
import re
import time
from datetime import datetime, timezone

from .knowledge import strip_accents
from .models import Evidence, Extraction, FACT_FIELDS

UNCERTAINTY_PATTERN = re.compile(
    r'\b(chưa biết|không biết|chưa rõ|không rõ|cần tư vấn|tuỳ bên bạn|tùy bên bạn|chưa chắc|không chắc|bỏ qua|chua biet|khong biet|can tu van)\b',
    re.I,
)

EMPTY_PLACEHOLDER_PATTERN = re.compile(
    r'^(không|khong|không nêu|khong neu|không có|khong co|chưa nêu|chua neu|không đề cập|khong de cap|'
    r'chưa đề cập|chua de cap|không nhắc đến|khong nhac den|không xác định|khong xac dinh|'
    r'không chỉ định|khong chi dinh|chưa chỉ định|chua chi dinh|được chỉ định|duoc chi dinh|'
    r'không cụ thể|khong cu the|chưa cụ thể|chua cu the|không có yêu cầu|khong co yeu cau|'
    r'không có lưu ý|khong co luu y|n/a|none|null|trống|trong|-)$',
    re.I,
)

PROMPT_ECHO_PREFIX = re.compile(
    r'^(mục tiêu|đối tượng|phạm vi|hướng/góc quan sát|điểm bất thường/vấn đề ưu tiên khách nêu|đầu ra|lưu ý hiện trường)\s*[:\-]?\s*',
    re.I,
)


SYNONYM_GROUPS: tuple[tuple[str, ...], ...] = (
    ('toan bo', 'tat ca', 'ca khu vuc', 'toan canh'),
    ('anh chup', 'hinh anh', 'anh'),
    ('bao cao', 'report'),
)


def _normalize_for_match(text: str) -> str:
    return re.sub(r'\s+', ' ', strip_accents((text or '').lower())).strip(' .,;:!?"\'()[]')


def _apply_synonym_canonical(norm_text: str) -> str:
    out = norm_text
    for group in SYNONYM_GROUPS:
        canon = group[0]
        for alias in group[1:]:
            out = re.sub(rf'\b{re.escape(alias)}\b', canon, out)
    return out


def is_grounded_in_statement(value: str, quote: str, statement_text: str) -> bool:
    """Verify that extracted value/quote is genuinely grounded in the customer statement."""
    if not value or not statement_text:
        return False
    if quote and quote in statement_text and value in quote:
        return True
    norm_stmt = _normalize_for_match(statement_text)
    norm_val = _normalize_for_match(value)
    if norm_val and norm_val in norm_stmt:
        return True
    canon_stmt = _apply_synonym_canonical(norm_stmt)
    canon_val = _apply_synonym_canonical(norm_val)
    if canon_val and canon_val in canon_stmt:
        return True
    # Token-level grounding: at least 75% of content tokens in value must appear in statement
    val_tokens = [t for t in re.findall(r'\w+', canon_val) if len(t) >= 2]
    stmt_tokens = set(re.findall(r'\w+', canon_stmt))
    if not val_tokens:
        return False
    matched = sum(1 for t in val_tokens if t in stmt_tokens)
    return (matched / len(val_tokens)) >= 0.75


def _find_latest_grounding_turn(value: str, statements: list[str]) -> int | None:
    """Locate the latest customer statement index that grounds `value`."""
    for idx in range(len(statements) - 1, -1, -1):
        if is_grounded_in_statement(value, value, statements[idx]):
            return idx
    # Also check across combined statements (in case value spans two short turns)
    combined = ' '.join(statements)
    if is_grounded_in_statement(value, value, combined):
        return len(statements) - 1
    return None


def extract_with_qwen(statements: list[str], sources: list[dict], agent_factory=None) -> Extraction:
    """Invoke local Qwen-Agent with flat JSON schema to extract grounded semantic facts."""
    from conversation_agent import BoundedAssistant, order_agent_model_server
    factory = agent_factory or BoundedAssistant
    flat_schema = {
        'type': 'object',
        'additionalProperties': False,
        'properties': {
            'objective': {'type': 'string', 'maxLength': 200},
            'subject': {'type': 'string', 'maxLength': 200},
            'scope': {'type': 'string', 'maxLength': 200},
            'directions': {'type': 'string', 'maxLength': 150},
            'concerns': {'type': 'string', 'maxLength': 200},
            'outputs': {'type': 'string', 'maxLength': 150},
            'constraints': {'type': 'string', 'maxLength': 200},
            'contradictions': {'type': 'array', 'items': {'type': 'string'}, 'maxItems': 3},
            'unsupported': {'type': 'array', 'items': {'type': 'string'}, 'maxItems': 3},
            'nextQuestion': {'type': 'string', 'maxLength': 220},
            'questionField': {'type': 'string', 'maxLength': 40},
        },
        'required': [
            'objective', 'subject', 'scope', 'directions', 'concerns', 'outputs', 'constraints',
            'contradictions', 'unsupported', 'nextQuestion', 'questionField',
        ],
    }
    agent = factory(
        max_calls=1,
        deadline=time.monotonic() + 75,
        llm={
            'model': os.getenv('ORDER_AGENT_MODEL', 'qwen2.5:3b'),
            'model_server': order_agent_model_server(),
            'api_key': 'EMPTY',
            'generate_cfg': {
                'temperature': 0,
                'max_tokens': 500,
                'max_input_tokens': 7000,
                'request_timeout': 65,
                'max_retries': 0,
                'extra_body': {'options': {'repeat_penalty': 1.15}},
                'response_format': {
                    'type': 'json_schema',
                    'json_schema': {'name': 'mission_evidence', 'schema': flat_schema},
                },
            },
        },
        system_message=(
            'Bạn là bộ trích xuất dữ kiện CHỈ từ lời khách hàng trong mảng `statements`.\n'
            'Trả về JSON đúng schema. Nếu khách KHÔNG nhắc rõ trường nào, BẮT BUỘC để chuỗi rỗng "" (tuyệt đối không điền "không có", "không nêu", "tất cả" hay tự suy diễn):\n'
            '- objective: mục tiêu giám sát khách tự nói.\n'
            '- subject: đối tượng cụ thể khách tự nói.\n'
            '- scope: phạm vi hoặc phần khu vực cần phủ mà khách tự nói.\n'
            '- directions: hướng hoặc góc quan sát khách tự nói.\n'
            '- concerns: vấn đề, dấu hiệu bất thường hoặc điểm ưu tiên khách tự nói.\n'
            '- outputs: loại kết quả đầu ra khách tự yêu cầu. Nếu khách không nhắc -> để "".\n'
            '- constraints: lưu ý hiện trường khách tự nói. Nếu khách không nhắc -> để "".\n'
            '- contradictions: CHỈ ghi mâu thuẫn thật sự giữa lời khách (KHÔNG ghi tên trường). Không có -> [].\n'
            '- unsupported: CHỈ ghi yêu cầu ngoài phạm vi dịch vụ mà khách tự nói. Không có -> [].\n'
            '- nextQuestion: hỏi 1 câu ngắn làm rõ thông tin thiết yếu còn thiếu (để "" nếu subject và scope đã rõ).\n'
            '- questionField: tên trường của nextQuestion hoặc "".\n'
            'QUY TẮC CỨNG: Sao chép nguyên văn cụm từ từ `statements` (giữ nguyên cả tiếng Việt không dấu nếu khách viết không dấu, không tự sửa thành từ khác nghĩa). '
            'Nếu có nhiều câu trong `statements`, tổng hợp đầy đủ các câu; câu sau sửa câu trước thì lấy giá trị mới nhất.'
        ),
    )
    service_name = next((s.get('excerpt', '').split(':', 1)[0].strip() for s in sources if s.get('kind') == 'service'), '')
    final = []
    for chunk in agent.run(
        messages=[
            {
                'role': 'user',
                'content': json.dumps(
                    {'serviceName': service_name, 'statements': statements},
                    ensure_ascii=False,
                ),
            }
        ]
    ):
        final = chunk
    raw = next((m.get('content', '') for m in reversed(final) if m.get('role') == 'assistant'), '')
    raw = re.sub(r'<think>.*?</think>', '', raw, flags=re.S).strip()
    raw = re.sub(r'^```(?:json)?\s*|\s*```$', '', raw)
    return Extraction.model_validate_json(raw)


def ground_extracted_facts(
    extraction: Extraction,
    statements: list[str],
    service_id: str,
) -> tuple[dict[str, dict], list[dict], list[int]]:
    """Validate extracted facts (both flat fields and legacy Evidence list) against customer statements."""
    facts: dict[str, dict] = {}
    rejected: list[dict] = []
    uncertain_turns: list[int] = [
        idx for idx, s in enumerate(statements) if UNCERTAINTY_PATTERN.search(s or '')
    ]
    now = datetime.now(timezone.utc).isoformat()

    # 1. Process flat fields from Extraction
    for field_name in FACT_FIELDS:
        raw_val = (getattr(extraction, field_name, '') or '').strip()
        raw_val = PROMPT_ECHO_PREFIX.sub('', raw_val).strip()
        if not raw_val or UNCERTAINTY_PATTERN.search(raw_val) or EMPTY_PLACEHOLDER_PATTERN.match(raw_val.rstrip('.')):
            continue
        turn_idx = _find_latest_grounding_turn(raw_val, statements)
        if turn_idx is None:
            rejected.append({'field': field_name, 'value': raw_val, 'reason': 'not_grounded_in_statement'})
            continue
        facts[field_name] = {
            'field': field_name,
            'value': raw_val,
            'originalExpression': raw_val,
            'normalizedMeaning': raw_val,
            'source': turn_idx,
            'sourceTurn': turn_idx,
            'quote': raw_val,
            'status': 'CONFIRMED',
            'confirmationStatus': 'CONFIRMED',
            'validationStatus': 'VALID',
            'confidence': None,
            'lastUpdated': now,
            'applicability': service_id,
            'relatedService': service_id,
        }

    # 2. Process legacy Evidence list (sorted by statement index so later corrections override)
    ordered = sorted(extraction.facts, key=lambda f: f.statement)
    for fact in ordered:
        if fact.statement < 0 or fact.statement >= len(statements):
            rejected.append({**fact.model_dump(), 'reason': 'statement_index_out_of_range'})
            continue
        stmt_text = statements[fact.statement]
        if UNCERTAINTY_PATTERN.search(fact.value):
            continue
        if not is_grounded_in_statement(fact.value, fact.quote, stmt_text):
            rejected.append({**fact.model_dump(), 'reason': 'not_grounded_in_statement'})
            continue
        facts[fact.field] = {
            'field': fact.field,
            'value': fact.value.strip(),
            'originalExpression': fact.quote.strip(),
            'normalizedMeaning': fact.value.strip(),
            'source': fact.statement,
            'sourceTurn': fact.statement,
            'quote': fact.quote.strip(),
            'status': 'CONFIRMED',
            'confirmationStatus': 'CONFIRMED',
            'validationStatus': 'VALID',
            'confidence': None,
            'lastUpdated': now,
            'applicability': service_id,
            'relatedService': service_id,
        }
    return facts, rejected, uncertain_turns
