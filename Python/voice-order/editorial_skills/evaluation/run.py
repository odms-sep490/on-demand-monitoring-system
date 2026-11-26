"""Run actual local Ollama evaluation; outputs raw results, not invented semantic scores."""
import json, sys, time
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[2]))
from inspection_report import ReportInput, generate
root=Path(__file__).parent
results=[]
for case in json.loads((root/'dataset.json').read_text(encoding='utf8')):
    start=time.monotonic()
    try: result=generate(ReportInput.model_validate({k:v for k,v in case.items() if k!='id'})); row={'id':case['id'],'result':result}
    except Exception as error: row={'id':case['id'],'error':str(error)}
    row['seconds']=time.monotonic()-start;results.append(row)
    (root/'latest.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf8')
    print(case['id'], 'returned' if 'result' in row else 'failed', flush=True)
