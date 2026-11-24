"""Independent RAG Retrieval Quality Benchmark for Description Intelligence.

Evaluates the hybrid Vietnamese lexical + character-trigram retriever on:
- Semantic paraphrases & synonyms
- Vietnamese without accents & customer slang
- Complex multi-topic queries
- Cross-service isolation (serviceId filtering)
- Verified-source enforcement
- Retrieval latency (ms)
Measures Recall@1, Recall@3, Recall@5, Precision@3, MRR.
"""
import json
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'Python' / 'voice-order'))

from description_intelligence.knowledge import retrieve_verified_knowledge

CATALOG = json.loads((ROOT / 'qa' / 'description_intelligence' / 'fixtures' / 'catalog-inventory.json').read_text(encoding='utf-8'))
SERVICES = {s['name']: s for s in CATALOG['services']}
OUT_PATH = ROOT / 'qa' / 'description_intelligence' / 'reports' / 'RAG_BENCHMARK_RESULTS.json'

# Independent ground-truth benchmark queries (NOT derived from the retriever code)
BENCHMARK_CASES = [
    # 1. Semantic paraphrases
    {
        'id': 'RAG-01', 'category': 'paraphrase', 'service': 'Kiểm tra nhà xưởng',
        'query': 'muốn drone bay xem nóc xưởng bị gỉ sét với thấm nước không',
        'expected_kinds': ['service', 'checklist'], 'expected_prefix': ['service:', 'checklist:'],
    },
    {
        'id': 'RAG-02', 'category': 'paraphrase', 'service': 'Giám sát rừng',
        'query': 'theo dõi cây cối trong rừng xem chỗ nào bị chết héo hoặc chặt phá',
        'expected_kinds': ['service', 'checklist'], 'expected_prefix': ['service:', 'checklist:'],
    },
    # 2. Synonyms
    {
        'id': 'RAG-03', 'category': 'synonym', 'service': 'Giám sát công trình',
        'query': 'chụp hình tiến độ xây dựng dự án để nộp report cho chủ đầu tư',
        'expected_kinds': ['deliverable', 'service'], 'expected_prefix': ['deliverable:', 'service:'],
    },
    # 3. Vietnamese without accents
    {
        'id': 'RAG-04', 'category': 'no_accents', 'service': 'Kiểm tra nhà xưởng',
        'query': 'kiem tra mai nha xuong ket cau ben ngoai co bi nut khong',
        'expected_kinds': ['service', 'checklist'], 'expected_prefix': ['service:', 'checklist:'],
    },
    {
        'id': 'RAG-05', 'category': 'no_accents', 'service': 'Giám sát khu vực',
        'query': 'giam sat toan canh khu cong nghiep xuat video va hinh anh',
        'expected_kinds': ['deliverable', 'service'], 'expected_prefix': ['deliverable:', 'service:'],
    },
    # 4. Customer slang / informal
    {
        'id': 'RAG-06', 'category': 'slang', 'service': 'Giám sát khu vực',
        'query': 'cho flycam lượn 1 vòng quay clip toàn bộ khu đất giúp em nha',
        'expected_kinds': ['deliverable', 'service'], 'expected_prefix': ['deliverable:', 'service:'],
    },
    # 5. Staff-only / technical boundary questions
    {
        'id': 'RAG-07', 'category': 'boundary_rule', 'service': 'Kiểm tra nhà xưởng',
        'query': 'tôi không biết độ cao bay, đường bay hay xin giấy phép bay thế nào',
        'expected_kinds': ['limitation'], 'expected_prefix': ['rule:staff-only-boundary'],
    },
    {
        'id': 'RAG-08', 'category': 'boundary_rule', 'service': 'Giám sát công trình',
        'query': 'có cần ghi tọa độ kinh độ vĩ độ và lịch bay vào mô tả này không',
        'expected_kinds': ['limitation'], 'expected_prefix': ['rule:separate-form-fields'],
    },
    # 6. Unsupported capability / promise boundary
    {
        'id': 'RAG-09', 'category': 'unsupported_promise', 'service': 'Giám sát khu vực',
        'query': 'bên bạn có cam kết kiểm định kết cấu chuyên sâu và bảo đảm giấy phép bay không',
        'expected_kinds': ['limitation'], 'expected_prefix': ['rule:no-unverified-promises'],
    },
    # 7. Complex multi-topic
    {
        'id': 'RAG-10', 'category': 'multi_topic', 'service': 'Giám sát rừng',
        'query': 'vừa muốn chụp ảnh toàn cảnh lô rừng phòng hộ, vừa ghi nhận điểm sạt lở, không biết cấu hình drone',
        'expected_kinds': ['service', 'deliverable', 'limitation'], 'expected_prefix': ['service:', 'deliverable:', 'rule:'],
    },
    # 8. Cross-service disambiguation (same query on different services)
    {
        'id': 'RAG-11', 'category': 'cross_service', 'service': 'Giám sát khu vực',
        'query': 'cần xuất báo cáo kết quả và ảnh chụp',
        'expected_kinds': ['deliverable', 'service'], 'expected_prefix': ['deliverable:', 'service:'],
    },
    {
        'id': 'RAG-12', 'category': 'cross_service', 'service': 'Kiểm tra nhà xưởng',
        'query': 'cần xuất báo cáo kết quả và ảnh chụp',
        'expected_kinds': ['deliverable', 'service'], 'expected_prefix': ['deliverable:', 'service:'],
    },
    # 9. Unknown / out-of-domain business question
    {
        'id': 'RAG-13', 'category': 'unknown_domain', 'service': 'Giám sát công trình',
        'query': 'giá thuê xe cẩu và vật liệu xi măng hôm nay bao nhiêu',
        'expected_kinds': ['service', 'handoff_rule', 'limitation'], 'expected_prefix': ['service:', 'rule:'],
    },
]


