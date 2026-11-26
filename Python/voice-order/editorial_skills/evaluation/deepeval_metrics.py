"""Optional DeepEval deterministic metric: factual token preservation, not semantic faithfulness."""
import json,sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[2]))
from editorial_runtime import protected
from deepeval.metrics import BaseMetric
from deepeval.test_case import LLMTestCase
class NumericUnitPreservation(BaseMetric):
    def __init__(self):self.threshold=1.0
    def measure(self,test_case,*args,**kwargs):
        self.score=float(protected(test_case.input)==protected(test_case.actual_output))
        self.success=self.score==1.0;self.reason='Exact number/unit multiset comparison';return self.score
    async def a_measure(self,test_case,*args,**kwargs):return self.measure(test_case)
    def is_successful(self):return self.success
    @property
    def __name__(self):return 'DeterministicNumericUnitPreservation'
if __name__=='__main__':
    root=Path(__file__).parent
    cases=json.loads((root/'dataset.json').read_text(encoding='utf8'))
    after=json.loads((root/'after.json').read_text(encoding='utf8'))
    rows=[]
    for case,result in zip(cases,after):
        if 'result' not in result:continue
        metric=NumericUnitPreservation();metric.measure(LLMTestCase(input=case['document']['executiveSummary'],actual_output=result['result']['document']['executiveSummary']))
        rows.append({'case':case['id'],'numericUnitPreservation':metric.score})
    (root/'deepeval-deterministic.json').write_text(json.dumps(rows,indent=2),encoding='utf8')
    print('DeepEval deterministic checks:',len(rows),'passed:',sum(r['numericUnitPreservation']==1 for r in rows))
