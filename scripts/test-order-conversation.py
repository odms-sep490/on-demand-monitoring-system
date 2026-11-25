"""Opt-in local integration test. Uses real Spring, database, catalog and (unless skipped) Qwen.

Set ORDER_AGENT_TEST_EMAIL / ORDER_AGENT_TEST_PASSWORD in the process environment.
Never submits an order. Geometry/contact below are explicitly test fixtures, not AI output.
"""
import argparse
import json
import os
import uuid
from datetime import date, timedelta
from pathlib import Path
import requests

parser=argparse.ArgumentParser()
parser.add_argument('--skip-model',action='store_true')
args=parser.parse_args()
root=os.getenv('ORDER_AGENT_TEST_URL','http://localhost:8080')
assert root.startswith(('http://localhost:','http://127.0.0.1:')), 'Local/test runtime only'
http=requests.Session()
login=http.post(root+'/api/auth/login',json={'email':os.environ['ORDER_AGENT_TEST_EMAIL'],'password':os.environ['ORDER_AGENT_TEST_PASSWORD']},timeout=15)
login.raise_for_status();http.headers['Authorization']='Bearer '+login.json()['data']['accessToken']
def call(method,path,body=None):
    response=http.request(method,root+path,json=body,timeout=110)
    response.raise_for_status()
    return response.json()['data']

before=call('GET','/api/orders/mine')
session=call('POST','/api/customer/order-conversations',{})
path='/api/customer/order-conversations/'+session['id']
evidence={'realLLM':not args.skip_model,'turns':[]}
destination=Path(__file__).resolve().parents[1]/'.ai-runtime'/('order-conversation-validation.json' if args.skip_model else 'order-conversation-model.json')
destination.parent.mkdir(exist_ok=True)
destination.write_text(json.dumps({'status':'RUNNING',**evidence},ensure_ascii=False,indent=2),encoding='utf-8')
if not args.skip_model:
    service_choices=call('GET','/api/services')
    for text in ['Tôi muốn theo dõi tiến độ thi công nhà xưởng, chú ý mái. Dịch vụ nào phù hợp?',
                 'Tôi chọn dịch vụ '+service_choices[0]['name']+'.',
                 'Tôi cần ghi nhận phần mái bằng 20 ảnh. Không cần video.']:
        message={'revision':session['revision'],'requestId':str(uuid.uuid4()),'text':text}
        session=call('POST',path+'/messages',message)
        evidence['turns'].append({'reply':session['state']['messages'][-1]['content'],
            'toolCalls':session['state'].get('lastToolCalls',[]),'proposedChanges':session['state']['pendingChanges']})
        destination.write_text(json.dumps({'status':'PARTIAL',**evidence},ensure_ascii=False,indent=2),encoding='utf-8')
        print('TURN',json.dumps(evidence['turns'][-1],ensure_ascii=False),flush=True)
        duplicate=call('POST',path+'/messages',message)
        assert duplicate['revision']==session['revision'], 'Duplicate request must not create another turn'
        session=call('POST',path+'/confirm',{'revision':session['revision']})
    restored=call('GET',path)
    assert restored['state']['messages']==session['state']['messages']
    assert len(restored['state']['messages'])==6
    confirmed_items=restored['state']['confirmedFields'].get('checklistItems',[])
    assert confirmed_items, 'Real agent did not collect the requested checklist; manual defaults cannot stand in for this test'
    assert sum(item['requestedImageCount'] for item in confirmed_items)==20, 'Real agent did not preserve the customer photo count'
    assert sum(item['requestedVideoCount'] for item in confirmed_items)==0, 'Real agent did not preserve the customer no-video request'

services=call('GET','/api/services')
service_id=session['state']['confirmedFields'].get('serviceId') or services[0]['id']
catalog=call('GET',f'/api/services/{service_id}/checklists')
selected=session['state']['confirmedFields'].get('checklistItems') or [
    {'sourceChecklistId':c['checklistId'],'expectedChecklistVersion':c['checklistVersion'],
     'requestedImageCount':c.get('includedImageCount',0),'requestedVideoCount':c.get('includedVideoCount',0)} for c in catalog]
for row in catalog:
    if row.get('required') and not any(item['sourceChecklistId']==row['checklistId'] for item in selected):
        selected.append({'sourceChecklistId':row['checklistId'],'expectedChecklistVersion':row['checklistVersion'],
            'requestedImageCount':row.get('includedImageCount',0),'requestedVideoCount':row.get('includedVideoCount',0)})
types=call('GET','/api/service-deliverables?serviceId='+service_id)
times=call('GET','/api/preferred-times')
when=(date.today()+timedelta(days=10)).isoformat()
draft={'title':'','serviceId':service_id,'description':'Theo dõi tiến độ thi công nhà xưởng, chú ý phần mái.',
    'flightPurpose':'CONSTRUCTION_MONITORING','altitudeM':60,'siteContactName':'Local integration fixture',
    'siteContactPhone':'0901234567','accessNotes':'Test fixture gate','address':'Local integration fixture',
    'latitude':10.7765,'longitude':106.7005,
    'coverageArea':{'type':'Polygon','coordinates':[[[106.700,10.776],[106.701,10.776],[106.701,10.777],[106.700,10.777],[106.700,10.776]]]},
    'monitoringRelationship':'OWNER','monitoringAuthorizationConfirmed':True,'termsAccepted':True,
    'dataRetentionDays':30,'resultFormats':['PHOTO'],'deliveryMethods':['DOWNLOAD'],'recurrenceType':'NONE',
    'weatherFallback':'CONTACT_CUSTOMER','preferredDateFrom':when,'preferredDateTo':when,'preferredTimeId':times[0]['id'],
    'deliverables':[{'deliverableTypeId':types[0]['deliverableTypeId'],'requirement':{'mediaType':'IMAGE','quantity':20}}],
    'checklistItems':selected}
old_revision=session['revision']
session=call('POST',path+'/validate',{'revision':old_revision,'draft':draft})
evidence['validation']=session['state']['validation']
evidence['revision']=session['revision']
print('VALIDATION',json.dumps(evidence['validation'],ensure_ascii=False),flush=True)
assert session['state']['readyForReview'] is True
conflict=http.post(root+path+'/validate',json={'revision':old_revision,'draft':draft},timeout=15)
assert conflict.status_code==409
after=call('GET','/api/orders/mine')
assert len(before)==len(after), 'Consultation and validation must not create an order'
evidence['ordersBefore']=len(before);evidence['ordersAfter']=len(after);evidence['staleRevisionStatus']=conflict.status_code
evidence['status']='PASS';destination.write_text(json.dumps(evidence,ensure_ascii=False,indent=2),encoding='utf-8')
print('LOCAL_API_INTEGRATION_PASS',destination,flush=True)
