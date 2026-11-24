"""Read-only source inventory; never changes development data."""
import json
import sys
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from qa.order_conversation_eval.environment import connect

def snapshot():
    with connect(readonly=True) as db:
        services = [dict(zip(('id', 'name', 'description'), row)) for row in db.execute(
            'select id,name,description from services where is_active=true order by id')]
        for service in services:
            service['checklists'] = [dict(zip(('id', 'version', 'content'), row)) for row in db.execute(
                'select c.id,c.version,c.content from service_checklists sc join checklist_definitions c on c.id=sc.checklist_id where sc.service_id=%s and c.is_active=true order by sc.display_order', (service['id'],))]
            service['deliverables'] = [dict(zip(('id', 'name'), row)) for row in db.execute(
                'select d.id,d.name from service_deliverables sd join deliverable_types d on d.id=sd.deliverable_type_id where sd.service_id=%s and d.is_active=true', (service['id'],))]
        columns = db.execute("select table_name,column_name from information_schema.columns where table_schema='public' and (table_name like '%checklist%' or table_name like '%deliverable%')").fetchall()
    output = ROOT / 'qa/description_intelligence/fixtures'
    output.mkdir(parents=True, exist_ok=True)
    (output / 'catalog-inventory.json').write_text(json.dumps({'services': services, 'columns': columns}, ensure_ascii=False, indent=2), encoding='utf-8')
    print('CATALOG_SNAPSHOT', len(services), 'services', sum(len(s['checklists']) for s in services), 'checklists')

if __name__ == '__main__': snapshot()
