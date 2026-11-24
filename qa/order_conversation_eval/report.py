"""Merge evidence without converting unexecuted or ungraded cases into passes."""
import hashlib,json
from collections import Counter
from cases import CASES,THRESHOLDS,freeze
from environment import OUTPUT,connect

def fingerprint(result):
    return hashlib.sha256(json.dumps(result.get('evidence',[]),ensure_ascii=False,sort_keys=True).encode()).hexdigest()

def main():
    freeze()
    results=json.loads((OUTPUT/'test-results.json').read_text(encoding='utf-8'))
    score_file=OUTPUT/'deepeval-scores.json';scores=json.loads(score_file.read_text(encoding='utf-8')) if score_file.exists() else {}
    browser_file=OUTPUT/'browser-results.json'
    browser=[]
    if browser_file.exists():
        def walk(suite):
            for spec in suite.get('specs',[]):
                for test in spec.get('tests',[]):
                    runs=test.get('results',[]);last=runs[-1] if runs else {}
                    browser.append({'id':spec['title'].split()[0],'status':last.get('status','not-run'),'error':last.get('error'),
                        'attachments':[a for a in last.get('attachments',[]) if a.get('contentType','').startswith('image/')], 'durationMs':last.get('duration'),'executedAt':last.get('startTime')})
            for child in suite.get('suites',[]):walk(child)
        for suite in json.loads(browser_file.read_text(encoding='utf-8')).get('suites',[]):walk(suite)
        # Merge latest browser subset with prior executions instead of losing them.
        old=json.loads((OUTPUT/'browser-evidence.json').read_text(encoding='utf-8')) if (OUTPUT/'browser-evidence.json').exists() else {}
        for row in browser:
            if row['id'] in old and old[row['id']]!=row:
                with (OUTPUT/'browser-history.jsonl').open('a',encoding='utf-8') as history:history.write(json.dumps(old[row['id']],ensure_ascii=False)+'\n')
            old[row['id']]=row
        (OUTPUT/'browser-evidence.json').write_text(json.dumps(old,ensure_ascii=False,indent=2),encoding='utf-8')
        browser=list(old.values())
    browser_by_id={r['id']:r for r in browser}
    assessed=[]
    for case in CASES:
        result=results['cases'][case['id']]
        status=result['status'];reason=result.get('error')
        if case['kind']=='browser':
            b=browser_by_id.get(case['id'])
            status='PASS' if b and b['status']=='passed' else 'FAIL' if b and b['status'] in ['failed','timedOut','interrupted'] else 'NOT_RUN'
            reason=b.get('error') if b else 'No browser execution evidence'
        elif status=='BUSINESS_PASS':
            score=scores.get(case['id'])
            llm_case=case['kind']=='agent' or case['kind']=='security' and case['expectedDraft'].get('scenario')=='injection'
            if not llm_case:status='PASS'
            elif not score:status='UNVERIFIED';reason='DeepEval scores not recorded'
            elif score.get('evidenceHash')!=fingerprint(result):status='UNVERIFIED';reason='Judge scores refer to a different execution'
            elif score.get('applicable'):
                if any(m.get('applicable') and m.get('threshold')!=THRESHOLDS[name] for name,m in score.get('metrics',{}).items()):status='UNVERIFIED';reason='Metric threshold differs from the frozen contract'
                elif set(THRESHOLDS)-set(score.get('metrics',{})):status='UNVERIFIED';reason='Required native metrics have not all executed'
                elif any(m.get('status')=='BLOCKED' for m in score['metrics'].values()):status='BLOCKED';reason='Native metric/judge failed'
                elif any(m.get('passed') is False for m in score['metrics'].values()):status='FAIL';reason='Native DeepEval metric below frozen threshold'
                else:status='PASS'
            else:status='BLOCKED';reason='Required LLM evidence was not evaluated'
        score=scores.get(case['id'],{})
        quality_applicable=case['kind']=='agent' or case['kind']=='security' and case['expectedDraft'].get('scenario')=='injection'
        if quality_applicable and status in ['PASS','FAIL']:
            calibration_file=OUTPUT/('calibration-'+str(score.get('judgeModel','')).replace(':','-')+'.json')
            calibration=json.loads(calibration_file.read_text(encoding='utf-8')) if calibration_file.exists() else {}
            if not calibration.get('complete') or not calibration.get('reliable'):
                status='BLOCKED';reason='EVALUATION_ERROR: judge failed or has not completed independent calibration'
        business_status=('BUSINESS_PASS' if status=='PASS' else 'BUSINESS_FAIL' if status=='FAIL' else 'NOT_RUN') if case['kind']=='browser' else result['status']
        assessed.append({'id':case['id'],'description':case['description'],'status':status,'reason':reason,'businessStatus':business_status,
            'qualityApplicable':quality_applicable,'qualityStatus':('QUALITY_PASS' if status=='PASS' else 'QUALITY_FAIL' if status=='FAIL' else 'EVALUATION_ERROR' if status=='BLOCKED' else 'UNVERIFIED') if quality_applicable else 'NOT_APPLICABLE',
            'executedAt':b.get('executedAt') if case['kind']=='browser' and b else result.get('executedAt'),'executionId':result.get('runId'),
            'metrics':score.get('metrics',{}) if quality_applicable else {},'judgeModel':score.get('judgeModel') if quality_applicable else None,
            'qualityEvidenceMatches':score.get('evidenceHash')==fingerprint(result) if quality_applicable else None})
    counts=Counter(c['status'] for c in assessed)
    environment=json.loads((OUTPUT/'environment.json').read_text())
    with connect(readonly=True) as db:
        from psycopg import sql
        source={t:db.execute(sql.SQL('SELECT count(*) FROM {}').format(sql.Identifier('public',t))).fetchone()[0] for t in environment['sourceCounts']}
    with connect(environment['schema'],True) as db:
        qa_counts={t:db.execute(sql.SQL('SELECT count(*) FROM {}').format(sql.Identifier(environment['schema'],t))).fetchone()[0] for t in ['orders','order_checklist_items','order_checklist_revisions']}
    baseline=results['cases']['A01'].get('ordersBefore',{})
    no_writes=qa_counts==baseline
    metric_summary={}
    for name,threshold in THRESHOLDS.items():
        current=[scores[c['id']].get('metrics',{}).get(name,{}) for c in CASES if c['id'] in scores and scores[c['id']].get('evidenceHash')==fingerprint(results['cases'][c['id']])]
        numerical=[m['score'] for m in current if isinstance(m.get('score'),(int,float))]
        metric_summary[name]={'threshold':threshold,'scored':len(numerical),'passed':sum(m.get('passed') is True for m in current),
            'failed':sum(m.get('passed') is False for m in current),'blocked':sum(m.get('status')=='BLOCKED' for m in current),
            'mean':sum(numerical)/len(numerical) if numerical else None}
    (OUTPUT/'deepeval-summary.json').write_text(json.dumps(metric_summary,ensure_ascii=False,indent=2),encoding='utf-8')
    summary={'status':'VERIFIED_100_OF_100' if counts['PASS']==100 and source==environment['sourceCounts'] and no_writes else 'PARTIALLY_VERIFIED',
        'Total':100,'Executed':100-counts['NOT_RUN'],'Passed':counts['PASS'],'Failed':counts['FAIL'],
        'Skipped':0,'Blocked':counts['BLOCKED'],'Unverified':counts['UNVERIFIED'],'NotRun':counts['NOT_RUN'],
        'Pass rate':f"{counts['PASS']}%",'thresholds':THRESHOLDS,'agentModel':'odm-order-qwen:3b (Qwen2.5:3b)',
        'sourceCountsBefore':environment['sourceCounts'],'sourceCountsAfter':source,'sourceCountsUnchanged':source==environment['sourceCounts'],
        'qaOrdersAndSnapshots':qa_counts,'noOrdersOrSnapshotsCreated':no_writes,
        'criticalBusinessCasesPassed':sum(r['status']=='BUSINESS_PASS' for r in results['cases'].values())+sum(b['status']=='passed' for b in browser),
        'judgeModels':sorted({s['judgeModel'] for s in scores.values() if s.get('applicable') and s.get('judgeModel')}),
        'browserCasesPassed':sum(b['status']=='passed' for b in browser),'cases':assessed}
    (OUTPUT/'final-qa-report.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
    (OUTPUT/'bug-report.json').write_text(json.dumps([c for c in assessed if c['status'] in ['FAIL','BLOCKED']],ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({k:v for k,v in summary.items() if k not in ['cases','thresholds']},ensure_ascii=False,indent=2))
if __name__=='__main__':main()
