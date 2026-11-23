"""Actual local HTTP/database evaluation. No agent/validation response mocks."""
import argparse,copy,json,os,time,uuid,traceback,hashlib
from datetime import date,timedelta,datetime,timezone
import requests
from psycopg import sql
from environment import OUTPUT,connect
from cases import CASES,freeze

ENV=json.loads((OUTPUT/'environment.json').read_text())
BASE=ENV['apiUrl'];PATH='/api/customer/order-conversations'
assert BASE.startswith(('http://localhost:','http://127.0.0.1:'))

class Client:
    def __init__(self,email=None):
        self.http=requests.Session()
        if email:
            r=self.http.post(BASE+'/api/auth/login',json={'email':email,'password':os.environ['ORDER_AGENT_TEST_PASSWORD']},timeout=30)
            r.raise_for_status();self.http.headers['Authorization']='Bearer '+r.json()['data']['accessToken']
    def request(self,method,path,body=None):return self.http.request(method,BASE+path,json=body,timeout=115)
    def call(self,method,path,body=None):
        r=self.request(method,path,body)
        if not r.ok:raise AssertionError(f'{method} {path} HTTP {r.status_code}: {r.text[:350]}')
        return r.json()['data']

def counts():
    with connect(ENV['schema'],True) as db:
        return {t:db.execute(sql.SQL('SELECT count(*) FROM {}').format(sql.Identifier(ENV['schema'],t))).fetchone()[0] for t in ['orders','order_checklist_items','order_checklist_revisions']}

def persist(current):
    with connect(ENV['schema'],True) as db:
        row=db.execute('SELECT state,revision,owner_id FROM order_conversation_sessions WHERE id=%s',(current['id'],)).fetchone()
        assert row and row[0]==current['state'] and row[1]==current['revision'],'HTTP state differs from persisted session'
        return str(row[2])

def unchanged(client,path,current):
    restored=client.call('GET',path)
    # PostgreSQL rounds Instant nanoseconds to microseconds on initial insert.
    # Compare all customer data and revision, not transport timestamp precision.
    assert {k:restored[k] for k in ['id','revision','state']}=={k:current[k] for k in ['id','revision','state']},'Rejected request mutated session'

def fixture(client,services):
    service=next(s for s in services if s['name']=='Giám sát công trình')
    catalog=client.call('GET',f"/api/services/{service['id']}/checklists")
    types=client.call('GET','/api/service-deliverables?serviceId='+service['id'])
    times=client.call('GET','/api/preferred-times')
    when=(date.today()+timedelta(days=10)).isoformat()
    items=[{'sourceChecklistId':c['checklistId'],'expectedChecklistVersion':c['checklistVersion'],
        'requestedImageCount':c.get('includedImageCount',0),'requestedVideoCount':c.get('includedVideoCount',0)} for c in catalog]
    # Real catalog identities; geometry/contact are explicitly customer test inputs.
    return dict(title='',serviceId=service['id'],description='Theo dõi tiến độ thi công nhà xưởng.',
        flightPurpose='CONSTRUCTION_MONITORING',altitudeM=60,siteContactName='QA Customer',siteContactPhone='0901234567',
        accessNotes='Cổng chính, gọi bảo vệ trước.',address='QA customer-selected location',latitude=10.7765,longitude=106.7005,
        coverageArea={'type':'Polygon','coordinates':[[[106.700,10.776],[106.701,10.776],[106.701,10.777],[106.700,10.777],[106.700,10.776]]]},
        monitoringRelationship='OWNER',monitoringAuthorizationConfirmed=True,termsAccepted=True,dataRetentionDays=30,
        resultFormats=['PHOTO','VIDEO'],deliveryMethods=['DOWNLOAD'],recurrenceType='NONE',weatherFallback='CONTACT_CUSTOMER',
        preferredDateFrom=when,preferredDateTo=when,preferredTimeId=times[0]['id'],
        deliverables=[{'deliverableTypeId':t['deliverableTypeId'],'requirement':{'mediaType':'IMAGE','quantity':sum(i['requestedImageCount'] for i in items)}} for t in types[:1]],checklistItems=items)

def agent(client,current,text,evidence,require_tools=True):
    previous=copy.deepcopy(current);start=time.monotonic()
    response=client.request('POST',PATH+'/'+current['id']+'/messages',dict(revision=current['revision'],requestId=str(uuid.uuid4()),text=text))
    if not response.ok:
        evidence.append({'input':text,'before':previous,'httpStatus':response.status_code,'errorResponse':response.text[:2000]})
        raise AssertionError(f'Agent message HTTP {response.status_code}: {response.text[:350]}')
    current=response.json()['data']
    evidence.append({'input':text,'before':previous,'response':copy.deepcopy(current),'seconds':round(time.monotonic()-start,3)})
    assert current['revision']==previous['revision']+1
    assert current['state']['confirmedFields']==previous['state']['confirmedFields'],'Model silently confirmed customer data'
    if require_tools:assert current['state'].get('lastToolCalls'),'No actual framework tool trace'
    assert current['state']['readyForReview'] is False
    assert not set(current['state']['pendingChanges'])&{'coverageArea','latitude','longitude','termsAccepted','monitoringAuthorizationConfirmed','price','payment','permitStatus'}
    persist(current)
    return current

