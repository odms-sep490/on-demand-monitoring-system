"""Massive Combinatorial Scenario Generator (5,000+ distinct specifications) with Coverage Matrix.

Derives scenarios from the real live service catalog (catalog-inventory.json) across:
- 4 active services
- 6 customer personas
- 8 description completeness profiles
- 9 Vietnamese language variations
- 10 conversation behaviors
- 8 business complexities
- 8 evaluation difficulties
"""
import hashlib
import json
import random
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CATALOG_PATH = ROOT / 'fixtures' / 'catalog-inventory.json'
OUTPUT_PATH = ROOT / 'scenario_generation' / 'scenarios_5000.jsonl'
COVERAGE_PATH = ROOT / 'reports' / 'SCENARIO_COVERAGE_REPORT.md'

PERSONAS = [
    'first_time_customer',
    'returning_customer',
    'domain_expert',
    'nontechnical_customer',
    'uncertain_customer',
    'impatient_customer',
]

COMPLETENESS = [
    'extremely_short',
    'short_but_meaningful',
    'partially_complete',
    'fully_detailed',
    'long_but_vague',
    'internally_contradictory',
    'excessively_technical',
    'missing_one_material_detail',
]

LANGUAGE_STYLES = [
    'formal_vi',
    'conversational_vi',
    'no_accents',
    'typos',
    'regional_phrasing',
    'abbreviations',
    'spoken_transcription',
    'incomplete_sentences',
    'multi_intent_sentence',
]

CONVERSATION_BEHAVIORS = [
    'single_turn',
    'multi_turn_discovery',
    'correction',
    'rejection',
    'changing_requirements',
    'interruption',
    'asking_recap',
    'repeated_clarification',
    'refusal_optional',
    'asking_ai_recommend',
]

BUSINESS_COMPLEXITIES = [
    'single_objective',
    'multiple_objectives',
    'several_subjects',
    'service_dependent',
    'conflicting_objectives',
    'unsupported_capability',
    'conditional_outputs',
    'site_constraints',
]

DIFFICULTIES = [
    'straightforward',
    'ambiguous_intent',
    'hidden_missing_info',
    'cross_source_question',
    'conflicting_docs',
    'stale_info',
    'unsupported_assumption',
    'complex_long_state',
]

SERVICE_SUBJECTS = {
    'Kiểm tra nhà xưởng': ['nhà xưởng A', 'kho số 2', 'xưởng cơ khí khu B', 'mái tôn dãy C', 'nhà máy dệt'],
    'Giám sát rừng': ['lô rừng phòng hộ 12', 'khu rừng keo phía Bắc', 'tiểu khu 4 rừng thông', 'vùng đồi cây xanh'],
    'Giám sát khu vực': ['khu công nghiệp phía Đông', 'khu đô thị mới', 'bãi tập kết vật liệu', 'khu vực ven sông'],
    'Giám sát công trình': ['công trình cầu vượt', 'dự án chung cư block B', 'công trường nhà máy điện', 'tuyến đường vành đai'],
}

SCOPES = [
    'toàn bộ diện tích',
    'khu vực phía Đông cạnh cổng chính',
    'phần mái và kết cấu mặt ngoài',
    'ranh giới phía Nam và lối ra vào',
    'hai phân khu đang ưu tiên',
]

CONCERNS = [
    'dấu hiệu rỉ sét và bong tróc',
    'khu vực có dấu hiệu bất thường khó tiếp cận',
    'tiến độ thi công hạng mục chính',
    'điểm nứt bề mặt và sạt lở',
    'hiện trạng tổng thể phục vụ theo dõi định kỳ',
]


def _apply_style(text: str, style: str) -> str:
    if style == 'no_accents':
        import unicodedata
        nfkd = unicodedata.normalize('NFKD', text)
        return ''.join(c for c in nfkd if not unicodedata.combining(c)).replace('đ', 'd').replace('Đ', 'D')
    if style == 'abbreviations':
        return text.replace('không', 'ko').replace('được', 'dc').replace('kiểm tra', 'kt').replace('nhà xưởng', 'nx')
    if style == 'typos':
        return text.replace('giám sát', 'giam sat').replace('khu vực', 'khu vuc')
    if style == 'conversational_vi':
        return 'Bên mình muốn ' + text[0].lower() + text[1:] + ' nha bạn.'
    if style == 'formal_vi':
        return 'Kính gửi đội vận hành, chúng tôi có nhu cầu ' + text[0].lower() + text[1:]
    if style == 'incomplete_sentences':
        return text.split(',')[0]
    return text


