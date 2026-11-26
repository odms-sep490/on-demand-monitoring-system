import unittest

from landing_assistant import ChatTurn, LandingChatInput, answer, clean_reply, normalize, retrieve


class LandingAssistantTest(unittest.TestCase):
    def test_normalize_removes_vietnamese_accents(self):
        self.assertEqual(normalize('Chi phí đặt cọc?'), 'chi phi dat coc')

    def test_retrieves_relevant_knowledge(self):
        self.assertEqual(retrieve('Thanh toán qua VNPAY được không')[0]['id'], 'payment')
        self.assertEqual(retrieve('Trời mưa thì sao')[0]['id'], 'weather')

    def test_unrelated_question_still_gets_general_context(self):
        ids = [e['id'] for e in retrieve('bóng đá tối nay')]
        self.assertIn('overview', ids)

    def test_prompt_contains_retrieved_knowledge_history_and_question(self):
        captured = {}

        def fake_llm(messages):
            captured['messages'] = messages
            return '<think>x</think> **Chi phí** tính theo diện tích.'

        result = answer(LandingChatInput(message='Chi phí thế nào?', history=[
            ChatTurn(role='user', content='xin chào'), ChatTurn(role='assistant', content='Chào bạn')]), call=fake_llm)

        system = captured['messages'][0]['content']
        self.assertIn('CHỈ dùng thông tin', system)
        self.assertIn('Chi phí tính theo dịch vụ', system)
        self.assertEqual(captured['messages'][-1], {'role': 'user', 'content': 'Chi phí thế nào?'})
        self.assertEqual(len(captured['messages']), 4)
        self.assertEqual(result['reply'], 'Chi phí tính theo diện tích.')
        self.assertEqual(result['sources'][0], 'pricing')

    def test_empty_model_reply_is_an_error(self):
        with self.assertRaises(ValueError):
            answer(LandingChatInput(message='Xin chào'), call=lambda m: '<think>only thinking</think>')

    def test_clean_reply_strips_markdown(self):
        self.assertEqual(clean_reply('## Tiêu đề\n* mục'), 'Tiêu đề mục')


if __name__ == '__main__':
    unittest.main()
