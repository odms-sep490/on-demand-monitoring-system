"""Adaptive Multi-Turn Real-Model Customer Evaluation Runner.

Executes diverse multi-turn conversations against local Qwen2.5:3b where the simulated
customer reacts contextually to the AI's actual follow-up questions, storing complete
transcripts and verifying memory, grounding, stopping policy, and non-fabrication.
"""
import argparse
import json
import os
import sys
import time
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / 'Python' / 'voice-order'))

from qa.description_intelligence.multi_turn.simulator import SimulatedCustomer, run_adaptive_session

CATALOG = json.loads((ROOT / 'qa' / 'description_intelligence' / 'fixtures' / 'catalog-inventory.json').read_text(encoding='utf-8'))
SERVICES = CATALOG['services']
OUT_JSONL = ROOT / 'qa' / 'description_intelligence' / 'multi_turn' / 'multi_turn_transcripts.jsonl'
SUMMARY_JSON = ROOT / 'qa' / 'description_intelligence' / 'multi_turn' / 'multi_turn_summary.json'


def build_diverse_personas() -> list[dict]:
    """Build 16 distinct multi-turn conversation templates x 4 services = 64+ diverse sessions."""
    templates = [
        ('vague_to_clear', 'Tôi cần kiểm tra giúp.', {'subject': 'Nhà xưởng A ở khu công nghiệp', 'scope': 'Toàn bộ mái nhà xưởng', 'concerns': 'Khu vực phía Đông bị rỉ sét'}),
        ('detailed_single_turn', 'Cần giám sát khu vực nhà kho số 3, phạm vi toàn bộ mái và mặt ngoài, chú ý điểm thấm dột, xuất ảnh chụp.', {}),
        ('unknown_technical', 'Muốn bay drone giám sát nhưng tôi không biết độ cao hay đường bay thế nào.', {'subject': 'Lô rừng phòng hộ 12', 'scope': 'Toàn cảnh khu vực rừng'}),
        ('contradictory_correction', 'Kiểm tra nhà xưởng A toàn bộ mái.', {'subject': 'Xin lỗi, đổi lại thành nhà xưởng B nhé.', 'scope': 'Chỉ kiểm tra phần mái phía Tây thôi.'}),
        ('short_replies', 'Giám sát giúp.', {'subject': 'Cầu vượt ngã tư', 'scope': 'Toàn bộ nhịp chính', 'outputs': 'Ảnh chụp'}),
        ('changing_requirements', 'Theo dõi tiến độ tòa nhà C.', {'scope': 'Ban đầu chỉ xem tầng mái, giờ mở rộng toàn bộ mặt ngoài công trình.', 'outputs': 'Ảnh chụp và video'}),
        ('multiple_objectives', 'Vừa theo dõi tiến độ thi công vừa ghi nhận hiện trạng bề mặt khu tập kết.', {'subject': 'Công trình khu đô thị mới', 'scope': 'Toàn bộ phân khu A'}),
        ('ambiguous_scope', 'Xem giúp khu công nghiệp phía Đông xem có vấn đề gì không.', {'scope': 'Tập trung ranh giới phía Bắc và lối ra vào chính', 'concerns': 'Kiểm tra xâm lấn ranh giới'}),
        ('impatient_customer', 'Làm nhanh giúp tôi mô tả để đặt đơn luôn.', {'subject': 'Nhà xưởng số 2', 'scope': 'Toàn bộ diện tích mái'}),
        ('unsupported_expectation', 'Giám sát khu đất dự án, toàn bộ khu vực, cần cam kết kiểm định kết cấu chuyên sâu và cấp phép bay ngay.', {}),
        ('immediate_generate', 'Tạo mô tả luôn: giám sát lô rừng tiểu khu 5, ghi nhận toàn cảnh và điểm cây suy giảm, cần ảnh chụp.', {}),
        ('deeper_consultation', 'Bên mình lần đầu đặt dịch vụ, cần tư vấn thêm về phạm vi chụp.', {'subject': 'Xưởng cơ khí B', 'scope': 'Chưa rõ, nhờ đội vận hành tư vấn thêm'}),
        ('long_memory', 'Cần khảo sát khu vực bến cảng số 1.', {'subject': 'Bến cảng số 1', 'scope': 'Toàn bộ khu vực cầu cảng và bãi container', 'concerns': 'Điểm nứt bề mặt và khu vực khó tiếp cận', 'outputs': 'Ảnh chụp'}),
        ('no_accents_vi', 'can giam sat nha xuong so 4, toan bo mai bi ri set, xuat anh chup', {'scope': 'toan bo dien tich mai nha xuong so 4'}),
        ('speech_artifacts', 'ờ thì bên anh muốn bay drone kiểm tra cái... nhà xưởng C ấy, toàn bộ mái phía Nam bị dột', {'outputs': 'lấy ảnh chụp rõ nét'}),
        ('refusal_optional', 'Giám sát công trình đường cao tốc đoạn Km10.', {'scope': 'Toàn bộ mặt đường và taluy hai bên', 'constraints': 'Không có lưu ý gì thêm, bỏ qua phần này'}),
    ]
    sessions = []
    idx = 0
    for svc in SERVICES:
        for name, init_msg, facts in templates:
            idx += 1
            sessions.append({
                'sessionId': f'MT-{idx:03d}',
                'behavior': name,
                'service': svc,
                'initial_message': init_msg,
                'known_facts': facts,
                'max_turns': 3,
            })
    return sessions


