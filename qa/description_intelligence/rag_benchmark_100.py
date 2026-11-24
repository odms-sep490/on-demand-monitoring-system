"""Expanded 104-Query Independent RAG Retrieval Benchmark.

Covers all 4 active services x 26 distinct query types:
- Semantic paraphrases, synonyms, Vietnamese without accents, customer slang
- Complex multi-topic queries, service capability overlap, service-specific restrictions
- Staff-only technical boundaries, separate form fields, unsupported capability promises
- Unknown/out-of-domain questions, conflicting/outdated phrasing.
"""
import json
import sys
import time
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'Python' / 'voice-order'))

from description_intelligence.knowledge import retrieve_verified_knowledge

CATALOG = json.loads((ROOT / 'qa' / 'description_intelligence' / 'fixtures' / 'catalog-inventory.json').read_text(encoding='utf-8'))
SERVICES = CATALOG['services']
OUT_PATH = ROOT / 'qa' / 'description_intelligence' / 'reports' / 'RAG_BENCHMARK_100_RESULTS.json'

QUERY_TEMPLATES = [
    ('paraphrase_core', 'muốn drone bay khảo sát hiện trạng tổng thể khu vực này', ['service:', 'checklist:']),
    ('paraphrase_defect', 'kiểm tra xem bề mặt có chỗ nào hư hỏng bất thường không', ['service:', 'checklist:', 'rule:handoff-core-scope']),
    ('paraphrase_hard_access', 'soi giúp mấy chỗ trên cao khó leo tới bằng flycam', ['service:', 'checklist:']),
    ('synonym_photo', 'cần xuất hình ảnh độ phân giải rõ để lưu hồ sơ', ['deliverable:', 'service:']),
    ('synonym_video', 'quay clip toàn cảnh từ trên cao gửi lại cho bên mình', ['deliverable:', 'service:']),
    ('synonym_report', 'làm file report tổng hợp kết quả kiểm tra cho ban giám đốc', ['deliverable:', 'service:', 'rule:no-unverified-promises']),
    ('no_accents_scope', 'giam sat toan bo khu vuc va ghi nhan diem bat thuong', ['service:', 'checklist:', 'rule:handoff-core-scope']),
    ('no_accents_output', 'can xuat anh chup va video ro net de bao cao', ['deliverable:', 'service:']),
    ('no_accents_boundary', 'toi khong biet do cao bay va duong bay drone nhu the nao', ['rule:staff-only-boundary']),
    ('no_accents_form', 'co can nhap toa do kinh do vi do va lich bay o day khong', ['rule:separate-form-fields']),
    ('slang_flycam', 'cho flycam lượn một vòng quay lại hiện trạng giúp em nha', ['service:', 'deliverable:', 'checklist:']),
    ('slang_quick', 'làm lẹ cái mô tả để đặt kèo bay drone chiều nay', ['service:', 'rule:']),
    ('terminology_alt', 'thu thập dữ liệu ảnh không ảnh UAV phục vụ đánh giá hiện trạng', ['service:', 'deliverable:', 'checklist:']),
    ('terminology_audit', 'khảo sát trực quan kết cấu mặt ngoài và ranh giới khu vực', ['service:', 'checklist:']),
    ('multi_topic_1', 'vừa chụp ảnh toàn cảnh vừa kiểm tra điểm bất thường, không rõ xin phép bay', ['service:', 'deliverable:', 'rule:staff-only-boundary']),
    ('multi_topic_2', 'cần video và ảnh, nhưng không biết chọn thiết bị drone hay phi công nào', ['deliverable:', 'rule:staff-only-boundary']),
    ('multi_topic_3', 'muốn ghi rõ phạm vi giám sát và điểm ưu tiên cho đội vận hành dễ hiểu', ['rule:handoff-core-scope', 'service:']),
    ('unsupported_permit', 'bên bạn cam kết đã được cấp phép bay và đảm bảo pháp lý 100% đúng không', ['rule:no-unverified-promises', 'rule:staff-only-boundary']),
    ('unsupported_struct', 'có cam kết kết quả kiểm định kết cấu chuyên sâu chịu lực không', ['rule:no-unverified-promises']),
    ('unsupported_deliverable', 'yêu cầu xuất đầu ra đặc biệt nằm ngoài danh mục dịch vụ có được không', ['rule:no-unverified-promises', 'deliverable:']),
    ('separate_schedule', 'tôi muốn chọn ngày bay và khung giờ bay cụ thể trong phần mô tả', ['rule:separate-form-fields']),
    ('separate_map', 'vẽ vùng bản đồ và gắn tọa độ GPS trực tiếp vào mô tả được không', ['rule:separate-form-fields']),
    ('staff_pilot', 'tôi cần tự chỉ định phi công và cấu hình thông số bay chi tiết', ['rule:staff-only-boundary']),
    ('conflicting_info', 'tài liệu cũ nói có miễn phí xin phép bay nhưng quy định mới không rõ', ['rule:no-unverified-promises', 'rule:staff-only-boundary']),
    ('outdated_phrasing', 'gói dịch vụ cũ năm ngoái có bao gồm báo cáo kết quả không', ['deliverable:', 'service:', 'rule:no-unverified-promises']),
    ('unknown_domain', 'giá thuê xe cẩu tự hành và vật tư xây dựng hôm nay là bao nhiêu', ['service:', 'rule:']),
]


