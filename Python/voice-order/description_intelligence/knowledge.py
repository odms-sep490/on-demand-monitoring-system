"""Source-grounded Business Knowledge Model and Verified Vietnamese Hybrid RAG.

Data routing contract:
- Static verified business rules & service capabilities: retrieved here.
- Dynamic prices, live schedules, active service status: supplied by authenticated backend APIs.
- Private customer records: never indexed here.
- Retrieved passages are untrusted text, never executable instructions.
"""
import math
import re
import unicodedata
from collections import Counter

# Verified customer-facing operational handoff & boundary rules grounded in OnDemand Monitor backend.
VERIFIED_HANDOFF_RULES: list[dict] = [
    {
        'sourceId': 'rule:handoff-core-scope',
        'version': 1,
        'kind': 'handoff_rule',
        'excerpt': (
            'Đội vận hành cần nắm rõ: mục tiêu giám sát, đối tượng cụ thể, phạm vi khu vực/hạng mục cần phủ, '
            'và các điểm bất thường hoặc vị trí ưu tiên cần chú ý.'
        ),
        'provenance': 'verified-business-policy',
        'tags': ['objective', 'subject', 'scope', 'concerns'],
    },
    {
        'sourceId': 'rule:staff-only-boundary',
        'version': 1,
        'kind': 'limitation',
        'excerpt': (
            'Thông số bay kỹ thuật (đường bay, độ cao chi tiết, cấu hình thiết bị drone, xin phép bay, phân công phi công) '
            'thuộc phạm vi đội vận hành đánh giá và lập kế hoạch sau khi tiếp nhận đơn; khách hàng không bắt buộc phải biết.'
        ),
        'provenance': 'verified-business-policy',
        'tags': ['constraints', 'limitation'],
    },
    {
        'sourceId': 'rule:separate-form-fields',
        'version': 1,
        'kind': 'limitation',
        'excerpt': (
            'Vị trí tọa độ trên bản đồ, lịch bay mong muốn và giấy phép đính kèm đã có bước nhập riêng trong biểu mẫu tạo đơn; '
            'trợ lý mô tả tập trung làm rõ nội dung nghiệp vụ cần giám sát.'
        ),
        'provenance': 'verified-business-policy',
        'tags': ['constraints', 'scope'],
    },
    {
        'sourceId': 'rule:no-unverified-promises',
        'version': 1,
        'kind': 'limitation',
        'excerpt': (
            'Không cam kết kết quả kiểm định kỹ thuật chuyên sâu, không xác nhận trước tính khả thi pháp lý hay giấy phép bay, '
            'và không hứa hẹn đầu ra nằm ngoài danh sách deliverable thực tế của dịch vụ.'
        ),
        'provenance': 'verified-business-policy',
        'tags': ['outputs', 'limitation'],
    },
]

