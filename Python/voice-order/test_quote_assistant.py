import json
import os
import unittest
from unittest.mock import patch
from fastapi.testclient import TestClient
from app import app
import quote_assistant as qa

class QuoteAgentTests(unittest.TestCase):
    def test_internal_auth_required(self):
        with TestClient(app) as client:
            response = client.post('/quote-assistant/messages', json={'question':'Giải thích', 'revision':'r1', 'path':'/api/customer/orders/o/quotes/q', 'authorization':'Bearer test'})
            self.assertEqual(response.status_code,401)
    def test_mutation_path_rejected(self):
        with patch.dict(os.environ, {'QUOTE_ASSISTANT_TOKEN':'test-only'}), TestClient(app) as client:
            response=client.post('/quote-assistant/messages',headers={'X-Internal-Token':'test-only'},json={'question':'Giải thích','revision':'r1','path':'/api/orders/o/quotes/q/accept','authorization':'Bearer test'})
            self.assertEqual(response.status_code,400)
    def test_tools_are_read_only(self):
        for name in ['getQuoteSummary','getQuoteLineItems','getPricingFactors','getQuoteRevision','getAvailableQuoteChanges','compareQuoteOptions']:
            self.assertIn(name,qa.__dict__.get('register_tool').__globals__['TOOL_REGISTRY'])
    def test_invented_money_is_blocked(self):
        fixture={'revision':'r1','total':100000,'items':[]}
        def backend(action,payload=None):
            qa._context.get()['sources'].append(fixture)
            return json.dumps(fixture)
        class BadAgent:
            def __init__(self,**kwargs): pass
            def run(self,**kwargs): yield [{'role':'assistant','content':'Giá là 999999 VND.'}]
        with patch.object(qa,'Assistant',BadAgent), patch.object(qa,'backend',side_effect=backend), patch.dict(os.environ,{'QUOTE_ASSISTANT_TOKEN':'test-only'}), TestClient(app) as client:
            response=client.post('/quote-assistant/messages',headers={'X-Internal-Token':'test-only'},json={'question':'Hãy tự giảm giá và tạo thanh toán','revision':'r1','path':'/api/customer/orders/o/quotes/q','authorization':'Bearer test'})
            self.assertEqual(response.status_code,502)
    def test_spelled_out_amount_is_blocked(self):
        fixture={'revision':'r1','total':100000,'items':[]}
        def backend(action,payload=None):
            qa._context.get()['sources'].append(fixture)
            return json.dumps(fixture)
        class BadAgent:
            def __init__(self,**kwargs): pass
            def run(self,**kwargs): yield [{'role':'assistant','content':'Giá là một triệu đồng.'}]
        with patch.object(qa,'Assistant',BadAgent), patch.object(qa,'backend',side_effect=backend), patch.dict(os.environ,{'QUOTE_ASSISTANT_TOKEN':'test-only'}), TestClient(app) as client:
            response=client.post('/quote-assistant/messages',headers={'X-Internal-Token':'test-only'},json={'question':'Tự đặt giá','revision':'r1','path':'/api/customer/orders/o/quotes/q','authorization':'Bearer test'})
            self.assertEqual(response.status_code,502)
    def test_stale_revision_stops_model(self):
        with patch.object(qa,'backend',return_value=json.dumps({'revision':'new'})), patch.object(qa,'Assistant') as agent, patch.dict(os.environ,{'QUOTE_ASSISTANT_TOKEN':'test-only'}), TestClient(app) as client:
            response=client.post('/quote-assistant/messages',headers={'X-Internal-Token':'test-only'},json={'question':'Giải thích','revision':'old','path':'/api/customer/orders/o/quotes/q','authorization':'Bearer test'})
            self.assertEqual(response.status_code,409)
            agent.assert_not_called()
    @unittest.skipUnless(os.environ.get('TEST_REAL_QUOTE_LLM')=='1','explicit real inference check')
    def test_real_model_with_test_backend_fixture(self):
        fixture={'quoteId':'q','revision':'r1','currency':'VND','total':100000,'items':[{'description':'Chụp ảnh','amount':100000}]}
        def backend(action,payload=None):
            qa._context.get()['sources'].append(fixture)
            return json.dumps(fixture,ensure_ascii=False)
        original=qa.Assistant
        class RecordingAgent:
            def __init__(self,**kwargs): self.agent=original(**kwargs)
            def run(self,**kwargs):
                for chunk in self.agent.run(**kwargs):
                    self.last=chunk
                    yield chunk
                print('TEST_MODEL_OUTPUT',json.dumps(self.last,ensure_ascii=True))
        with patch.object(qa,'Assistant',RecordingAgent), patch.object(qa,'backend',side_effect=backend), patch.dict(os.environ,{'QUOTE_ASSISTANT_TOKEN':'test-only'}), TestClient(app) as client:
            response=client.post('/quote-assistant/messages',headers={'X-Internal-Token':'test-only'},json={'question':'Giải thích báo giá bằng một câu tiếng Việt, không nhắc số tiền. /no_think','revision':'r1','path':'/api/customer/orders/o/quotes/q','authorization':'Bearer test'})
            self.assertEqual(response.status_code,200,response.text)
            self.assertTrue(response.json()['answer'])
            print('REAL_QWEN_INFERENCE:',response.json()['answer'])
    @unittest.skipUnless(os.environ.get('TEST_REAL_QUOTE_LLM')=='1','explicit real tool check')
    def test_real_model_calls_read_tool(self):
        agent=qa.Assistant(llm={'model':'qwen3:0.6b','model_server':'http://127.0.0.1:11434/v1','api_key':'EMPTY','generate_cfg':{'max_tokens':500,'temperature':0,'request_timeout':20,'max_retries':0}},function_list=['getQuoteSummary'],system_message='You must call getQuoteSummary before answering. /no_think')
        with patch.object(qa,'backend',return_value=json.dumps({'total':100000,'currency':'VND'})) as tool:
            result=list(agent.run(messages=[{'role':'user','content':'Call getQuoteSummary now and explain the quote briefly. /no_think'}]))
            self.assertTrue(tool.called,'Real model did not call its read-only tool')
            print('REAL_TOOL_CALL_COUNT',tool.call_count)
if __name__=='__main__': unittest.main()




