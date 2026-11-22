import os
import unittest
from unittest.mock import patch
from fastapi.testclient import TestClient
from app import app
import request_assistant as ra

CONTEXT = {'service': 'Giám sát công trình', 'total': 2250000,
           'items': [{'name': 'Ảnh tổng quan', 'images': 4, 'videos': 0, 'includedImages': 4, 'includedVideos': 0, 'subtotal': 500000},
                     {'name': 'Video quanh công trình', 'images': 0, 'videos': 1, 'includedImages': 0, 'includedVideos': 1, 'subtotal': 1750000}],
           'businessRules': ['Giá là tạm tính. Quản lý xác nhận báo giá chính thức.',
                             'Khách nhận ảnh/video theo nội dung đã chọn.']}


class RequestAssistantTests(unittest.TestCase):
    def test_deliverables_describe_each_selected_item_and_exact_media(self):
        details = ra.deliverable_details(CONTEXT)
        self.assertIn('Ảnh tổng quan', details)
        self.assertIn('Sản phẩm bạn yêu cầu: 4 ảnh.', details)
        self.assertIn('Video quanh công trình', details)
        self.assertIn('Sản phẩm bạn yêu cầu: 1 video.', details)
        self.assertIn('chưa phải kết quả đã được thực hiện hay bàn giao', details)
        self.assertNotIn('4K', details)

    def test_deliverables_are_not_lost_when_model_claims_information_is_missing(self):
        class Agent:
            def __init__(self, **kwargs): pass
            def run(self, **kwargs):
                yield [{'role': 'assistant', 'content': 'Hạn chế: Không có chi tiết cụ thể về sản phẩm hoặc quy trình.'}]
        with patch.dict(os.environ, {'QUOTE_ASSISTANT_TOKEN': 'test-only'}), patch.object(ra, 'Assistant', Agent), TestClient(app) as client:
            response = self.send(client)
            self.assertEqual(response.status_code, 200)
            self.assertIn('Sản phẩm bạn yêu cầu: 4 ảnh.', response.json()['answer'])
            self.assertNotIn('Không có chi tiết cụ thể', response.json()['answer'])

    def test_cost_details_use_exact_prices_and_allowances(self):
        details = ra.cost_details(CONTEXT)
        self.assertIn('500.000 đồng', details)
        self.assertIn('1.750.000 đồng', details)
        self.assertIn('2.250.000 đồng', details)
        self.assertIn('Đã gồm trong gói: 4 ảnh, 0 video.', details)
        self.assertIn('Video quanh công trình', details)

    def test_unpriced_custom_work_is_not_presented_as_free(self):
        details = ra.cost_details({'total': 0, 'needsManagerQuote': True,
                                  'items': [{'name': 'Nội dung riêng', 'needsManagerQuote': True}]})
        self.assertIn('cần quản lý báo giá riêng', details)
        self.assertNotIn('Chi phí tạm tính của hạng mục: 0 đồng', details)
        self.assertIn('Phần đã tạm tính', details)

    def send(self, client, token='test-only', question='Tôi sẽ nhận được những gì?'):
        return client.post('/request-assistant/messages', headers={'X-Internal-Token': token},
                           json={'question': question, 'context': CONTEXT})

    def test_requires_internal_authentication_before_model(self):
        with patch.dict(os.environ, {'QUOTE_ASSISTANT_TOKEN': 'test-only'}), patch.object(ra, 'Assistant') as agent, TestClient(app) as client:
            self.assertEqual(self.send(client, 'invalid').status_code, 401)
            agent.assert_not_called()

    def test_grounded_explanation(self):
        class Agent:
            def __init__(self, **kwargs):
                assert 'function_list' not in kwargs
            def run(self, **kwargs):
                yield [{'role': 'assistant', 'content': 'Bạn sẽ nhận 4 ảnh tổng quan. Chi phí là tạm tính.'}]
        with patch.dict(os.environ, {'QUOTE_ASSISTANT_TOKEN': 'test-only'}), patch.object(ra, 'Assistant', Agent), TestClient(app) as client:
            response = self.send(client)
            self.assertEqual(response.status_code, 200)
            self.assertIn('4 ảnh', response.json()['answer'])

    def test_invented_price_is_rejected(self):
        class Agent:
            def __init__(self, **kwargs): pass
            def run(self, **kwargs):
                yield [{'role': 'assistant', 'content': 'Bạn chỉ trả 999999 VND.'}]
        with patch.dict(os.environ, {'QUOTE_ASSISTANT_TOKEN': 'test-only'}), patch.object(ra, 'Assistant', Agent), TestClient(app) as client:
            self.assertEqual(self.send(client).status_code, 502)

    @unittest.skipUnless(os.environ.get('TEST_REAL_QUOTE_LLM') == '1', 'explicit real inference')
    def test_real_customer_explanation(self):
        original = ra.Assistant
        class RecordingAgent:
            def __init__(self, **kwargs): self.agent = original(**kwargs)
            def run(self, **kwargs):
                for chunk in self.agent.run(**kwargs):
                    self.last = chunk
                    yield chunk
                print('REAL_REQUEST_MODEL_OUTPUT:', self.last)
        with patch.dict(os.environ, {'QUOTE_ASSISTANT_TOKEN': 'test-only'}), patch.object(ra, 'Assistant', RecordingAgent), TestClient(app) as client:
            for question in ['Tôi sẽ nhận được những gì?', 'Vì sao có chi phí này?', 'Sau khi gửi yêu cầu, tôi cần làm gì?']:
                with self.subTest(question=question):
                    response = self.send(client, question=question)
                    self.assertEqual(response.status_code, 200, response.text)
                    self.assertTrue(response.json()['answer'])
                    if 'chi phí' in question:
                        self.assertIn('Chi tiết các khoản tạm tính:', response.json()['answer'])
                        self.assertIn('2.250.000 đồng', response.json()['answer'])
                    if 'nhận được' in question:
                        self.assertIn('Sản phẩm bạn yêu cầu: 4 ảnh.', response.json()['answer'])
                        self.assertIn('Sản phẩm bạn yêu cầu: 1 video.', response.json()['answer'])
                    print('REAL_REQUEST_AI:', question, response.json()['answer'])
