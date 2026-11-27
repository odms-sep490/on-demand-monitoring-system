"""Automated tests for the Production Drone Consultation Engine."""
import unittest

from consultation_engine import (
    AgenticHybridRAG,
    AnswerVerifier,
    ConsultationRouter,
    CustomerMemoryManager,
    default_skill_registry,
)
from consultation_engine.eval_dataset_300 import build_evaluation_dataset
from consultation_engine.run_eval import run_evaluation


CATALOG = {
    'services': [
        {'id': 'svc-build', 'name': 'Giám sát công trình', 'description': 'Theo dõi tiến độ thi công.', 'indicativeStandardPrice': 2_500_000},
        {'id': 'svc-factory', 'name': 'Kiểm tra nhà xưởng', 'description': 'Khảo sát mái tôn và kết cấu.', 'indicativeStandardPrice': 3_800_000},
        {'id': 'svc-forest', 'name': 'Giám sát rừng', 'description': 'Phát hiện nguy cơ cháy rừng.', 'indicativeStandardPrice': 4_500_000},
    ]
}


class ConsultationEngineTests(unittest.TestCase):
    def test_skill_registry_has_17_production_skills_and_dynamic_selection(self):
        self.assertEqual(len(default_skill_registry.all_skills), 17)
        skills = default_skill_registry.select_skills('Dịch vụ công trình khác gì nhà xưởng?', 'COMPARISON', max_skills=2)
        self.assertEqual(skills[0].skill_id, 'service_comparison')
        prompt_block = default_skill_registry.render_prompt_context('Tôi có khoảng 3 triệu thôi', 'RECOMMENDATION', max_skills=2)
        self.assertIn('budget_consultation', prompt_block)

    def test_router_switches_modes_and_protects_draft_during_consultation(self):
        router = ConsultationRouter()
        r_compare = router.route('Dịch vụ công trình khác gì nhà xưởng?', has_active_draft=True)
        self.assertEqual(r_compare.mode, 'COMPARISON')
        self.assertFalse(r_compare.allows_draft_mutation)

        r_select = router.route('Vậy chọn công trình đi.', has_active_draft=True)
        self.assertEqual(r_select.mode, 'REQUEST_CREATION')
        self.assertTrue(r_select.allows_draft_mutation)

        r_pause_price = router.route('Khoan, tôi muốn hỏi giá trước.', has_active_draft=True)
        self.assertEqual(r_pause_price.mode, 'INFORMATION')
        self.assertFalse(r_pause_price.allows_draft_mutation)
        self.assertTrue(r_pause_price.is_interruption)

        r_resume = router.route('Được rồi, tiếp tục.', has_active_draft=True)
        self.assertEqual(r_resume.mode, 'REQUEST_CREATION')
        self.assertTrue(r_resume.allows_draft_mutation)

        r_implicit_home = router.route('Tôi xây nhà mà không có thời gian tới xem.')
        self.assertEqual(r_implicit_home.mode, 'RECOMMENDATION')
        self.assertFalse(r_implicit_home.allows_draft_mutation)

        r_implicit_roof = router.route('Mái nhà xưởng của tôi nghi bị dột.')
        self.assertEqual(r_implicit_roof.mode, 'RECOMMENDATION')
        self.assertFalse(r_implicit_roof.allows_draft_mutation)

    def test_hybrid_rag_combines_kb_graph_and_live_catalog(self):
        rag = AgenticHybridRAG()
        chunks = rag.retrieve('Mái nhà xưởng bị dột có kiểm tra được không, giá sao?', catalog=CATALOG, top_k=4)
        sources = {c.source for c in chunks}
        doc_ids = {c.doc_id for c in chunks}
        self.assertIn('LIVE_CATALOG', sources)
        self.assertIn('service_factory_roof', doc_ids)
        self.assertIn('pricing_policy', doc_ids)

    def test_customer_memory_isolation_across_accounts(self):
        mgr = CustomerMemoryManager()
        mem_a = mgr.get_or_create('acc-A', 'sess-1')
        mem_a.observe_message('Tôi có khoảng 3 triệu, xây nhà ở Thủ Đức cần chụp ảnh.', catalog=CATALOG)
        mem_b = mgr.get_or_create('acc-B', 'sess-1')
        self.assertEqual(mem_a.budget_vnd, 3_000_000)
        self.assertIsNone(mem_b.budget_vnd)
        self.assertEqual(mem_b.goals, [])

    def test_answer_verifier_blocks_hallucinations_and_unauthorized_mutations(self):
        verifier = AnswerVerifier()
        bad_discount = verifier.verify('Tôi đã giảm giá 20% cho bạn.', catalog=CATALOG)
        self.assertFalse(bad_discount.passed)
        self.assertIn('INVENTED_DISCOUNT', bad_discount.violations)

        bad_mutation = verifier.verify(
            'Dịch vụ công trình khác nhà xưởng ở hạng mục mái.',
            catalog=CATALOG,
            allow_draft_mutation=False,
            proposed_changes={'serviceId': 'svc-build'},
        )
        self.assertFalse(bad_mutation.passed)
        self.assertIn('UNAUTHORIZED_DRAFT_MUTATION', bad_mutation.violations)

        good = verifier.verify(
            'Gói Giám sát công trình có giá tham khảo 2.500.000 VNĐ; quản lý sẽ gửi báo giá chính thức.',
            catalog=CATALOG,
            allow_draft_mutation=False,
            proposed_changes={},
        )
        self.assertTrue(good.passed)

    def test_dataset_has_over_300_scenarios_and_upgraded_beats_baseline(self):
        dataset = build_evaluation_dataset()
        self.assertGreaterEqual(len(dataset), 300)
        report = run_evaluation()
        self.assertGreater(
            report['upgradedHeldOut']['modeRoutingAccuracy'],
            report['baselineHeldOut']['modeRoutingAccuracy'],
        )
        self.assertGreater(
            report['upgradedHeldOut']['draftMutationSafety'],
            report['baselineHeldOut']['draftMutationSafety'],
        )
        self.assertGreaterEqual(report['upgradedHeldOut']['hallucinationBlockRate'], 1.0)


if __name__ == '__main__':
    unittest.main()
