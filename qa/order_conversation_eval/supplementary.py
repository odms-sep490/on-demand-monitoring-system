"""Additional regression coverage; never added to the mandatory 100-case tally."""
import json,os,traceback
from datetime import date,timedelta
from run import Client,PATH,ENV,OUTPUT,agent,confirm,counts,persist,fixture
from environment import connect

def main():
    client=Client(os.environ['ORDER_AGENT_TEST_EMAIL']);services=client.call('GET','/api/services')
    output=[];before=counts()
    previous=OUTPUT/'supplementary-results.json'
    if previous.exists():
        with (OUTPUT/'supplementary-history.jsonl').open('a',encoding='utf-8') as history:history.write(json.dumps(json.loads(previous.read_text(encoding='utf-8')),ensure_ascii=False)+'\n')
    definitions=[('R01','Unaccented spelling with explicit media counts'),('R02','Customer changes an explicitly dated single-day schedule'),
        ('R03','Missing permit information does not grant approval'),('R04','Inactive real catalog item cannot reach Review')]
    spec=OUTPUT/'supplementary-cases.json'
    if not spec.exists():spec.write_text(json.dumps(definitions,ensure_ascii=False,indent=2),encoding='utf-8')
    for case_id,title in definitions:
        evidence=[];result={'id':case_id,'description':title,'countedInMandatory100':False,'evidence':evidence}
        try:
            current=client.call('POST',PATH,{})
            if case_id!='R04':current=confirm(client,agent(client,current,'Tôi chọn dịch vụ Giám sát công trình.',evidence))
            if case_id=='R01':
                current=agent(client,current,'Tôi muốn giam sat cong trinh, chụp 20 ảnh, không cần video.',evidence)
                items=current['state']['pendingChanges']['checklistItems']
                assert sum(i['requestedImageCount'] for i in items)==20 and sum(i['requestedVideoCount'] for i in items)==0
            elif case_id=='R02':
                for offset in [10,12]:
                    when=(date.today()+timedelta(days=offset)).isoformat()
                    current=confirm(client,agent(client,current,f'Tôi đổi lịch thực hiện thành một ngày, từ ngày {when} đến ngày {when}.',evidence))
                    fields=current['state']['confirmedFields']
                    assert fields.get('preferredDateFrom')==when and fields.get('preferredDateTo')==when,'Explicit schedule change was not collected'
            elif case_id=='R03':
                current=agent(client,current,'Tôi chưa có số giấy phép hoặc tệp giấy phép. Tôi cần làm gì tiếp theo?',evidence)
                assert not set(current['state']['pendingChanges'])&{'permitStatus','permitNumber','permitDocuments','monitoringAuthorizationConfirmed'}
                reply=current['state']['messages'][-1]['content'].lower()
                assert any(word in reply for word in ['quản lý','bổ sung','chưa','xác minh'])
            else:
                draft=fixture(client,services);checklist_id=draft['checklistItems'][0]['sourceChecklistId']
                with connect(ENV['schema']) as db:
                    previous=db.execute('SELECT is_active FROM checklist_definitions WHERE id=%s',(checklist_id,)).fetchone()[0]
                    try:
                        db.execute('UPDATE checklist_definitions SET is_active=false WHERE id=%s',(checklist_id,))
                        current=client.call('POST',PATH+'/'+current['id']+'/validate',{'revision':current['revision'],'draft':draft})
                        evidence.append({'response':current,'fixture':'Existing QA-only catalog item temporarily inactive; restored in finally'})
                        assert current['state']['readyForReview'] is False and current['state']['validation']['invalidFields']
                    finally:db.execute('UPDATE checklist_definitions SET is_active=%s WHERE id=%s',(previous,checklist_id))
            persist(current);assert counts()==before
            result['status']='PASS'
        except Exception as error:result.update(status='FAIL',error=str(error),stack=traceback.format_exc(limit=3))
        output.append(result);(OUTPUT/'supplementary-results.json').write_text(json.dumps(output,ensure_ascii=False,indent=2),encoding='utf-8')
        print(case_id,result['status'],result.get('error',''),flush=True)
    return 1 if any(r['status']=='FAIL' for r in output) else 0
if __name__=='__main__':raise SystemExit(main())
