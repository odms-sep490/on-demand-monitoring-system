import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock,patch
from pydantic import BaseModel
import metrics

class Verdict(BaseModel):
    verdict:str

class EvaluatorTransportTests(unittest.TestCase):
    def invoke(self,content,done='stop'):
        response=Mock()
        response.json.return_value={'message':{'content':content},'done_reason':done}
        with tempfile.TemporaryDirectory() as folder,patch.object(metrics,'OUTPUT',Path(folder)),patch.object(metrics.requests,'post',return_value=response):
            return metrics.LocalJudge().generate('Controlled parser test, no customer data',schema=Verdict)

    def test_valid_negative_is_preserved(self):
        self.assertEqual(self.invoke('{"verdict":"no"}').verdict,'no')

    def test_invalid_json_is_error_not_zero(self):
        with self.assertRaises(ValueError):self.invoke('not json')

    def test_missing_required_verdict_is_error_not_zero(self):
        with self.assertRaises(ValueError):self.invoke('{}')

    def test_truncated_valid_prefix_is_not_accepted(self):
        with self.assertRaisesRegex(ValueError,'EVALUATION_ERROR'):
            self.invoke('{"verdict":"yes"}','length')

if __name__=='__main__':unittest.main()
