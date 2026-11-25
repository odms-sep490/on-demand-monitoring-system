"""Real local ASR protocol/security checks, opt-in fixture, no transcript mock."""
import array
import io
import json
import os
import wave
from pathlib import Path
import requests
from dotenv import dotenv_values

repo=Path(__file__).resolve().parents[1]
config=dotenv_values(repo/'.env')
token=config.get('DESCRIPTION_AI_TOKEN')
assert token,'Configure internal worker token'
endpoint='http://localhost:8002/conversation/transcribe'
headers={'X-Internal-Token':token,'Content-Type':'audio/wav'}
evidence={'realASR':True,'cases':[]}
def check(label,data,status,auth=True):
    response=requests.post(endpoint,data=data,headers=headers if auth else {'Content-Type':'audio/wav'},timeout=215)
    body=response.json();assert response.status_code==status,(label,response.status_code,body)
    evidence['cases'].append({'case':label,'status':response.status_code,'response':body})
    return body
def wav(seconds,silent=False):
    target=io.BytesIO()
    with wave.open(target,'wb') as audio:
        audio.setnchannels(1);audio.setsampwidth(2);audio.setframerate(16000)
        values=array.array('h',[0 if silent else 1000]*int(seconds*16000))
        audio.writeframes(values.tobytes())
    return target.getvalue()
check('internal authentication',b'invalid',401,False)
check('invalid audio',b'invalid',422)
check('silence',wav(1,True),422)
check('duration above 60 seconds',wav(61),422)
override=os.getenv('ORDER_ASR_TEST_AUDIO')
fixture=Path(override) if override else repo/'.ai-runtime/audio/dev/10959203197012192023.wav'
result=check('real Vietnamese human recording',fixture.read_bytes(),200)
assert len(result['text'])>20
if not override:assert 'Nigeria' in result['text']
evidence['fixtureSource']='Customer microphone recording from previous failed local request' if override else 'https://huggingface.co/datasets/google/fleurs (Vietnamese dev, CC BY 4.0)'
evidence['reference']=None if override else 'Người bệnh cũng đã từng tới Nigeria, nơi một số ca mắc bệnh Ebola xảy ra.'
evidence['status']='PASS'
(repo/'.ai-runtime/asr-protocol-evidence.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2),encoding='utf-8')
print('REAL_ASR_PROTOCOL_PASS')