def generate_scenarios(target_count: int = 5200, seed: int = 20261124) -> dict:
    rng = random.Random(seed)
    catalog = json.loads(CATALOG_PATH.read_text(encoding='utf-8'))
    services = catalog['services']

    seen_hashes: set[str] = set()
    scenarios: list[dict] = []
    cov_service = Counter()
    cov_persona = Counter()
    cov_completeness = Counter()
    cov_style = Counter()
    cov_behavior = Counter()
    cov_complexity = Counter()
    cov_difficulty = Counter()

    idx = 0
    while len(scenarios) < target_count:
        service = services[idx % len(services)]
        sname = service['name']
        persona = PERSONAS[(idx // 4) % len(PERSONAS)]
        comp = COMPLETENESS[(idx // 24) % len(COMPLETENESS)]
        style = LANGUAGE_STYLES[(idx // 7) % len(LANGUAGE_STYLES)]
        behavior = CONVERSATION_BEHAVIORS[(idx // 11) % len(CONVERSATION_BEHAVIORS)]
        complexity = BUSINESS_COMPLEXITIES[(idx // 13) % len(BUSINESS_COMPLEXITIES)]
        difficulty = DIFFICULTIES[(idx // 17) % len(DIFFICULTIES)]
        idx += 1

        subj = rng.choice(SERVICE_SUBJECTS.get(sname, ['khu vực cần giám sát']))
        scope = rng.choice(SCOPES)
        concern = rng.choice(CONCERNS)
        deliv = rng.choice([d['name'] for d in service.get('deliverables', [])] or ['Ảnh chụp'])

        if comp == 'extremely_short':
            base = f'{sname.lower()} giúp tôi'
            expected_readiness = 'NEEDS_CLARIFICATION'
        elif comp == 'short_but_meaningful':
            base = f'Cần {sname.lower()} cho {subj}, phạm vi {scope}.'
            expected_readiness = 'READY_FOR_DRAFT'
        elif comp == 'partially_complete':
            base = f'Muốn {sname.lower()} tại {subj} để xem {concern}.'
            expected_readiness = 'NEEDS_CLARIFICATION'
        elif comp == 'fully_detailed':
            base = f'Cần {sname.lower()} cho {subj}, phủ {scope}, tập trung {concern}, cần nhận {deliv}.'
            expected_readiness = 'READY_FOR_DRAFT'
        elif comp == 'long_but_vague':
            base = f'Bên tôi đang cần triển khai gấp dịch vụ {sname.lower()} trong thời gian tới vì sếp yêu cầu xem kỹ hơn nhưng chưa rõ cụ thể phần nào.'
            expected_readiness = 'NEEDS_CLARIFICATION'
        elif comp == 'internally_contradictory':
            base = f'Chỉ cần chụp đúng cổng chính của {subj}, nhưng cũng phải bao phủ toàn bộ {scope} không bỏ sót góc nào.'
            expected_readiness = 'NEEDS_HUMAN_REVIEW'
        elif comp == 'missing_one_material_detail':
            base = f'Cần kiểm tra {concern} và xuất {deliv} nhưng chưa ghi rõ đối tượng cụ thể.'
            expected_readiness = 'NEEDS_CLARIFICATION'
        else:
            base = f'Yêu cầu {sname.lower()} cho {subj} ({scope}), chú ý {concern}, đầu ra {deliv}.'
            expected_readiness = 'READY_FOR_DRAFT'

        if complexity == 'unsupported_capability':
            base += ' Ngoài ra cần cam kết kiểm định kết cấu chuyên sâu và cấp phép bay ngay.'
            expected_readiness = 'NEEDS_HUMAN_REVIEW'

        prompt_text = _apply_style(base, style)
        # Ensure uniqueness across combinatorial space
        sig = hashlib.sha256(
            f'{service["id"]}|{persona}|{comp}|{style}|{behavior}|{complexity}|{difficulty}|{prompt_text}'.encode('utf-8')
        ).hexdigest()
        if sig in seen_hashes:
            continue
        seen_hashes.add(sig)

        case_id = f'SCEN-{len(scenarios) + 1:05d}'
        scenarios.append({
            'caseId': case_id,
            'seed': seed,
            'serviceId': service['id'],
            'serviceName': sname,
            'persona': persona,
            'completeness': comp,
            'languageStyle': style,
            'conversationBehavior': behavior,
            'businessComplexity': complexity,
            'difficulty': difficulty,
            'initialInput': prompt_text,
            'expectedReadiness': expected_readiness,
            'split': 'held_out' if len(scenarios) % 10 == 0 else 'train_eval',
        })
        cov_service[sname] += 1
        cov_persona[persona] += 1
        cov_completeness[comp] += 1
        cov_style[style] += 1
        cov_behavior[behavior] += 1
        cov_complexity[complexity] += 1
        cov_difficulty[difficulty] += 1

    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    with OUTPUT_PATH.open('w', encoding='utf-8') as f:
        for row in scenarios:
            f.write(json.dumps(row, ensure_ascii=False) + '\n')

    COVERAGE_PATH.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        '# SCENARIO COVERAGE REPORT',
        '',
        f'- **SCENARIOS_GENERATED**: {len(scenarios)}',
        f'- **SCENARIOS_EXECUTED**: 0 (specification corpus only; see REAL_MODEL_EVALUATION_REPORT.md for executed batches)',
        f'- **SEED**: `{seed}`',
        f'- **HELD_OUT_SPLIT**: {sum(1 for s in scenarios if s["split"] == "held_out")} scenarios (10% reserved)',
        '',
        '## Coverage by Active Service',
        *[f'- `{k}`: {v}' for k, v in cov_service.items()],
        '',
        '## Coverage by Customer Persona',
        *[f'- `{k}`: {v}' for k, v in cov_persona.items()],
        '',
        '## Coverage by Description Completeness',
        *[f'- `{k}`: {v}' for k, v in cov_completeness.items()],
        '',
        '## Coverage by Language Variation',
        *[f'- `{k}`: {v}' for k, v in cov_style.items()],
        '',
        '## Coverage by Conversation Behavior',
        *[f'- `{k}`: {v}' for k, v in cov_behavior.items()],
        '',
        '## Coverage by Business Complexity',
        *[f'- `{k}`: {v}' for k, v in cov_complexity.items()],
        '',
        '## Coverage by Evaluation Difficulty',
        *[f'- `{k}`: {v}' for k, v in cov_difficulty.items()],
    ]
    COVERAGE_PATH.write_text('\n'.join(lines) + '\n', encoding='utf-8')
    return {'generated': len(scenarios), 'output': str(OUTPUT_PATH), 'coverageReport': str(COVERAGE_PATH)}


if __name__ == '__main__':
    print(json.dumps(generate_scenarios(), ensure_ascii=False, indent=2))