def run_benchmark() -> dict:
    recall1, recall3, recall5, prec3, mrr_sum = 0, 0, 0, 0.0, 0.0
    service_isolation_ok = 0
    verified_only_ok = 0
    latencies_ms = []
    details = []

    for case in BENCHMARK_CASES:
        svc = SERVICES[case['service']]
        t0 = time.perf_counter()
        results = retrieve_verified_knowledge(svc, case['query'], top_k=5)
        ms = round((time.perf_counter() - t0) * 1000, 3)
        latencies_ms.append(ms)

        # Verify strict service isolation & verified-only enforcement
        svc_ok = all(r['serviceId'] == svc['id'] for r in results)
        ver_ok = all(r.get('verified') and r.get('verificationStatus') == 'VERIFIED' for r in results)
        service_isolation_ok += int(svc_ok)
        verified_only_ok += int(ver_ok)

        def is_relevant(r: dict) -> bool:
            return any(r['sourceId'].startswith(p) for p in case['expected_prefix'])

        hits = [i + 1 for i, r in enumerate(results) if is_relevant(r)]
        r1 = 1 if any(h <= 1 for h in hits) else 0
        r3 = 1 if any(h <= 3 for h in hits) else 0
        r5 = 1 if any(h <= 5 for h in hits) else 0
        p3 = sum(1 for r in results[:3] if is_relevant(r)) / max(min(len(results), 3), 1)
        rr = (1.0 / hits[0]) if hits else 0.0

        recall1 += r1
        recall3 += r3
        recall5 += r5
        prec3 += p3
        mrr_sum += rr

        details.append({
            'id': case['id'],
            'category': case['category'],
            'service': case['service'],
            'latencyMs': ms,
            'recall@1': r1,
            'recall@3': r3,
            'recall@5': r5,
            'precision@3': round(p3, 3),
            'rr': round(rr, 3),
            'serviceIsolationOk': svc_ok,
            'verifiedOnlyOk': ver_ok,
            'top3Sources': [r['sourceId'] for r in results[:3]],
        })

    n = len(BENCHMARK_CASES)
    summary = {
        'TOTAL_QUERIES': n,
        'RECALL_AT_1': round(recall1 / n, 4),
        'RECALL_AT_3': round(recall3 / n, 4),
        'RECALL_AT_5': round(recall5 / n, 4),
        'PRECISION_AT_3': round(prec3 / n, 4),
        'MRR': round(mrr_sum / n, 4),
        'SERVICE_ISOLATION_RATE': round(service_isolation_ok / n, 4),
        'VERIFIED_SOURCE_RATE': round(verified_only_ok / n, 4),
        'AVG_LATENCY_MS': round(sum(latencies_ms) / n, 3),
        'details': details,
    }
    OUT_PATH.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
    return summary


if __name__ == '__main__':
    print(json.dumps(run_benchmark(), ensure_ascii=False, indent=2))
