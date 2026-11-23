"""Create a disposable QA schema from the real local catalog, without changing public."""
import json
import uuid
from pathlib import Path
from urllib.parse import urlsplit,parse_qsl,urlencode,urlunsplit
import psycopg
from psycopg import sql
from dotenv import dotenv_values

ROOT=Path(__file__).resolve().parents[2]
OUTPUT=ROOT/'.ai-runtime/deepeval'

def connect(schema='public',readonly=False):
    config=dotenv_values(ROOT/'.env')
    url=config['SPRING_DATASOURCE_URL'].removeprefix('jdbc:')
    parsed=urlsplit(url)
    if parsed.hostname not in ('localhost','127.0.0.1'):raise RuntimeError('Local QA only')
    connection=psycopg.connect(host=parsed.hostname,port=parsed.port or 5432,dbname=parsed.path.lstrip('/'),
        user=config['SPRING_DATASOURCE_USERNAME'],password=config['SPRING_DATASOURCE_PASSWORD'],
        options=f'-c search_path={schema},public',autocommit=True)
    if readonly:connection.execute('SET default_transaction_read_only=on')
    return connection

def setup():
    OUTPUT.mkdir(parents=True,exist_ok=True)
    target=OUTPUT/'environment.json'
    if target.exists():
        print('QA_SCHEMA_REUSED',json.loads(target.read_text())['schema']);return
    schema='odm_deepeval_'+uuid.uuid4().hex[:12]
    with connect() as db,db.transaction():
        tables=[row[0] for row in db.execute("SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename<>'spatial_ref_sys' ORDER BY tablename")]
        db.execute(sql.SQL('CREATE SCHEMA {}').format(sql.Identifier(schema)))
        for table in tables:
            destination=sql.Identifier(schema,table);source=sql.Identifier('public',table)
            db.execute(sql.SQL('CREATE TABLE {} (LIKE {} INCLUDING ALL)').format(destination,source))
            db.execute(sql.SQL('INSERT INTO {} SELECT * FROM {}').format(destination,source))
        # LIKE defaults must never advance a sequence in the source schema.
        for table,column,default in db.execute("SELECT table_name,column_name,column_default FROM information_schema.columns WHERE table_schema=%s AND column_default LIKE 'nextval(%%'",(schema,)).fetchall():
            sequence='qa_'+table+'_'+column
            db.execute(sql.SQL('CREATE SEQUENCE {}').format(sql.Identifier(schema,sequence)))
            maximum=db.execute(sql.SQL('SELECT coalesce(max({}),0)+1 FROM {}').format(sql.Identifier(column),sql.Identifier(schema,table))).fetchone()[0]
            db.execute('SELECT setval(%s::regclass,%s,false)',(schema+'.'+sequence,maximum))
            db.execute(sql.SQL('ALTER TABLE {} ALTER COLUMN {} SET DEFAULT nextval({}::regclass)').format(sql.Identifier(schema,table),sql.Identifier(column),sql.Literal(schema+'.'+sequence)))
        counts={table:db.execute(sql.SQL('SELECT count(*) FROM {}').format(sql.Identifier('public',table))).fetchone()[0] for table in ('services','service_checklists','users','orders')}
    config=dotenv_values(ROOT/'.env');url=config['SPRING_DATASOURCE_URL'].removeprefix('jdbc:');parsed=urlsplit(url)
    query=dict(parse_qsl(parsed.query));query['currentSchema']=schema+',public'
    jdbc='jdbc:'+urlunsplit(parsed._replace(query=urlencode(query,safe=',')))
    target.write_text(json.dumps({'schema':schema,'jdbcUrl':jdbc,'apiUrl':'http://localhost:8081','webUrl':'http://localhost:5174','sourceCounts':counts,'tablesCopied':len(tables)},indent=2),encoding='utf-8')
    print('QA_SCHEMA_CREATED',schema,'TABLES',len(tables))

if __name__=='__main__':setup()