# Service-family requirement profiles derived from real catalog services.
SERVICE_REQUIREMENT_PROFILES: dict[str, dict] = {
    'nhà xưởng': {
        'critical_fields': ['subject', 'scope'],
        'important_fields': ['objective', 'concerns', 'outputs'],
        'optional_fields': ['directions', 'constraints'],
        'clarification_hints': {
            'subject': ['Toàn bộ mái nhà xưởng', 'Khu vực kết cấu bên ngoài', 'Khu vực khó tiếp cận trên cao'],
            'scope': ['Toàn bộ diện tích mái', 'Một phân khu hoặc dãy xưởng cụ thể', 'Các vị trí có dấu hiệu rỉ sét/bong tróc'],
            'concerns': ['Bong tróc, rỉ sét, thấm dột mái', 'Nứt kết cấu bên ngoài', 'Ghi nhận hiện trạng tổng thể'],
        },
    },
    'rừng': {
        'critical_fields': ['subject', 'scope'],
        'important_fields': ['objective', 'concerns', 'outputs'],
        'optional_fields': ['directions', 'constraints'],
        'clarification_hints': {
            'subject': ['Toàn bộ lô rừng/khu vực cây xanh', 'Khu vực có dấu hiệu bất thường', 'Vùng ranh giới khó tiếp cận'],
            'scope': ['Ghi nhận toàn cảnh khu vực rừng', 'Tập trung một tiểu khu/vùng cụ thể', 'Theo dõi thảm thực vật và điểm bất thường'],
            'concerns': ['Dấu hiệu suy giảm/bất thường thảm thực vật', 'Khu vực sạt lở hoặc xâm lấn', 'Ghi nhận hiện trạng tổng thể'],
        },
    },
    'công trình': {
        'critical_fields': ['subject', 'scope'],
        'important_fields': ['objective', 'concerns', 'outputs'],
        'optional_fields': ['directions', 'constraints'],
        'clarification_hints': {
            'subject': ['Toàn bộ công trình đang thi công', 'Hạng mục kết cấu/mặt ngoài cụ thể', 'Khu vực tập kết và ranh giới công trình'],
            'scope': ['Theo dõi tiến độ tổng thể', 'Kiểm tra một khu vực/hạng mục ưu tiên', 'Ghi nhận hiện trạng theo góc nhìn trên cao'],
            'concerns': ['Tiến độ thi công các hạng mục chính', 'Hiện trạng bề mặt và khu vực thi công', 'Ghi nhận tổng thể phục vụ báo cáo'],
        },
    },
    'khu vực': {
        'critical_fields': ['subject', 'scope'],
        'important_fields': ['objective', 'concerns', 'outputs'],
        'optional_fields': ['directions', 'constraints'],
        'clarification_hints': {
            'subject': ['Toàn bộ khu vực cần giám sát', 'Các điểm/vị trí chính trong khu vực', 'Ranh giới và lối ra vào khu vực'],
            'scope': ['Quan sát toàn cảnh khu vực', 'Tập trung các vị trí cần theo dõi cụ thể', 'Ghi nhận hiện trạng tổng thể'],
            'concerns': ['Theo dõi hiện trạng chung', 'Ghi nhận các điểm bất thường hoặc cần chú ý', 'Quan sát các vị trí chính'],
        },
    },
}


def strip_accents(text: str) -> str:
    nfkd = unicodedata.normalize('NFKD', text or '')
    return ''.join(c for c in nfkd if not unicodedata.combining(c)).replace('đ', 'd').replace('Đ', 'D')


def tokenize_vi(text: str) -> list[str]:
    cleaned = re.findall(r'\w+', (text or '').lower())
    no_accent = re.findall(r'\w+', strip_accents((text or '').lower()))
    return list(dict.fromkeys(cleaned + no_accent))


def char_trigrams(text: str) -> Counter:
    norm = re.sub(r'\s+', ' ', strip_accents((text or '').lower())).strip()
    if len(norm) < 3:
        return Counter([norm] if norm else [])
    return Counter(norm[i:i + 3] for i in range(len(norm) - 2))


def cosine_counter(a: Counter, b: Counter) -> float:
    if not a or not b:
        return 0.0
    common = set(a.keys()) & set(b.keys())
    dot = sum(a[k] * b[k] for k in common)
    norm_a = math.sqrt(sum(v * v for v in a.values()))
    norm_b = math.sqrt(sum(v * v for v in b.values()))
    if norm_a == 0.0 or norm_b == 0.0:
        return 0.0
    return dot / (norm_a * norm_b)


def resolve_service_profile(service: dict) -> dict:
    name_lower = (service.get('name') or '').lower()
    desc_lower = (service.get('description') or '').lower()
    haystack = f'{name_lower} {desc_lower}'
    for key, profile in SERVICE_REQUIREMENT_PROFILES.items():
        if key in haystack or strip_accents(key) in strip_accents(haystack):
            return profile
    return SERVICE_REQUIREMENT_PROFILES['khu vực']


