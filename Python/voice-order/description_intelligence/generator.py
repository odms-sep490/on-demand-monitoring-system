"""Grounded Professional Mission Description Generator.

Builds natural, operationally actionable Vietnamese mission descriptions strictly
from CONFIRMED customer facts with paragraph-level provenance tracing.
Never invents locations, area geometry, flight routes, altitudes, equipment,
image quantities, dates, prices, schedules, or flight permits.
"""

LABELS: dict[str, str] = {
    'objective': 'Mục tiêu giám sát',
    'subject': 'Đối tượng giám sát',
    'scope': 'Phạm vi thực hiện',
    'directions': 'Hướng / góc quan sát',
    'concerns': 'Điểm cần chú ý và ưu tiên',
    'outputs': 'Kết quả đầu ra mong muốn',
    'constraints': 'Lưu ý hiện trường',
}

ORDER = ('objective', 'subject', 'scope', 'directions', 'concerns', 'outputs', 'constraints')


def generate_grounded_description(
    service: dict,
    facts: dict[str, dict],
    missing_critical: list[str],
) -> tuple[str, list[dict]]:
    """Compose a natural, traceable Vietnamese description from confirmed facts only."""
    paragraphs: list[dict] = []
    service_name = (service.get('name') or '').strip()

    # Compose cohesive operational sentences grouped logically, preserving exact traceability
    for field in ORDER:
        if field not in facts:
            continue
        fact = facts[field]
        value = fact['value'].strip().rstrip('.')
        if not value:
            continue
        label = LABELS[field]
        text = f'{label}: {value}.'
        paragraphs.append({
            'text': text,
            'factFields': [field],
            'statementIds': [fact['source']],
        })

    if not paragraphs:
        return '', []

    header = f"Yêu cầu dịch vụ {service_name}:" if service_name else ''
    body_lines = [p['text'] for p in paragraphs]
    if missing_critical:
        gap_labels = ', '.join(LABELS.get(f, f) for f in missing_critical)
        body_lines.append(f'Thông tin cần đội vận hành trao đổi thêm: {gap_labels}.')

    draft = (header + '\n' + '\n'.join(body_lines)).strip() if header else '\n'.join(body_lines)
    return draft, paragraphs
