"""Property-based (Hypothesis) and Metamorphic Invariant Tests for Description Intelligence."""
import json
import sys
import unittest
from pathlib import Path

from hypothesis import given, settings, strategies as st

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'Python' / 'voice-order'))

from description_intelligence.engine import review_description
from description_intelligence.extractor import ground_extracted_facts
from description_intelligence.knowledge import retrieve_verified_knowledge
from description_intelligence.models import Evidence, Extraction

CATALOG = json.loads((ROOT / 'qa' / 'description_intelligence' / 'fixtures' / 'catalog-inventory.json').read_text(encoding='utf-8'))
SERVICES = CATALOG['services']


def make_stub_factory(extraction_payload: dict):
    class StubAgent:
        def __init__(self, **kwargs):
            pass

        def run(self, messages):
            yield [{'role': 'assistant', 'content': json.dumps(extraction_payload, ensure_ascii=False)}]

    return StubAgent


class DescriptionIntelligenceInvariantTests(unittest.TestCase):
    @given(
        svc_idx=st.integers(min_value=0, max_value=len(SERVICES) - 1),
        subject=st.sampled_from(['Nhà xưởng A', 'Lô rừng 12', 'Công trình cầu vượt', 'Khu công nghiệp phía Đông']),
        scope=st.sampled_from(['toàn bộ mái', 'khu vực phía Bắc', 'phân khu B và cổng chính']),
    )
    @settings(max_examples=40, deadline=None)
    def test_confirmed_facts_preserved_and_grounded(self, svc_idx, subject, scope):
        service = SERVICES[svc_idx]
        stmt = f'Tôi cần giám sát {subject}, phạm vi {scope}.'
        stub = make_stub_factory({
            'facts': [
                {'field': 'subject', 'value': subject, 'statement': 0, 'quote': subject},
                {'field': 'scope', 'value': scope, 'statement': 0, 'quote': scope},
            ],
            'contradictions': [],
            'unsupported': [],
            'missingCritical': [],
            'nextQuestion': '',
            'questionField': '',
            'quickReplies': [],
            'optionalImprovements': [],
        })
        result = review_description(service, stmt, [stmt], action='generate', agent_factory=stub)
        self.assertEqual(result['facts']['subject']['value'], subject)
        self.assertEqual(result['facts']['scope']['value'], scope)
        self.assertTrue(result['hardChecks']['NO_FABRICATED_FACTS'])
        self.assertEqual(result['readinessState'], 'READY_FOR_CUSTOMER_REVIEW')
        self.assertIn(subject, result['generatedDescription'])
        self.assertIn(scope, result['generatedDescription'])

    @given(
        svc_idx=st.integers(min_value=0, max_value=len(SERVICES) - 1),
        invented=st.sampled_from(['độ cao 120m', 'giá 5 triệu đồng', 'giấy phép đã được duyệt']),
    )
    @settings(max_examples=20, deadline=None)
    def test_fabricated_facts_are_strictly_rejected(self, svc_idx, invented):
        service = SERVICES[svc_idx]
        stmt = 'Cần kiểm tra mái nhà xưởng A.'
        stub = make_stub_factory({
            'facts': [
                {'field': 'subject', 'value': 'mái nhà xưởng A', 'statement': 0, 'quote': 'mái nhà xưởng A'},
                {'field': 'constraints', 'value': invented, 'statement': 0, 'quote': invented},
            ],
            'contradictions': [],
            'unsupported': [],
            'missingCritical': ['scope'],
            'nextQuestion': 'Phạm vi cần kiểm tra là toàn bộ mái hay một phần?',
            'questionField': 'scope',
            'quickReplies': ['Toàn bộ mái'],
            'optionalImprovements': [],
        })
        result = review_description(service, stmt, [stmt], action='review', agent_factory=stub)
        self.assertNotIn('constraints', result['facts'])
        self.assertEqual(len(result['rejectedFacts']), 1)
        self.assertFalse(result['hardChecks']['NO_FABRICATED_FACTS'])
        self.assertEqual(result['readinessState'], 'NEEDS_OPERATIONAL_REVIEW')

    def test_customer_correction_overrides_earlier_value(self):
        service = SERVICES[0]
        statements = ['Kiểm tra nhà xưởng A toàn bộ mái.', 'Xin lỗi, đổi lại thành nhà xưởng B nhé.']
        extraction = Extraction(
            facts=[
                Evidence(field='subject', value='nhà xưởng A', statement=0, quote='nhà xưởng A'),
                Evidence(field='subject', value='nhà xưởng B', statement=1, quote='nhà xưởng B'),
            ]
        )
        facts, rejected, _ = ground_extracted_facts(extraction, statements, service['id'])
        self.assertEqual(rejected, [])
        self.assertEqual(facts['subject']['value'], 'nhà xưởng B')
        self.assertEqual(facts['subject']['sourceTurn'], 1)

    def test_uncertainty_never_becomes_confirmed_fact(self):
        service = SERVICES[0]
        statements = ['Tôi chưa biết cần chụp phần nào, cần tư vấn.']
        extraction = Extraction(
            facts=[Evidence(field='scope', value='chưa biết', statement=0, quote='chưa biết')]
        )
        facts, _, uncertain = ground_extracted_facts(extraction, statements, service['id'])
        self.assertNotIn('scope', facts)
        self.assertEqual(uncertain, [0])

    def test_rag_retrieves_only_verified_selected_service_sources(self):
        for service in SERVICES:
            sources = retrieve_verified_knowledge(service, 'kiểm tra mái rỉ sét ảnh chụp')
            self.assertGreater(len(sources), 0)
            for s in sources:
                self.assertEqual(s['serviceId'], service['id'])
                self.assertTrue(s['verified'])
                self.assertEqual(s['verificationStatus'], 'VERIFIED')

    def test_unsupported_deliverable_for_area_monitoring_flagged(self):
        # Giám sát khu vực only supports Ảnh chụp & Video, NOT Báo cáo kết quả
        area_svc = next(s for s in SERVICES if s['name'] == 'Giám sát khu vực')
        stmt = 'Giám sát khu công nghiệp phía Đông, toàn bộ khu vực, tôi cần xuất Báo cáo kết quả.'
        stub = make_stub_factory({
            'facts': [
                {'field': 'subject', 'value': 'khu công nghiệp phía Đông', 'statement': 0, 'quote': 'khu công nghiệp phía Đông'},
                {'field': 'scope', 'value': 'toàn bộ khu vực', 'statement': 0, 'quote': 'toàn bộ khu vực'},
                {'field': 'outputs', 'value': 'Báo cáo kết quả', 'statement': 0, 'quote': 'Báo cáo kết quả'},
            ],
            'contradictions': [],
            'unsupported': [],
            'missingCritical': [],
            'nextQuestion': '',
            'questionField': '',
            'quickReplies': [],
            'optionalImprovements': [],
        })
        result = review_description(area_svc, stmt, [stmt], action='review', agent_factory=stub)
        self.assertFalse(result['hardChecks']['SERVICE_COMPATIBLE'])
        self.assertGreater(len(result['unsupportedRequests']), 0)
        self.assertEqual(result['readinessState'], 'NEEDS_OPERATIONAL_REVIEW')


if __name__ == '__main__':
    unittest.main()
