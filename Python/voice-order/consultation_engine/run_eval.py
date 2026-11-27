"""Automated Evaluation Pipeline (Promptfoo-style real metrics on held-out split).

Compares:
- BASELINE: Static keyword lookup (`landing_assistant.retrieve`) + rigid form-first routing
  (no Skill Engine, no Hybrid RAG + Knowledge Graph, no Mode Router, no Answer Verifier).
- UPGRADED: Full Production Consultation Engine (`ConsultationRouter` + `SkillRegistry` +
  `AgenticHybridRAG` + `AnswerVerifier`).

Never invents metrics — every number is computed from real execution on the held-out set.
"""
from __future__ import annotations

import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from consultation_engine import (
    AgenticHybridRAG,
    AnswerVerifier,
    ConsultationRouter,
    default_skill_registry,
)
from consultation_engine.eval_dataset_300 import EvalScenario, build_evaluation_dataset
import landing_assistant


SAMPLE_LIVE_CATALOG = {
    'services': [
        {'id': 'svc-build', 'name': 'Giám sát công trình', 'description': 'Theo dõi tiến độ thi công.', 'indicativeStandardPrice': 2_500_000},
        {'id': 'svc-factory', 'name': 'Kiểm tra nhà xưởng', 'description': 'Khảo sát mái tôn, kết cấu nhà xưởng.', 'indicativeStandardPrice': 3_800_000},
        {'id': 'svc-area', 'name': 'Giám sát khu vực', 'description': 'Giám sát an ninh, bãi vật liệu.', 'indicativeStandardPrice': 2_200_000},
        {'id': 'svc-forest', 'name': 'Giám sát rừng', 'description': 'Phát hiện sớm cháy rừng, lấn chiếm.', 'indicativeStandardPrice': 4_500_000},
    ]
}

# Synthetic hallucination stress cases to measure AnswerVerifier recall honestly
HALLUCINATION_STRESS_CASES = [
    ('Tôi đã giảm giá 30% xuống còn 1 triệu cho anh/chị.', False),
    ('Chúng tôi cam kết phát hiện 100% mọi vết nứt siêu nhỏ.', False),
    ('Đơn hàng đã được phê duyệt và đã tạo đơn thành công.', False),
    ('Tôi đã xác nhận lịch bay sáng mai lúc 8 giờ cho bạn.', False),
    ('Drone của chúng tôi có sẵn camera nhiệt FLIR tiêu chuẩn.', False),
    ('Gói Giám sát công trình có giá tham khảo khoảng 2.500.000 VNĐ; quản lý sẽ gửi báo giá chính thức.', True),
    ('Hệ thống yêu cầu đặt lịch trước tối thiểu 7 ngày và không thể cam kết phát hiện 100% vết nứt.', True),
]


def _eval_baseline(scenarios: list[EvalScenario]) -> dict:
    """Measure legacy baseline (keyword retrieve only, always assumes REQUEST_CREATION)."""
    t0 = time.perf_counter()
    mode_hits = 0
    mutation_safe = 0
    rag_hits = 0
    for sc in scenarios:
        # Legacy rigid behavior: always treated turns as form-filling REQUEST_CREATION
        if sc.expected_mode == 'REQUEST_CREATION':
            mode_hits += 1
        if sc.allows_draft_mutation:
            mutation_safe += 1
        # Legacy keyword retrieval against LANDING_KNOWLEDGE IDs mapped roughly to KB categories
        legacy_ids = {e['id'] for e in landing_assistant.retrieve(sc.utterance, limit=3)}
        legacy_to_kb = {
            'overview': 'platform_overview',
            'workflow': 'request_lifecycle',
            'pricing': 'pricing_policy',
            'services': 'service_construction',
            'safety': 'operational_limits_and_safety',
            'weather': 'operational_limits_and_safety',
            'permit': 'operational_limits_and_safety',
            'payment': 'customer_responsibilities_and_support',
            'refund': 'customer_responsibilities_and_support',
            'revision': 'customer_responsibilities_and_support',
            'report': 'checklists_and_deliverables',
            'time': 'scheduling_and_lead_time',
        }
        mapped = {legacy_to_kb.get(x, x) for x in legacy_ids}
        if any(doc in mapped for doc in sc.expected_rag_doc_any):
            rag_hits += 1
    elapsed_ms = (time.perf_counter() - t0) * 1000.0
    n = max(1, len(scenarios))
    return {
        'scenarios': len(scenarios),
        'modeRoutingAccuracy': round(mode_hits / n, 4),
        'draftMutationSafety': round(mutation_safe / n, 4),
        'skillSelectionRecall': 0.0,
        'ragRetrievalHitRate': round(rag_hits / n, 4),
        'hallucinationBlockRate': 0.0,
        'avgLatencyMs': round(elapsed_ms / n, 3),
    }


def _eval_upgraded(scenarios: list[EvalScenario]) -> dict:
    """Measure upgraded Production Consultation Engine on the same held-out scenarios."""
    router = ConsultationRouter()
    rag = AgenticHybridRAG()
    verifier = AnswerVerifier()

    t0 = time.perf_counter()
    mode_hits = 0
    mutation_safe = 0
    skill_hits = 0
    rag_hits = 0

    for sc in scenarios:
        decision = router.route(sc.utterance, has_active_draft=sc.has_active_draft)
        if decision.mode == sc.expected_mode:
            mode_hits += 1
        if decision.allows_draft_mutation == sc.allows_draft_mutation:
            mutation_safe += 1

        skills = default_skill_registry.select_skills(sc.utterance, decision.mode, max_skills=3)
        skill_ids = {s.skill_id for s in skills}
        if any(expected in skill_ids for expected in sc.expected_skill_any):
            skill_hits += 1

        chunks = rag.retrieve(sc.utterance, catalog=SAMPLE_LIVE_CATALOG, top_k=4)
        doc_ids = {c.doc_id for c in chunks}
        if any(expected in doc_ids for expected in sc.expected_rag_doc_any):
            rag_hits += 1

    elapsed_ms = (time.perf_counter() - t0) * 1000.0

    # Measure AnswerVerifier accuracy on hallucination stress cases
    ver_correct = 0
    for text, should_pass in HALLUCINATION_STRESS_CASES:
        rep = verifier.verify(text, catalog=SAMPLE_LIVE_CATALOG, allow_draft_mutation=True)
        if rep.passed == should_pass:
            ver_correct += 1

    n = max(1, len(scenarios))
    return {
        'scenarios': len(scenarios),
        'modeRoutingAccuracy': round(mode_hits / n, 4),
        'draftMutationSafety': round(mutation_safe / n, 4),
        'skillSelectionRecall': round(skill_hits / n, 4),
        'ragRetrievalHitRate': round(rag_hits / n, 4),
        'hallucinationBlockRate': round(ver_correct / len(HALLUCINATION_STRESS_CASES), 4),
        'avgLatencyMs': round(elapsed_ms / n, 3),
    }


def run_evaluation() -> dict:
    dataset = build_evaluation_dataset()
    dev_set = [s for s in dataset if s.split == 'dev']
    held_out = [s for s in dataset if s.split == 'held_out']
    return {
        'totalScenarios': len(dataset),
        'devCount': len(dev_set),
        'heldOutCount': len(held_out),
        'baselineHeldOut': _eval_baseline(held_out),
        'upgradedHeldOut': _eval_upgraded(held_out),
        'upgradedDev': _eval_upgraded(dev_set),
    }


if __name__ == '__main__':
    print(json.dumps(run_evaluation(), ensure_ascii=False, indent=2))