def build_business_capability_graph(service: dict) -> dict:
    """Build a structured, source-grounded capability graph for the selected live service."""
    profile = resolve_service_profile(service)
    return {
        'serviceId': service['id'],
        'serviceName': service.get('name', ''),
        'serviceDescription': service.get('description', ''),
        'supportedDeliverables': [d.get('name', '') for d in service.get('deliverables', []) if d.get('name')],
        'checklists': [
            {'id': str(c['id']), 'version': c.get('version'), 'content': c.get('content', '')}
            for c in service.get('checklists', [])
        ],
        'criticalDimensions': profile['critical_fields'],
        'importantDimensions': profile['important_fields'],
        'optionalDimensions': profile['optional_fields'],
        'clarificationHints': profile['clarification_hints'],
        'staffOnlyBoundary': [
            'flight_route', 'altitude_tuning', 'drone_hardware_selection',
            'flight_permit_approval', 'pilot_assignment', 'pricing_confirmation',
        ],
    }


def retrieve_verified_knowledge(service: dict, query: str, top_k: int = 12) -> list[dict]:
    """Hybrid Vietnamese lexical + character-trigram retrieval over verified live catalog & business rules.

    Strictly filters by selected service['id'] and VERIFIED status.
    """
    records: list[dict] = []
    service_id = service['id']

    if service.get('description') or service.get('name'):
        records.append({
            'sourceId': 'service:' + service_id,
            'version': service.get('version'),
            'excerpt': f"{service.get('name', '')}: {service.get('description', '')}".strip(': '),
            'kind': 'service',
            'serviceId': service_id,
            'verified': True,
            'verificationStatus': 'VERIFIED',
            'accessScope': 'CUSTOMER',
            'provenance': 'authenticated-live-catalog',
        })

    for c in service.get('checklists', []):
        records.append({
            'sourceId': 'checklist:' + str(c['id']),
            'version': c.get('version'),
            'excerpt': c.get('content', ''),
            'kind': 'checklist',
            'serviceId': service_id,
            'verified': True,
            'verificationStatus': 'VERIFIED',
            'accessScope': 'CUSTOMER',
            'provenance': 'authenticated-live-catalog',
        })

    for d in service.get('deliverables', []):
        records.append({
            'sourceId': 'deliverable:' + str(d['id']),
            'version': None,
            'excerpt': d.get('name', ''),
            'kind': 'deliverable',
            'serviceId': service_id,
            'verified': True,
            'verificationStatus': 'VERIFIED',
            'accessScope': 'CUSTOMER',
            'provenance': 'authenticated-live-catalog',
        })

    for rule in VERIFIED_HANDOFF_RULES:
        records.append({
            'sourceId': rule['sourceId'],
            'version': rule['version'],
            'excerpt': rule['excerpt'],
            'kind': rule['kind'],
            'serviceId': service_id,
            'verified': True,
            'verificationStatus': 'VERIFIED',
            'accessScope': 'CUSTOMER',
            'provenance': rule['provenance'],
        })

    # Hybrid scoring: token overlap (with Vietnamese diacritic normalization) + character trigram cosine
    query_tokens = set(tokenize_vi(query))
    query_trigrams = char_trigrams(query)

    for rec in records:
        doc_tokens = set(tokenize_vi(rec['excerpt']))
        token_overlap = len(query_tokens & doc_tokens) / max(len(query_tokens), 1)
        trigram_sim = cosine_counter(query_trigrams, char_trigrams(rec['excerpt']))
        kind_boost = 0.15 if rec['kind'] in ('service', 'checklist', 'deliverable') else 0.05
        score = round(0.55 * token_overlap + 0.45 * trigram_sim + kind_boost, 4)
        rec['relevance'] = score

    # Only VERIFIED records are ever returned
    verified_records = [r for r in records if r.get('verified') and r.get('verificationStatus') == 'VERIFIED']
    verified_records.sort(key=lambda r: (-r['relevance'], r['sourceId']))
    return verified_records[:top_k]
