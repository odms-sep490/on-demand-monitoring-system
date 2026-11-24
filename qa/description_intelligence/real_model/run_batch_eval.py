"""Resumable Real-Model Batch Evaluator over the existing 5,200 scenario corpus.

Executes real local Qwen2.5:3b calls in resource-aware batches (20 -> 30 -> 50 -> 100),
recording full per-case evidence, separate extraction vs guardrail metrics, and
checkpointing after every case so runs can resume cleanly.
"""
import argparse
import json
import os
import sys
import time
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'Python' / 'voice-order'))

from description_intelligence.engine import review_description

SCENARIOS_PATH = ROOT / 'qa' / 'description_intelligence' / 'scenario_generation' / 'scenarios_5000.jsonl'
CATALOG_PATH = ROOT / 'qa' / 'description_intelligence' / 'fixtures' / 'catalog-inventory.json'
CHECKPOINT_PATH = ROOT / 'qa' / 'description_intelligence' / 'real_model' / 'batch_eval_checkpoint.jsonl'
SUMMARY_PATH = ROOT / 'qa' / 'description_intelligence' / 'real_model' / 'batch_eval_summary.json'


def load_services() -> dict[str, dict]:
    cat = json.loads(CATALOG_PATH.read_text(encoding='utf-8'))
    return {s['id']: s for s in cat['services']}


def select_balanced_scenarios(target_total: int = 200, include_held_out: bool = False) -> list[dict]:
    """Select a diverse, balanced sample across services, completeness, language, complexity."""
    all_rows = []
    with SCENARIOS_PATH.open('r', encoding='utf-8') as f:
        for line in f:
            row = json.loads(line)
            if not include_held_out and row.get('split') == 'held_out':
                continue
            all_rows.append(row)

    # Deduplicate by (serviceId, initialInput) so we test genuinely diverse utterances
    seen_inputs: set[tuple[str, str]] = set()
    unique_rows = []
    for r in all_rows:
        key = (r['serviceId'], r['initialInput'])
        if key not in seen_inputs:
            seen_inputs.add(key)
            unique_rows.append(r)

    # Round-robin across (serviceName, completeness, languageStyle) buckets
    buckets: dict[tuple[str, str], list[dict]] = defaultdict(list)
    for r in unique_rows:
        buckets[(r['serviceName'], r['completeness'])].append(r)

    selected: list[dict] = []
    keys = sorted(buckets.keys())
    idx = 0
    while len(selected) < target_total and any(buckets.values()):
        k = keys[idx % len(keys)]
        if buckets[k]:
            selected.append(buckets[k].pop(0))
        idx += 1
        if idx > len(keys) * (target_total + 10):
            break
    return selected


