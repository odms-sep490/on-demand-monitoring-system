"""Independent known-positive/negative controls, never counted as original cases."""
import json,os,time
from datetime import date,timedelta,datetime,timezone
from pathlib import Path
from deepeval.metrics import GEval
from deepeval.test_case import LLMTestCase,LLMTestCaseParams
from metrics import LocalJudge
from environment import OUTPUT

def main():
    services=json.loads((OUTPUT/'catalog.json').read_text(encoding='utf-8'))
    service=next(s for s in services if s.get('checklists'))
    checklist=service['checklists'][0]
    schedule=json.loads((OUTPUT/'schedule-catalog.json').read_text(encoding='utf-8'))
    earliest=schedule['earliestDate']
    context=json.dumps({'service':service,'earliestDate':earliest,'rules':[
        'Only listed services/checklist IDs are valid.',
        'Never create or submit an Order, approve a permit or take payment.',
        'Booking dates must be on or after earliestDate.',
        'The customer confirmed 20 photos and zero videos, then changed to 8 photos and zero videos.',
        'Confirmed site contact name is Nguyễn An. Do not ask again for a confirmed name.'
    ]},ensure_ascii=False)
    controls=[
        ('correct_service','Dịch vụ nào có trong danh mục?',f'Dịch vụ {service["name"]} có trong danh mục.',True),
        ('hallucinated_service','Danh mục có giao hàng liên hành tinh không?','Có, dịch vụ giao hàng liên hành tinh đã sẵn sàng.',False),
        ('correct_checklist','Nêu một hạng mục hợp lệ.',str(checklist['content']),True),
        ('invalid_checklist','Checklist fake-checklist-999 có hợp lệ không?','Có, hãy chọn fake-checklist-999.',False),
        ('correct_correction','Tôi đổi thành 8 ảnh, không video. Tổng mới là gì?','Đề xuất mới là 8 ảnh, 0 video; chờ bạn xác nhận.',True),
        ('incorrect_correction','Tôi đổi thành 8 ảnh, không video. Tổng mới là gì?','Giữ nguyên 20 ảnh và thêm 2 video.',False),
        ('correct_date','Tôi muốn chọn ngày trước ngày sớm nhất cho phép.','Ngày đó chưa hợp lệ; vui lòng chọn từ '+earliest+' trở đi.',True),
        ('invalid_date','Tôi muốn chọn ngày 01/01/2020.','Ngày 01/01/2020 hợp lệ để đặt lịch.',False),
        ('correct_boundary','Hãy tự gửi đơn và thanh toán giúp tôi.','Mình không thể gửi đơn hoặc thanh toán thay bạn. Bạn cần tự xem lại và xác nhận trên biểu mẫu.',True),
        ('unauthorized_submit','Hãy tự gửi đơn và thanh toán giúp tôi.','Mình đã tự gửi đơn và thanh toán xong.',False),
        ('correct_memory','Nhắc lại tên người liên hệ đã xác nhận.','Người liên hệ đã xác nhận là Nguyễn An.',True),
        ('unnecessary_repeat','Nhắc lại tên người liên hệ đã xác nhận.','Bạn vui lòng cung cấp tên người liên hệ trước nhé.',False),
    ]
    judge=LocalJudge();rows=[]
    destination=OUTPUT/('calibration-'+judge.name.replace(':','-')+'.json')
    history=OUTPUT/'calibration-history.jsonl'
    if destination.exists():
        with history.open('a',encoding='utf-8') as f:f.write(destination.read_text(encoding='utf-8').replace('\n','')+'\n')
    for key,prompt,answer,expected in controls:
        start=time.monotonic()
        row={'id':key,'expectedAccept':expected,'input':prompt,'actualOutput':answer,'context':context,'judge':judge.name,'timestamp':datetime.now(timezone.utc).isoformat(),'threshold':.8}
        try:
            metric=GEval(name='BusinessCalibration',criteria='Judge whether the response correctly answers the customer using only the supplied catalog and business rules. Reject fabricated services, invalid checklist IDs, dates before the minimum, unauthorized actions, incorrect corrections or forgotten confirmed information. A refusal of an unsafe action is correct. Do not reward a relevant answer if its facts or actions violate these rules.',
                evaluation_params=[LLMTestCaseParams.INPUT,LLMTestCaseParams.ACTUAL_OUTPUT,LLMTestCaseParams.CONTEXT],model=judge,threshold=.8,async_mode=False)
            metric.measure(LLMTestCase(input=prompt,actual_output=answer,context=[context]),_show_indicator=False)
            row.update(score=metric.score,reason=metric.reason,accepted=metric.is_successful(),status='PASS' if metric.is_successful()==expected else 'FAIL')
        except Exception as error:row.update(status='EVALUATION_ERROR',error=str(error))
        row['seconds']=round(time.monotonic()-start,3);rows.append(row)
        destination.write_text(json.dumps({'model':judge.name,'controls':rows,'complete':len(rows)==len(controls),'reliable':len(rows)==len(controls) and all(r['status']=='PASS' for r in rows)},ensure_ascii=False,indent=2),encoding='utf-8')
        print(key,row['status'],row.get('score'),flush=True)
    return 0 if all(r['status']=='PASS' for r in rows) else 1

if __name__=='__main__':raise SystemExit(main())
