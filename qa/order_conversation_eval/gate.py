"""Run all evidence gates sequentially; never commits, submits orders or lowers thresholds."""
import json,os,subprocess,sys,time
from pathlib import Path
from environment import ROOT,OUTPUT
from runner_support import playwright_command

def main():
    environment=json.loads((OUTPUT/'environment.json').read_text())
    frontend=ROOT.parent/'ondemand-monitoring-web'
    if not frontend.is_dir():raise SystemExit('Frontend checkout is missing')
    if not os.getenv('ORDER_AGENT_TEST_EMAIL') or not os.getenv('ORDER_AGENT_TEST_PASSWORD'):
        raise SystemExit('Set local test customer credentials in the process environment')
    process_environment={**os.environ,'PYTHONUTF8':'1','DEEPEVAL_TELEMETRY_OPT_OUT':'1',
        'ORDER_AGENT_API_URL':environment['apiUrl'],'ORDER_AGENT_WEB_URL':environment['webUrl'],
        'ORDER_AGENT_OUTPUT_DIR':str(OUTPUT/'browser'),'DEEPEVAL_BROWSER_REPORT':str(OUTPUT/'browser-results.json')}
    # Local QA services must already be running. This runner never starts or
    # stops a developer's unrelated application process.
    commands=[('supplementary-regression',[sys.executable,'qa/order_conversation_eval/supplementary.py'],ROOT),
        ('real-api',[sys.executable,'qa/order_conversation_eval/run.py'],ROOT),
        ('browser',playwright_command(os.name),frontend),
        ('native-metrics',[sys.executable,'qa/order_conversation_eval/metrics.py','--resume'],ROOT),
        ('report',[sys.executable,'qa/order_conversation_eval/report.py'],ROOT)]
    outcomes=[]
    for name,command,cwd in commands:
        print('RUNNING',name,flush=True);start=time.monotonic()
        with (OUTPUT/(name+'-gate.log')).open('w',encoding='utf-8') as log:
            completed=subprocess.run(command,cwd=cwd,env=process_environment,stdout=log,stderr=subprocess.STDOUT,check=False)
        outcomes.append({'phase':name,'command':command,'exitCode':completed.returncode,'seconds':round(time.monotonic()-start,2)})
        (OUTPUT/'gate-commands.json').write_text(json.dumps(outcomes,indent=2),encoding='utf-8')
        print('FINISHED',name,'EXIT',completed.returncode,flush=True)
    report=json.loads((OUTPUT/'final-qa-report.json').read_text(encoding='utf-8'))
    return 0 if report['status']=='VERIFIED_100_OF_100' and all(o['exitCode']==0 for o in outcomes) else 1
if __name__=='__main__':raise SystemExit(main())