def run_multi_turn(limit: int = 32) -> dict:
    plan = build_diverse_personas()
    done_ids: set[str] = set()
    if OUT_JSONL.exists():
        for line in OUT_JSONL.read_text(encoding='utf-8').splitlines():
            if line.strip():
                try:
                    done_ids.add(json.loads(line)['sessionId'])
                except Exception:
                    pass

    todo = [s for s in plan if s['sessionId'] not in done_ids][:limit]
    OUT_JSONL.parent.mkdir(parents=True, exist_ok=True)

    with OUT_JSONL.open('a', encoding='utf-8') as out:
        for i, item in enumerate(todo, 1):
            cust = SimulatedCustomer(
                goal=item['behavior'],
                known_facts=item['known_facts'],
                initial_message=item['initial_message'],
                max_turns=item['max_turns'],
            )
            try:
                res = run_adaptive_session(item['service'], cust)
                no_fab = res['hardChecks'].get('NO_FABRICATED_FACTS', False)
                has_facts = len(res['finalFacts']) > 0
                is_unsupported = item['behavior'] == 'unsupported_expectation'
                is_uncertain_consult = item['behavior'] == 'deeper_consultation'
                passed = no_fab and (has_facts or is_unsupported or is_uncertain_consult)
                record = {
                    'sessionId': item['sessionId'],
                    'behavior': item['behavior'],
                    'status': 'PASS' if passed else 'FAIL',
                    **res,
                }
            except Exception as exc:
                record = {
                    'sessionId': item['sessionId'],
                    'behavior': item['behavior'],
                    'serviceName': item['service']['name'],
                    'status': 'BLOCKED',
                    'turns': 0,
                    'error': str(exc),
                }
            out.write(json.dumps(record, ensure_ascii=False) + '\n')
            out.flush()
            print(f"[{i}/{len(todo)}] {record['sessionId']} ({record['behavior']} / {record['serviceName']}) -> {record['status']} ({record.get('turns', 0)} turns)", flush=True)

    return summarize_multi_turn()


def summarize_multi_turn() -> dict:
    rows = []
    if OUT_JSONL.exists():
        for line in OUT_JSONL.read_text(encoding='utf-8').splitlines():
            if line.strip():
                rows.append(json.loads(line))
    counts = Counter(r['status'] for r in rows)
    total_calls = sum(r.get('turns', 0) for r in rows)
    summary = {
        'model': os.getenv('ORDER_AGENT_MODEL', 'qwen2.5:3b'),
        'REAL_MODEL_SESSIONS_EXECUTED': len(rows),
        'REAL_MODEL_CALLS': total_calls,
        'PASS': counts.get('PASS', 0),
        'FAIL': counts.get('FAIL', 0),
        'BLOCKED': counts.get('BLOCKED', 0),
    }
    SUMMARY_JSON.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
    return summary


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--limit', type=int, default=32)
    args = parser.parse_args()
    print(json.dumps(run_multi_turn(limit=args.limit), ensure_ascii=False, indent=2))
