"""Independent Description Quality Evaluator.

Decoupled from description generation. Never scores based on text length.
Enforces hard failure conditions that override any aggregate score.
"""
import re
from .knowledge import build_business_capability_graph, strip_accents

WEIGHTS = {
    'objective': 15,
    'subject': 15,
    'scope': 15,
    'concerns': 15,
    'outputs': 15,
    'handoff': 10,
    'consistency': 10,
    'readability': 5,
}

# Known deliverable keywords to verify against the live service's actual deliverables
DELIVERABLE_KEYWORDS = {
    'báo cáo': ['báo cáo', 'bao cao', 'report'],
    'ảnh chụp': ['ảnh', 'anh chup', 'hình ảnh', 'hinh anh', 'photo'],
    'video': ['video', 'quay phim', 'clip'],
}

UNSUPPORTED_PROMISE_PATTERN = re.compile(
    r'\b(đã cấp phép|đã được duyệt|cam kết 100%|bảo đảm tuyệt đối|kiểm định kết cấu chuyên sâu|phun thuốc|vận chuyển hàng)\b',
    re.I,
)


def check_service_compatibility(
    service: dict,
    facts: dict[str, dict],
    statements: list[str],
    model_unsupported: list[str],
) -> list[str]:
    """Verify customer requests against the live service's actual deliverables and boundaries."""
    unsupported = list(dict.fromkeys(u.strip() for u in model_unsupported if u.strip()))
    graph = build_business_capability_graph(service)
    supported_norm = [strip_accents(d.lower()) for d in graph['supportedDeliverables']]
    combined_text = ' '.join(statements)
    combined_norm = strip_accents(combined_text.lower())

    # Check if customer explicitly requested a deliverable not supported by this service
    if 'outputs' in facts:
        out_norm = strip_accents(facts['outputs']['value'].lower())
        for label, keywords in DELIVERABLE_KEYWORDS.items():
            label_norm = strip_accents(label)
            requested = any(strip_accents(kw) in out_norm for kw in keywords)
            supported = any(label_norm in s or s in label_norm for s in supported_norm)
            if requested and not supported:
                msg = (
                    f"Dịch vụ '{graph['serviceName']}' hiện chỉ hỗ trợ đầu ra: "
                    f"{', '.join(graph['supportedDeliverables'])}; yêu cầu '{label}' cần đội vận hành xác nhận."
                )
                if msg not in unsupported:
                    unsupported.append(msg)

    # Check for clearly out-of-scope / unverified operational promises in customer text
    match = UNSUPPORTED_PROMISE_PATTERN.search(combined_text)
    if match:
        msg = f"Nội dung '{match.group(0)}' nằm ngoài phạm vi xác nhận tự động, cần đội vận hành kiểm tra."
        if msg not in unsupported:
            unsupported.append(msg)

    return unsupported


def evaluate_description(
    service: dict,
    original_text: str,
    statements: list[str],
    facts: dict[str, dict],
    missing_critical: list[str],
    optional_improvements: list[str],
    contradictions: list[str],
    unsupported_requests: list[str],
    rejected_facts: list[dict],
    generated_description: str,
    source_references: list[dict],
    readiness_state: str,
    next_question: str,
) -> dict:
    """Evaluate requirement and description quality independently from generation."""
    dimensions: dict[str, dict] = {}
    confirmed_strengths: list[str] = []

    for field, weight in WEIGHTS.items():
        if field in ('handoff', 'consistency', 'readability'):
            # Subjective dimensions remain uncalibrated until judge calibration passes
            dimensions[field] = {
                'score': None,
                'maximum': weight,
                'evidence': [],
            }
        else:
            present = field in facts
            dimensions[field] = {
                'score': weight if present else 0,
                'maximum': weight,
                'evidence': [facts[field]['quote']] if present else [],
            }
            if present:
                confirmed_strengths.append(f"{field}: {facts[field]['value']}")

    hard_checks = {
        'NO_FABRICATED_FACTS': len(rejected_facts) == 0,
        'NO_CRITICAL_CONTRADICTIONS': len(contradictions) == 0,
        'NO_UNVERIFIED_CAPABILITY_PROMISES': len(unsupported_requests) == 0,
        'CUSTOMER_CONFIRMED_SCOPE': len(missing_critical) == 0,
        'SERVICE_COMPATIBLE': len(unsupported_requests) == 0,
    }
    all_hard_passed = all(hard_checks.values())

    # Deterministic factual coverage score (0-75 normalized to 0-100) — never based on text length
    factual_earned = sum(
        d['score'] for k, d in dimensions.items()
        if isinstance(d['score'], (int, float))
    )
    factual_max = sum(
        d['maximum'] for k, d in dimensions.items()
        if isinstance(d['score'], (int, float))
    )
    factual_score = round((factual_earned / factual_max) * 100) if factual_max else 0

    return {
        'dimensions': dimensions,
        'overallScore': factual_score if all_hard_passed else min(factual_score, 49),
        'score': None,  # Preserve uncalibrated subjective score contract
        'calibrationStatus': 'UNVERIFIED',
        'confirmedStrengths': confirmed_strengths,
        'criticalMissingInformation': missing_critical,
        'optionalImprovements': optional_improvements,
        'unsupportedClaims': unsupported_requests,
        'contradictions': contradictions,
        'sourceReferences': source_references,
        'readinessStatus': readiness_state,
        'recommendedNextQuestion': next_question,
        'hardChecks': hard_checks,
        'evaluationEvidence': {
            'originalTextPresent': bool((original_text or '').strip()),
            'turnCount': len(statements),
            'confirmedFactCount': len(facts),
            'rejectedFactCount': len(rejected_facts),
            'hasGeneratedDescription': bool((generated_description or '').strip()),
        },
    }