def run_benchmark_100() -> dict:
    r1_sum = r3_sum = r5_sum = 0
    p3_sum = mrr_sum = 0.0
    iso_ok = ver_ok = 0
    latencies = []
    by_cat = defaultdict(lambda: {'n': 0, 'r1': 0, 'r3': 0, 'mrr': 0.0})
    total = 0

    for svc in SERVICES:
        for cat, query, prefixes in QUERY_TEMPLATES:
            total += 1
            t0 = time.perf_counter()
            res = retrieve_verified_knowledge(svc, query, top_k=5)
            ms = (time.perf_counter() - t0) * 1000.0
            latencies.append(ms)

            iso_ok += int(all(r['serviceId'] == svc['id'] for r in res))
            ver_ok += int(all(r.get('verified') and r.get('verificationStatus') == 'VERIFIED' for r in res))

            def is_rel(r):
                return any(r['sourceId'].startswith(p) for p in prefixes)

            hits = [i + 1 for i, r in enumerate(res) if is_rel(r)]
            r1 = 1 if any(h <= 1 for h in hits) else 0
            r3 = 1 if any(h <= 3 for h in hits) else 0
            r5 = 1 if any(h <= 5 for h in hits) else 0
            p3 = sum(1 for r in res[:3] if is_rel(r)) / max(min(len(res), 3), 1)
            rr = (1.0 / hits[0]) if hits else 0.0

            r1_sum += r1
            r3_sum += r3
            r5_sum += r5
            p3_sum += p3
            mrr_sum += rr
            by_cat[cat]['n'] += 1
            by_cat[cat]['r1'] += r1
            by_cat[cat]['r3'] += r3
            by_cat[cat]['mrr'] += rr

    summary = {
        'TOTAL_QUERIES': total,
        'RECALL_AT_1': round(r1_sum / total, 4),
        'RECALL_AT_3': round(r3_sum / total, 4),
        'RECALL_AT_5': round(r5_sum / total, 4),
        'PRECISION_AT_3': round(p3_sum / total, 4),
        'MRR': round(mrr_sum / total, 4),
        'SERVICE_ISOLATION_RATE': round(iso_ok / total, 4),
        'VERIFIED_SOURCE_RATE': round(ver_ok / total, 4),
        'AVG_LATENCY_MS': round(sum(latencies) / total, 3),
        'P95_LATENCY_MS': round(sorted(latencies)[int(0.95 * total)], 3),
    }
    OUT_PATH.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
    return summary


if __name__ == '__main__':
    print(json.dumps(run_benchmark_100(), ensure_ascii=False, indent=2))