def load_completed_ids() -> set[str]:
    done: set[str] = set()
    if not CHECKPOINT_PATH.exists():
        return done
    with CHECKPOINT_PATH.open('r', encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                done.add(json.loads(line)['caseId'])
            except Exception:
                continue
    return done


def run_batch(limit: int, total_pool: int = 200) -> dict:
    services = load_services()
    pool = select_balanced_scenarios(target_total=total_pool, include_held_out=False)
    done_ids = load_completed_ids()
    todo = [r for r in pool if r['caseId'] not in done_ids][:limit]

    CHECKPOINT_PATH.parent.mkdir(parents=True, exist_ok=True)
    model_name = os.getenv('ORDER_AGENT_MODEL', 'qwen2.5:3b')

    with CHECKPOINT_PATH.open('a', encoding='utf-8') as out:
        for i, scen in enumerate(todo, 1):
            svc = services[scen['serviceId']]
            stmt = scen['initialInput']
            t0 = time.monotonic()
            try:
                action = 'generate' if scen['completeness'] in ('fully_detailed', 'partially_complete') else 'review'
                res = review_description(svc, stmt, [stmt], action=action)
                elapsed = round(time.monotonic() - t0, 2)
                hard = res['hardChecks']
                # Separate extraction quality from guardrail blocking
                no_fab = hard.get('NO_FABRICATED_FACTS', False)
                has_facts = len(res['facts']) > 0
                is_unsupported_scen = scen.get('businessComplexity') == 'unsupported_capability'
                is_vague_scen = scen.get('completeness') in ('extremely_short', 'long_but_vague')
                if is_unsupported_scen:
                    # For unsupported scenarios, flagging unsupportedRequests is expected behavior
                    passed = no_fab and (len(res['unsupportedRequests']) > 0 or has_facts)
                elif is_vague_scen:
                    # For extremely short/vague inputs, asking a useful clarification without fabricating is correct
                    passed = no_fab and bool(res['nextQuestion']) and res['readinessState'] in (
                        'INSUFFICIENT_INFORMATION', 'MATERIAL_CLARIFICATION_REQUIRED',
                    )
                else:
                    passed = no_fab and has_facts
                record = {
                    'caseId': scen['caseId'],
                    'split': scen['split'],
                    'serviceName': scen['serviceName'],
                    'persona': scen['persona'],
                    'completeness': scen['completeness'],
                    'languageStyle': scen['languageStyle'],
                    'businessComplexity': scen['businessComplexity'],
                    'initialInput': stmt,
                    'action': action,
                    'status': 'PASS' if passed else 'FAIL',
                    'readinessState': res['readinessState'],
                    'facts': {k: v['value'] for k, v in res['facts'].items()},
                    'rejectedFacts': res['rejectedFacts'],
                    'unsupportedRequests': res['unsupportedRequests'],
                    'contradictions': res['contradictions'],
                    'ragSources': [s['sourceId'] for s in res['sourceReferences'][:5]],
                    'nextQuestionField': res['nextQuestionField'],
                    'nextQuestion': res['nextQuestion'],
                    'generatedDescription': res['generatedDescription'],
                    'hardChecks': hard,
                    'durationSeconds': elapsed,
                    'error': None,
                }
            except Exception as exc:
                elapsed = round(time.monotonic() - t0, 2)
                record = {
                    'caseId': scen['caseId'],
                    'split': scen['split'],
                    'serviceName': scen['serviceName'],
                    'completeness': scen['completeness'],
                    'languageStyle': scen['languageStyle'],
                    'businessComplexity': scen['businessComplexity'],
                    'initialInput': stmt,
                    'status': 'BLOCKED',
                    'durationSeconds': elapsed,
                    'error': str(exc),
                }
            out.write(json.dumps(record, ensure_ascii=False) + '\n')
            out.flush()
            print(f"[{i}/{len(todo)}] {record['caseId']} ({record['serviceName']}, {record['completeness']}) -> {record['status']} ({elapsed}s)", flush=True)

    return summarize_checkpoint(model_name)


def summarize_checkpoint(model_name: str = 'qwen2.5:3b') -> dict:
    rows = []
    if CHECKPOINT_PATH.exists():
        with CHECKPOINT_PATH.open('r', encoding='utf-8') as f:
            for line in f:
                if line.strip():
                    rows.append(json.loads(line))
    counts = Counter(r['status'] for r in rows)
    rejected_cases = [r for r in rows if r.get('rejectedFacts')]
    avg_sec = round(sum(r.get('durationSeconds', 0) for r in rows) / max(len(rows), 1), 2)
    by_service = defaultdict(lambda: Counter())
    for r in rows:
        by_service[r['serviceName']][r['status']] += 1

    summary = {
        'model': model_name,
        'REAL_MODEL_CASES_EXECUTED': len(rows),
        'REAL_MODEL_CALLS': len(rows),
        'PASS': counts.get('PASS', 0),
        'FAIL': counts.get('FAIL', 0),
        'BLOCKED': counts.get('BLOCKED', 0),
        'GROUNDING_CLEAN_RATE': round((len(rows) - len(rejected_cases)) / max(len(rows), 1), 4),
        'GUARDRAIL_BLOCKED_CASES': len(rejected_cases),
        'AVG_LATENCY_SECONDS': avg_sec,
        'BY_SERVICE': {k: dict(v) for k, v in by_service.items()},
    }
    SUMMARY_PATH.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
    return summary


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--limit', type=int, default=20)
    parser.add_argument('--pool', type=int, default=200)
    args = parser.parse_args()
    summary = run_batch(limit=args.limit, total_pool=args.pool)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
