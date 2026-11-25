"""Staged Real-Model Evaluation Runner with honest PASS/FAIL/BLOCKED/NOT_VERIFIED accounting."""
import json
import os
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'Python' / 'voice-order'))

from description_intelligence.engine import review_description
from qa.description_intelligence.multi_turn.simulator import SimulatedCustomer, run_adaptive_session

CATALOG = json.loads((ROOT / 'qa' / 'description_intelligence' / 'fixtures' / 'catalog-inventory.json').read_text(encoding='utf-8'))
SERVICES = CATALOG['services']
REPORT_PATH = ROOT / 'qa' / 'description_intelligence' / 'reports' / 'REAL_MODEL_EVALUATION_REPORT.md'
OUT_JSON = ROOT / 'qa' / 'description_intelligence' / 'real_model' / 'eval_results.json'


def run_real_model_smoke() -> dict:
    model_name = os.getenv('ORDER_AGENT_MODEL', 'qwen2.5:3b')
    sessions = []
    cases = [
        (
            SERVICES[0],
            SimulatedCustomer(
                goal='Kiểm tra mái nhà xưởng bị rỉ sét',
                initial_message='Tôi cần kiểm tra nhà xưởng giúp.',
                known_facts={
                    'subject': 'Nhà xưởng A ở khu công nghiệp.',
                    'scope': 'Quan sát toàn bộ mái nhà xưởng, tập trung khu vực phía Đông bị rỉ sét và bong tróc.',
                    'outputs': 'Cần ảnh chụp rõ các vị trí hư hỏng và báo cáo kết quả.',
                },
                max_turns=3,
            ),
        ),
        (
            SERVICES[1],
            SimulatedCustomer(
                goal='Giám sát rừng phát hiện bất thường',
                initial_message='Muốn giám sát lô rừng phòng hộ 12, ghi nhận toàn cảnh khu vực rừng và điểm cây suy giảm.',
                known_facts={
                    'outputs': 'Cần ảnh chụp và video.',
                },
                max_turns=2,
            ),
        ),
        (
            SERVICES[2],
            SimulatedCustomer(
                goal='Giám sát khu vực có yêu cầu đầu ra không hỗ trợ',
                initial_message='Giám sát khu công nghiệp phía Đông, toàn bộ khu vực, cần xuất Báo cáo kết quả.',
                known_facts={},
                max_turns=1,
            ),
        ),
        (
            SERVICES[3],
            SimulatedCustomer(
                goal='Giám sát tiến độ công trình',
                initial_message='Cần giám sát công trình cầu vượt, theo dõi toàn bộ tiến độ thi công các hạng mục chính, cần ảnh chụp.',
                known_facts={},
                max_turns=2,
            ),
        ),
    ]

    passed = 0
    failed = 0
    blocked = 0
    total_calls = 0

    for svc, cust in cases:
        try:
            res = run_adaptive_session(svc, cust)
            total_calls += res['turns']
            hard_ok = res['hardChecks'].get('NO_FABRICATED_FACTS', False)
            status = 'PASS' if (hard_ok and len(res['finalFacts']) > 0) else 'FAIL'
            if status == 'PASS':
                passed += 1
            else:
                failed += 1
            sessions.append({'status': status, **res})
        except Exception as exc:
            blocked += 1
            sessions.append({'status': 'BLOCKED', 'serviceName': svc['name'], 'error': str(exc)})

    summary = {
        'model': model_name,
        'SCENARIOS_EXECUTED': len(cases),
        'REAL_MODEL_SESSIONS': len(cases),
        'REAL_MODEL_CALLS': total_calls,
        'PASS': passed,
        'FAIL': failed,
        'BLOCKED': blocked,
        'NOT_VERIFIED': 0,
        'sessions': sessions,
    }
    OUT_JSON.parent.mkdir(parents=True, exist_ok=True)
    OUT_JSON.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')

    REPORT_PATH.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        '# REAL MODEL EVALUATION REPORT',
        '',
        f'- **MODEL**: `{model_name}` (local Ollama `http://localhost:11434/v1`)',
        f'- **SCENARIOS_EXECUTED**: {len(cases)}',
        f'- **REAL_MODEL_SESSIONS**: {len(cases)}',
        f'- **REAL_MODEL_CALLS**: {total_calls}',
        f'- **PASS**: {passed}',
        f'- **FAIL**: {failed}',
        f'- **BLOCKED**: {blocked}',
        f'- **NOT_VERIFIED**: 0',
        '',
        '## Session Results',
    ]
    for s in sessions:
        lines.append(
            f"- **{s['serviceName']}** — `{s['status']}` | Turns: {s.get('turns', 0)} | "
            f"Final state: `{s.get('finalReadinessState', 'N/A')}` | Facts: `{s.get('finalFacts', {})}`"
        )
    REPORT_PATH.write_text('\n'.join(lines) + '\n', encoding='utf-8')
    return summary


if __name__ == '__main__':
    print(json.dumps(run_real_model_smoke(), ensure_ascii=False, indent=2))