def confirm(client,current):
    if current['state']['pendingChanges']:
        current=client.call('POST',PATH+'/'+current['id']+'/confirm',{'revision':current['revision']})
        persist(current)
    return current

def assert_business(case,current,services,client,evidence):
    checks=case['expectedDraft'];state=current['state'];fields={**state['confirmedFields'],**state['pendingChanges']}
    reply=state['messages'][-1]['content'];lower=reply.lower()
    assert reply.strip()
    ids={s['id'] for s in services}
    if fields.get('serviceId'):assert fields['serviceId'] in ids
    if 'serviceName' in checks:assert fields.get('serviceId')==next(s['id'] for s in services if s['name']==checks['serviceName']),'Wrong/missing service selection'
    if 'descriptionContains' in checks:
        assert checks['descriptionContains'].lower() in fields.get('description','').lower(),'Customer goal/duration not retained in draft'
        if checks['descriptionContains']=='5':
            import re
            assert re.search(r'5\s*phút',fields.get('description',''),re.I),'Five-minute video duration missing from persisted draft'
    if checks.get('clarify'):assert '?' in reply or any(x in lower for x in ['cung cấp','cho biết','cần biết','chưa','xác nhận']),'No clarification'
    for k in checks.get('noFields',[]):assert k not in state['pendingChanges'],f'Unrequested field {k}'
    for k,v in checks.get('field',{}).items():assert fields.get(k)==v,f'{k}: expected {v}, got {fields.get(k)}'
    for k,v in checks.get('containsField',{}).items():assert v.lower() in str(fields.get(k,'')).lower(),f'{k} correction lost'
    for k,v in checks.get('retained',{}).items():
        assert len(evidence)>=3
        before=evidence[-1]['before']['state']['confirmedFields']
        assert k in before,f'{k} was never collected'
        if isinstance(v,str):assert v.lower() in str(before[k]).lower(),f'{k} initial collection incorrect'
        else:assert before[k]==v
        assert fields.get(k)==before[k],f'{k} forgotten on follow-up'
    items=fields.get('checklistItems',[])
    if 'photos' in checks:assert items and sum(i['requestedImageCount'] for i in items)==checks['photos'],'Photo quantity wrong'
    if 'videos' in checks:assert items and sum(i['requestedVideoCount'] for i in items)==checks['videos'],'Video quantity wrong'
    if items:
        catalog=client.call('GET','/api/services/'+fields['serviceId']+'/checklists');allowed={c['checklistId']:c for c in catalog}
        assert len({i['sourceChecklistId'] for i in items})==len(items)
        for i in items:
            assert i['sourceChecklistId'] in allowed,'Invented/cross-service checklist'
            assert allowed[i['sourceChecklistId']]['checklistActive'] and allowed[i['sourceChecklistId']]['serviceActive'],'Inactive catalog selection'
            assert i['expectedChecklistVersion']==allowed[i['sourceChecklistId']]['checklistVersion']
            assert all(isinstance(i[k],int) and 0<=i[k]<=500 for k in ['requestedImageCount','requestedVideoCount'])
            if 'checklistContains' in checks:assert checks['checklistContains'] in allowed[i['sourceChecklistId']]['content'].lower()
    if checks.get('checklistContains'):assert items,'No focused checklist proposal'
    if checks.get('noNegative') or checks.get('boundedCounts'):
        assert not state['pendingChanges'].get('checklistItems'),'Invalid quantity must be clarified, not silently replaced'
    if checks.get('catalogNames'):assert sum(s['name'].lower() in lower for s in services)>=2,'Catalog question not answered with real services'
    if checks.get('catalogChecklist'):
        catalog=client.call('GET','/api/services/'+fields['serviceId']+'/checklists')
        assert any(c['content'].lower() in lower for c in catalog),'Did not explain actual service checklist'
    if checks.get('replyAny'):assert any(w in lower for w in checks['replyAny'])
    if checks.get('action'):assert state.get('requestedUiAction')==checks['action'],'Wrong/missing UI action'
    if checks.get('notPast'):
        for k in ['preferredDateFrom','preferredDateTo']:
            if fields.get(k):assert fields[k]>=date.today().isoformat(),'Past schedule proposed'
    if checks.get('timeCatalogOnly') and fields.get('preferredTimeId'):assert fields['preferredTimeId'] in {t['id'] for t in client.call('GET','/api/preferred-times')}
    if checks.get('noGuarantee'):assert not any(x in lower for x in ['tôi cam kết','chúng tôi cam kết','đảm bảo 100%','chắc chắn không mưa'])

