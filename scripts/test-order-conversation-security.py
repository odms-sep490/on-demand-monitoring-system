"""Opt-in security checks against local seeded test users; no model/order creation."""
import json
import os
from pathlib import Path
import requests

root='http://localhost:8080';base='/api/customer/order-conversations'
password=os.environ['ORDER_AGENT_TEST_PASSWORD']
def login(email):
    response=requests.post(root+'/api/auth/login',json={'email':email,'password':password},timeout=20)
    response.raise_for_status();return {'Authorization':'Bearer '+response.json()['data']['accessToken']}
owner=login(os.environ['ORDER_AGENT_TEST_EMAIL'])
other=login('seed.customer.2@odms.local')
manager=login('seed.manager@odms.local')
before=requests.get(root+'/api/orders/mine',headers=owner,timeout=10).json()['data']
created=requests.post(root+base,headers=owner,timeout=10);created.raise_for_status();session=created.json()['data']
evidence={'cases':[]}
def check(label,method,path,expected,headers=None,body=None):
    response=requests.request(method,root+path,headers=headers,json=body,timeout=15)
    assert response.status_code==expected,(label,response.status_code)
    evidence['cases'].append({'case':label,'status':response.status_code})
check('unauthenticated session read','GET',base+'/'+session['id'],401)
check('another customer cannot read','GET',base+'/'+session['id'],403,other)
check('another customer cannot confirm','POST',base+'/'+session['id']+'/confirm',403,other,{'revision':0})
check('another customer cannot validate','POST',base+'/'+session['id']+'/validate',403,other,{'revision':0,'draft':{}})
check('manager cannot start customer consultation','POST',base,403,manager,{})
check('blank transcript rejected','POST',base+'/'+session['id']+'/messages',400,owner,{'revision':0,'requestId':'empty','text':' '})
repo=Path(__file__).resolve().parents[1]
matrix=json.loads((repo/'.ai-runtime/order-conversation-scenarios.json').read_text(encoding='utf-8-sig'))
assert matrix['status']=='PASS','Run real conversation scenarios first'
previous=matrix['cases'][0]['response'];message=previous['state']['messages'][0]
path=base+'/'+previous['id']
check('duplicate request id is safe even with old revision','POST',path+'/messages',200,owner,{'revision':0,'requestId':message['requestId'],'text':message['content']})
check('same request id with different content conflicts','POST',path+'/messages',409,owner,{'revision':0,'requestId':message['requestId'],'text':'changed duplicate'})
check('stale validation revision conflicts','POST',path+'/validate',409,owner,{'revision':0,'draft':{}})
after=requests.get(root+'/api/orders/mine',headers=owner,timeout=10).json()['data']
assert len(before)==len(after)
evidence.update(status='PASS',ordersBefore=len(before),ordersAfter=len(after))
(repo/'.ai-runtime/order-conversation-security.json').write_text(json.dumps(evidence,indent=2),encoding='utf-8')
print('REAL_SECURITY_PASS')
