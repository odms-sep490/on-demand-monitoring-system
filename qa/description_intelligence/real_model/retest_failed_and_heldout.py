"""Retest all 36 previously failed cases + 50 unseen held-out scenarios on real Qwen2.5:3b."""
import json
import os
import sys
import time
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / 'Python' / 'voice-order'))

from description_intelligence.engine import review_description
from qa.description_intelligence.multi_turn.run_multi_turn_eval import build_diverse_personas
from qa.description_intelligence.multi_turn.simulator import SimulatedCustomer, run_adaptive_session

CATALOG = json.loads((ROOT / 'qa' / 'description_intelligence' / 'fixtures' / 'catalog-inventory.json').read_text(encoding='utf-8'))
SERVICES = {s['id']: s for s in CATALOG['services']}
SCENARIOS_PATH = ROOT / 'qa' / 'description_intelligence' / 'scenario_generation' / 'scenarios_5000.jsonl'
BATCH_CKPT = ROOT / 'qa' / 'description_intelligence' / 'real_model' / 'batch_eval_checkpoint.jsonl'
MT_CKPT = ROOT / 'qa' / 'description_intelligence' / 'multi_turn' / 'multi_turn_transcripts.jsonl'
OUT_JSON = ROOT / 'qa' / 'description_intelligence' / 'real_model' / 'retest_and_heldout_results.json'


def eval_single(scen: dict) -> dict:
    svc = SERVICES[scen['serviceId']]
    stmt = scen['initialInput']
    action = 'generate' if scen['completeness'] in ('fully_detailed', 'partially_complete') else 'review'
    t0 = time.monotonic()
    res = review_description(svc, stmt, [stmt], action=action)
    elapsed = round(time.monotonic() - t0, 2)
    no_fab = res['hardChecks'].get('NO_FABRICATED_FACTS', False)
    has_facts = len(res['facts']) > 0
    is_unsupported = scen.get('businessComplexity') == 'unsupported_capability'
    is_vague = scen.get('completeness') in ('extremely_short', 'long_but_vague')
    if is_unsupported:
        passed = no_fab and (len(res['unsupportedRequests']) > 0 or has_facts)
    elif is_vague:
        passed = no_fab and bool(res['nextQuestion']) and res['readinessState'] in (
            'INSUFFICIENT_INFORMATION', 'MATERIAL_CLARIFICATION_REQUIRED',
        )
    else:
        passed = no_fab and has_facts
    return {
        'caseId': scen['caseId'],
        'split': scen.get('split', 'train_eval'),
        'serviceName': scen['serviceName'],
        'completeness': scen['completeness'],
        'status': 'PASS' if passed else 'FAIL',
        'noFabricated': no_fab,
        'rejectedFacts': res['rejectedFacts'],
        'facts': {k: v['value'] for k, v in res['facts'].items()},
        'durationSeconds': elapsed,
    }


def main():
    all_scens = {}
    held_out = []
    seen_ho = set()
    for line in SCENARIOS_PATH.read_text(encoding='utf-8').splitlines():
        if not line.strip():
            continue
        r = json.loads(line)
        all_scens[r['caseId']] = r
        if r.get('split') == 'held_out':
            key = (r['serviceId'], r['initialInput'])
            if key not in seen_ho:
                seen_ho.add(key)
                held_out.append(r)

    # 1. Retest all 33 previously failed batch cases
    prev_batch_fails = [
        json.loads(l)['caseId']
        for l in BATCH_CKPT.read_text(encoding='utf-8').splitlines()
        if l.strip() and json.loads(l)['status'] != 'PASS'
    ]
    batch_retest = []
    for i, cid in enumerate(prev_batch_fails, 1):
        rec = eval_single(all_scens[cid])
        batch_retest.append(rec)
        print(f"[RETEST-BATCH {i}/{len(prev_batch_fails)}] {cid} -> {rec['status']} (rej={len(rec['rejectedFacts'])})", flush=True)

    # 2. Retest all 3 previously failed multi-turn sessions
    prev_mt_fails = [
        json.loads(l)['sessionId']
        for l in MT_CKPT.read_text(encoding='utf-8').splitlines()
        if l.strip() and json.loads(l)['status'] != 'PASS'
    ]
    personas = {p['sessionId']: p for p in build_diverse_personas()}
    mt_retest = []
    for i, sid in enumerate(prev_mt_fails, 1):
        item = personas[sid]
        cust = SimulatedCustomer(item['behavior'], item['known_facts'], item['initial_message'], item['max_turns'])
        res = run_adaptive_session(item['service'], cust)
        no_fab = res['hardChecks'].get('NO_FABRICATED_FACTS', False)
        has_facts = len(res['finalFacts']) > 0
        passed = no_fab and (has_facts or item['behavior'] in ('unsupported_expectation', 'deeper_consultation'))
        rec = {'sessionId': sid, 'behavior': item['behavior'], 'status': 'PASS' if passed else 'FAIL', 'turns': res['turns'], 'hardChecks': res['hardChecks'], 'finalFacts': res['finalFacts']}
        mt_retest.append(rec)
        print(f"[RETEST-MT {i}/{len(prev_mt_fails)}] {sid} ({item['behavior']}) -> {rec['status']}", flush=True)

    # 3. Run 50 unseen held-out scenarios
    held_out_sample = held_out[:50]
    ho_results = []
    for i, scen in enumerate(held_out_sample, 1):
        rec = eval_single(scen)
        ho_results.append(rec)
        print(f"[HELD-OUT {i}/{len(held_out_sample)}] {rec['caseId']} ({rec['completeness']}) -> {rec['status']}", flush=True)

    summary = {
        'model': os.getenv('ORDER_AGENT_MODEL', 'qwen2.5:3b'),
        'RETEST_33_BATCH': dict(Counter(r['status'] for r in batch_retest)),
        'RETEST_3_MT': dict(Counter(r['status'] for r in mt_retest)),
        'HELD_OUT_50': dict(Counter(r['status'] for r in ho_results)),
        'HELD_OUT_GROUNDING_CLEAN_RATE': round(sum(1 for r in ho_results if r['noFabricated']) / max(len(ho_results), 1), 4),
        'batchRetest': batch_retest,
        'mtRetest': mt_retest,
        'heldOut': ho_results,
    }
    OUT_JSON.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({k: v for k, v in summary.items() if k not in ('batchRetest', 'mtRetest', 'heldOut')}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