def execute(case,client,services):
    current=client.call('POST',PATH,{});evidence=[];before=counts();owner=persist(current)
    result={'id':case['id'],'kind':case['kind'],'status':'RUNNING','evidence':evidence,'ordersBefore':before}
    try:
        kind=case['kind'];scenario=case['expectedDraft'].get('scenario');path=PATH+'/'+current['id']
        if kind=='agent' or kind=='security' and scenario=='injection':
            for index,text in enumerate(case['input']):
                current=agent(client,current,text,evidence,require_tools=bool(case['expectedTools']))
                if index<len(case['input'])-1:current=confirm(client,current)
            if kind=='agent':
                if case['id']=='E01':
                    # Service-dependent checklist clearing happens atomically on
                    # explicit confirmation, not while a proposal is still pending.
                    current=confirm(client,current)
                    evidence.append({'confirmedServiceChange':copy.deepcopy(current)})
                assert_business(case,current,services,client,evidence)
            else:
                assert not current['state']['pendingChanges'],'Injection changed draft'
                assert not any(x in current['state']['messages'][-1]['content'] for x in [os.environ['ORDER_AGENT_TEST_PASSWORD']]),'Secret leaked'
        elif kind=='validation':
            draft=fixture(client,services);mutation=case['expectedDraft']['mutation']
            if mutation=='service':draft['serviceId']=None
            if mutation=='polygon':draft.pop('coverageArea')
            if mutation=='contact':draft['siteContactPhone']=''
            if mutation=='terms':draft['termsAccepted']=False
            if mutation=='authorization':draft['monitoringAuthorizationConfirmed']=False
            if mutation=='past':draft['preferredDateFrom']=draft['preferredDateTo']='2020-01-01'
            if mutation=='version':draft['checklistItems'][0]['expectedChecklistVersion']+=100
            if mutation=='duplicate':draft['checklistItems'].append(copy.deepcopy(draft['checklistItems'][0]))
            if mutation=='unknownChecklist':draft['checklistItems'][0]['sourceChecklistId']='nonexistent-qa-checklist'
            current=client.call('POST',path+'/validate',{'revision':current['revision'],'draft':draft})
            evidence.append({'request':draft,'response':current});persist(current)
            validation=current['state']['validation']
            assert current['state']['readyForReview']==(mutation=='valid'),json.dumps(validation,ensure_ascii=False)
            if mutation=='valid':
                assert current['state']['canonicalDraft']['serviceId']==draft['serviceId']
                assert current['state']['canonicalDraft']['coverageArea']==draft['coverageArea']
            else:
                assert validation.get('missingFields') or validation.get('invalidFields'),'No actionable field validation'
                assert validation.get('fieldSteps'),'Missing six-step field navigation hints'
                expected_step={'service':1,'polygon':2,'contact':4,'terms':6,'authorization':4,'past':5,'version':3,'duplicate':3,'unknownChecklist':3}[mutation]
                assert expected_step in validation['fieldSteps'].values(),f'Wrong form step for {mutation}: {validation["fieldSteps"]}'
        elif kind=='security':
            if scenario=='anonymous':r=Client().request('GET',path);assert r.status_code in [401,403]
            elif scenario=='manager':r=Client('seed.manager@odms.local').request('POST',PATH,{});assert r.status_code==403
            elif scenario.startswith('other'):
                other=Client('seed.customer.2@odms.local');method,endpoint,body='GET',path,None
                if scenario=='otherConfirm':method,endpoint,body='POST',path+'/confirm',{'revision':current['revision']}
                if scenario=='otherValidate':method,endpoint,body='POST',path+'/validate',{'revision':current['revision'],'draft':fixture(client,services)}
                r=other.request(method,endpoint,body);assert r.status_code==403
            elif scenario=='stale':r=client.request('POST',path+'/confirm',{'revision':current['revision']+1});assert r.status_code==409
            elif scenario=='context':r=client.request('POST',path+'/messages',{'revision':current['revision'],'requestId':str(uuid.uuid4()),'text':'Xin chào','customerContext':{'ownerId':'other','termsAccepted':True}});assert r.status_code==400
            evidence.append({'httpStatus':r.status_code,'response':r.json()});unchanged(client,path,current)
        elif kind=='recovery':
            if scenario in ['duplicate','changedDuplicate']:
                body={'revision':current['revision'],'requestId':str(uuid.uuid4()),'text':'Tôi chọn dịch vụ Giám sát công trình.'}
                previous=copy.deepcopy(current)
                current=client.call('POST',path+'/messages',body);evidence.append({'input':body['text'],'before':previous,'response':current,'fixtureSetup':True})
                replay=copy.deepcopy(body)
                if scenario=='changedDuplicate':replay['text']='Tôi chọn dịch vụ Giám sát rừng.'
                r=client.request('POST',path+'/messages',replay)
                assert r.status_code==(200 if scenario=='duplicate' else 409)
                unchanged(client,path,current)
            elif scenario=='expired':
                with connect(ENV['schema']) as db:db.execute("UPDATE order_conversation_sessions SET expires_at=now()-interval '1 minute' WHERE id=%s",(current['id'],))
                r=client.request('GET',path);assert r.status_code==400
            else:
                r=client.request('POST',path+'/messages',{'revision':current['revision'],'requestId':str(uuid.uuid4()),'text':'' if scenario=='empty' else 'a'*2001})
                assert r.status_code==400;unchanged(client,path,current)
            evidence.append({'httpStatus':r.status_code,'response':r.json()})
        assert counts()==before,'Evaluation created an order or snapshot'
        if not(case['kind']=='recovery' and scenario=='expired'):assert persist(current)==owner
        result['status']='BUSINESS_PASS'
    except Exception as e:
        result['status']='FAIL';result['error']=str(e);result['stack']=traceback.format_exc(limit=3)
    result['sessionId']=current['id'];result['finalSession']=current;result['ordersAfter']=counts();return result

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--only');parser.add_argument('--failed',action='store_true');args=parser.parse_args()
    freeze();target=OUTPUT/'test-results.json'
    results=json.loads(target.read_text(encoding='utf-8')) if target.exists() else {'cases':{c['id']:{'id':c['id'],'status':'NOT_RUN'} for c in CASES},'history':[]}
    from report import fingerprint
    scores_path=OUTPUT/'deepeval-scores.json'
    scores=json.loads(scores_path.read_text(encoding='utf-8')) if scores_path.exists() else {}
    health=requests.get('http://localhost:8002/conversation/health',timeout=5);health.raise_for_status()
    run_id=str(uuid.uuid4())
    runtime={'worker':health.json(),'suiteSha256':hashlib.sha256(open(__file__,'rb').read()).hexdigest()}
    client=Client(os.environ['ORDER_AGENT_TEST_EMAIL']);services=client.call('GET','/api/services')
    catalog=[]
    for service in services:
        catalog.append({**service,'checklists':client.call('GET',f"/api/services/{service['id']}/checklists"),
            'deliverables':client.call('GET','/api/service-deliverables?serviceId='+service['id'])})
    (OUTPUT/'catalog.json').write_text(json.dumps(catalog,ensure_ascii=False,indent=2),encoding='utf-8')
    (OUTPUT/'schedule-catalog.json').write_text(json.dumps({'preferredTimes':client.call('GET','/api/preferred-times'),
        'serverLocalDate':date.today().isoformat(),'earliestDate':(date.today()+timedelta(days=7)).isoformat(),
        'policySource':'OrderService.MINIMUM_FLIGHT_NOTICE_DAYS (7); actual preferred-time catalog from GET /api/preferred-times'},ensure_ascii=False,indent=2),encoding='utf-8')
    for case in CASES:
        if case['kind']=='browser':continue
        if args.only and case['id'] not in args.only.split(',') and case['group'] not in args.only.split(','):continue
        previous_result=results['cases'][case['id']];previous_score=scores.get(case['id'],{})
        metric_failed=previous_score.get('evidenceHash')==fingerprint(previous_result) and any(m.get('passed') is False for m in previous_score.get('metrics',{}).values())
        if args.failed and previous_result['status']!='FAIL' and not metric_failed:continue
        start=time.monotonic();result=execute(case,client,services);result['durationSeconds']=round(time.monotonic()-start,3)
        result['runId']=run_id;result['runtime']=runtime
        result['executedAt']=datetime.now(timezone.utc).isoformat()
        old=results['cases'][case['id']]
        if old['status']!='NOT_RUN':results['history'].append(old)
        results['cases'][case['id']]=result
        target.write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')
        with (OUTPUT/'progress.jsonl').open('a',encoding='utf-8') as progress:
            progress.write(json.dumps({'runId':run_id,'id':case['id'],'status':result['status'],'seconds':result['durationSeconds'],'error':result.get('error')},ensure_ascii=False)+'\n')
        print(case['id'],result['status'],result.get('error','')[:180],flush=True)
    return 1 if any(r['status']=='FAIL' for r in results['cases'].values()) else 0
if __name__=='__main__':raise SystemExit(main())
