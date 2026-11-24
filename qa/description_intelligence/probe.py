import json
import sys
import time
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'Python/voice-order'))
from description_intelligence import review_description

def main():
    services = json.loads((ROOT / 'qa/description_intelligence/fixtures/catalog-inventory.json').read_text(encoding='utf-8'))['services']
    output = ROOT / 'qa/description_intelligence/real_model'
    output.mkdir(parents=True, exist_ok=True)
    service = services[0]
    original = '4 hướng đông tây nam bắc'
    statements = [original]
    turns = []
    for answer in ['', 'Nhà xưởng A, tôi muốn kiểm tra phần mái bị bong và rỉ sét.', 'Quan sát toàn bộ mái, tập trung phía Đông cạnh cổng số 2, cần ảnh rõ các vị trí hư hỏng.']:
        if answer: statements.append(answer)
        start = time.monotonic()
        try:
            result = review_description(service, original, statements, 'generate' if len(statements) == 3 else 'review')
            turns.append({'seconds': time.monotonic()-start, 'result': result})
            print(result['readiness'], result['nextQuestion'], flush=True)
        except Exception as error:
            turns.append({'error': str(error), 'seconds': time.monotonic()-start})
            print(type(error).__name__, str(error), flush=True)
            break
    (output / 'probe.json').write_text(json.dumps(turns, ensure_ascii=False, indent=2), encoding='utf-8')

if __name__ == '__main__': main()
