-- CURRENT Order 1:N schema; read-only post-bootstrap verification.
-- psql -v ON_ERROR_STOP=1 -f verify_current_schema.sql
-- No DDL, data writes, backfill or lifecycle commands.
BEGIN READ ONLY;
SET LOCAL search_path = public, pg_catalog;
DO $$ DECLARE e record; actual text; keys text[]; predicate text; meta record; BEGIN
    FOREACH actual IN ARRAY ARRAY['postgis','vector','btree_gist'] LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname=actual AND extnamespace='public'::regnamespace)
            THEN RAISE EXCEPTION 'Required public extension missing: %',actual; END IF;
    END LOOP;
    FOR e IN SELECT * FROM (VALUES ('missions','order_id'),
        ('mission_checklist_executions','order_checklist_item_id')) AS forbidden(table_name,column_name) LOOP
        IF EXISTS (SELECT 1 FROM pg_index i JOIN pg_attribute a ON a.attrelid=i.indrelid
            AND a.attnum=i.indkey[0] WHERE i.indrelid=to_regclass('public.'||e.table_name)
            AND i.indisunique AND i.indnkeyatts=1 AND a.attname=e.column_name) THEN
            RAISE EXCEPTION 'Forbidden legacy uniqueness: %.%',e.table_name,e.column_name;
        END IF;
    END LOOP;
    IF to_regclass('public.uk_mission_direct_replacement') IS NOT NULL
        OR to_regclass('public.uk_resource_time_locks_resource_mission') IS NOT NULL THEN
        RAISE EXCEPTION 'Obsolete global replacement/reservation index exists';
    END IF;
    FOR e IN SELECT * FROM (VALUES
        ('services','ck_services_base_price_positive','c'),
        ('orders','ck_order_execution_plan','c'),
        ('missions','ck_mission_foundation','c'),
        ('missions','uk_mission_id_order','u'),
        ('order_checklist_items','uk_checklist_item_id_order','u'),
        ('order_checklist_items','ck_snapshot_evidence_policy','c'),
        ('order_checklist_items','ck_order_checklist_pricing_snapshot','c'),
        ('order_checklist_items','ck_order_checklist_manager_quote','c'),
        ('services','ck_services_default_price','c'),
        ('checklist_definitions','ck_checklist_pricing','c'),
        ('service_checklists','ck_service_checklist_overrides','c'),
        ('pricing_rules','ck_pricing_rule_media','c'),
        ('mission_checklist_assignments','uk_assignment_identity','u'),
        ('mission_checklist_assignments','ck_assignment_shape','c'),
        ('mission_checklist_executions','uk_execution_assignment','u'),
        ('mission_checklist_executions','uk_execution_id_mission','u'),
        ('drone_media','uk_media_id_mission','u'),
        ('mission_checklist_evidence','ck_evidence_detach','c'),
        ('mission_results','ck_result_approval_status','c'),
        ('resource_time_locks','ex_resource_reservation_window','x'),
        ('resource_time_locks','ck_resource_reservation_window_valid','c')
    ) AS required(table_name,constraint_name,constraint_type) LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid=to_regclass('public.'||e.table_name)
            AND conname=e.constraint_name AND contype::text=e.constraint_type AND convalidated) THEN
            RAISE EXCEPTION 'Missing/invalid constraint: %.%',e.table_name,e.constraint_name;
        END IF;
    END LOOP;
    FOR e IN SELECT * FROM (VALUES
        ('missions','fk_mission_replaces_order','FOREIGN KEY (replaces_mission_id, order_id) REFERENCES missions(id, order_id) ON DELETE RESTRICT'),
        ('mission_checklist_assignments','fk_assignment_mission_order','FOREIGN KEY (mission_id, order_id) REFERENCES missions(id, order_id) ON DELETE RESTRICT'),
        ('mission_checklist_assignments','fk_assignment_item_order','FOREIGN KEY (order_checklist_item_id, order_id) REFERENCES order_checklist_items(id, order_id) ON DELETE RESTRICT'),
        ('mission_checklist_executions','fk_execution_assignment_identity','FOREIGN KEY (mission_checklist_assignment_id, mission_id, order_id, order_checklist_item_id) REFERENCES mission_checklist_assignments(id, mission_id, order_id, order_checklist_item_id) ON DELETE RESTRICT'),
        ('mission_checklist_executions','fk_execution_mission_order','FOREIGN KEY (mission_id, order_id) REFERENCES missions(id, order_id)'),
        ('mission_checklist_executions','fk_execution_item_order','FOREIGN KEY (order_checklist_item_id, order_id) REFERENCES order_checklist_items(id, order_id)'),
        ('mission_checklist_evidence','fk_evidence_execution_mission','FOREIGN KEY (mission_checklist_execution_id, mission_id) REFERENCES mission_checklist_executions(id, mission_id) ON DELETE RESTRICT'),
        ('mission_checklist_evidence','fk_evidence_media_mission','FOREIGN KEY (media_asset_id, mission_id) REFERENCES drone_media(id, mission_id) ON DELETE RESTRICT'),
        ('mission_checklist_evidence','fk_evidence_attached_user','FOREIGN KEY (attached_by) REFERENCES users(id) ON DELETE RESTRICT'),
        ('mission_checklist_evidence','fk_evidence_detached_user','FOREIGN KEY (detached_by) REFERENCES users(id) ON DELETE RESTRICT')
    ) AS expected(table_name,constraint_name,definition) LOOP
        SELECT pg_get_constraintdef(oid) INTO actual FROM pg_constraint
            WHERE conrelid=to_regclass('public.'||e.table_name)
            AND conname=e.constraint_name AND contype='f' AND convalidated;
        IF actual IS DISTINCT FROM e.definition THEN
            RAISE EXCEPTION 'Composite FK drift: %.% (actual: %)',e.table_name,e.constraint_name,actual;
        END IF;
    END LOOP;
    FOR e IN SELECT * FROM (VALUES
        ('uk_checklist_seed_code','checklist_definitions','seed_code',true,''),
        ('uk_assignment_active_pair','mission_checklist_assignments','mission_id,order_checklist_item_id',true,'removed_atisnull'),
        ('ix_assignment_mission_order','mission_checklist_assignments','mission_id,display_order,id',false,''),
        ('ix_assignment_coverage','mission_checklist_assignments','order_id,order_checklist_item_id',false,''),
        ('ix_mission_order_schedule','missions','order_id,scheduled_start_at,id',false,''),
        ('ix_execution_mission','mission_checklist_executions','mission_id',false,''),
        ('uk_evidence_active_pair','mission_checklist_evidence','mission_checklist_execution_id,media_asset_id',true,'detached_atisnull'),
        ('ix_evidence_mission_execution','mission_checklist_evidence','mission_id,mission_checklist_execution_id',false,''),
        ('ix_evidence_media','mission_checklist_evidence','media_asset_id',false,''),
        ('uk_mission_active_direct_replacement','missions','replaces_mission_id',true,'replaces_mission_idisnotnullandstatus<>''cancelled'''),
        ('uk_mission_device_assignment_current_main','mission_device_assignments','mission_id',true,'is_current=trueanddevice_role=''main'''),
        ('uk_resource_reservation_active_pair','resource_time_locks','resource_id,mission_id',true,'released_atisnull'),
        ('ix_resource_reservation_active_window','resource_time_locks','resource_id,start_time,end_time',false,'released_atisnull'),
        ('ix_order_checklist_order','order_checklist_items','order_id,display_order,id',false,'')
    ) AS expected(index_name,table_name,column_names,is_unique,predicate) LOOP
        SELECT i.*,am.amname INTO meta FROM pg_index i
            JOIN pg_class c ON c.oid=i.indexrelid JOIN pg_am am ON am.oid=c.relam
            WHERE i.indexrelid=to_regclass('public.'||e.index_name)
            AND i.indrelid=to_regclass('public.'||e.table_name);
        IF NOT FOUND OR NOT meta.indisvalid OR NOT meta.indisready
            OR meta.indisunique IS DISTINCT FROM e.is_unique OR meta.amname <> 'btree'
            OR meta.indnkeyatts <> meta.indnatts THEN
            RAISE EXCEPTION 'Index missing/invalid: %',e.index_name;
        END IF;
        SELECT array_agg(pg_get_indexdef(meta.indexrelid,n,false) ORDER BY n) INTO keys
            FROM generate_series(1,meta.indnkeyatts) n;
        predicate := lower(regexp_replace(replace(COALESCE(pg_get_expr(meta.indpred,meta.indrelid),''),
            '::text',''),'[()[:space:]]','','g'));
        IF keys IS DISTINCT FROM string_to_array(e.column_names,',') OR predicate IS DISTINCT FROM e.predicate THEN
            RAISE EXCEPTION 'Index keys/predicate drift: %',e.index_name;
        END IF;
    END LOOP;
    FOR e IN SELECT * FROM (VALUES
        ('missions','order_id','orders'),('mission_results','mission_id','missions'),
        ('mission_device_assignments','mission_id','missions'),('mission_device_assignments','device_id','devices'),
        ('order_checklist_items','order_id','orders')
    ) AS required(table_name,column_name,parent_name) LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_constraint c JOIN pg_attribute a ON a.attrelid=c.conrelid
            AND c.conkey=ARRAY[a.attnum] WHERE c.conrelid=to_regclass('public.'||e.table_name)
            AND a.attname=e.column_name AND a.attnotnull AND c.contype='f' AND c.convalidated
            AND c.confrelid=to_regclass('public.'||e.parent_name)) THEN
            RAISE EXCEPTION 'Mandatory ownership FK missing: %.%',e.table_name,e.column_name;
        END IF;
    END LOOP;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint c JOIN pg_attribute a ON a.attrelid=c.conrelid
        AND c.conkey=ARRAY[a.attnum] WHERE c.conrelid='public.mission_results'::regclass
        AND c.contype='u' AND c.convalidated AND a.attname='mission_id') THEN
        RAISE EXCEPTION 'MissionResult 1:0..1 uniqueness missing';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid='public.mission_checklist_executions'::regclass
        AND attname='mission_checklist_assignment_id' AND attnotnull) THEN
        RAISE EXCEPTION 'Execution assignment link must be NOT NULL';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid='public.order_checklist_items'::regclass
        AND attname='required_evidence_type' AND attnotnull
        AND format_type(atttypid,atttypmod)='character varying(30)') THEN
        RAISE EXCEPTION 'Order checklist required_evidence_type missing/invalid';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid='public.pricing_rules'::regclass
        AND attname='checklist_definition_id'
        AND format_type(atttypid,atttypmod)='character varying(36)') THEN
        RAISE EXCEPTION 'Pricing rule checklist target missing/invalid';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid='public.order_checklist_items'::regclass
        AND attname='pricing_status' AND attnotnull
        AND format_type(atttypid,atttypmod)='character varying(40)') THEN
        RAISE EXCEPTION 'Order checklist pricing_status missing/invalid';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='public.order_checklist_items'::regclass
        AND conname='ck_snapshot_evidence_policy' AND contype='c' AND convalidated
        AND pg_get_constraintdef(oid) LIKE '%evidence_policy_version%'
        AND pg_get_constraintdef(oid) LIKE '%required_evidence_type%'
        AND pg_get_constraintdef(oid) LIKE '%minimum_evidence_count%'
        AND pg_get_constraintdef(oid) LIKE '%IMAGE_OR_VIDEO%') THEN
        RAISE EXCEPTION 'Versioned configurable checklist evidence policy constraint missing';
    END IF;
    SELECT pg_get_constraintdef(oid) INTO actual FROM pg_constraint
        WHERE conrelid='public.resource_time_locks'::regclass AND conname='ex_resource_reservation_window';
    IF actual IS DISTINCT FROM 'EXCLUDE USING gist (resource_id WITH =, tstzrange(start_time, end_time, ''[)''::text) WITH &&) WHERE ((released_at IS NULL))' THEN
        RAISE EXCEPTION 'Reservation exclusion must use active half-open windows';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='public.missions'::regclass AND contype='c'
        AND convalidated AND pg_get_constraintdef(oid) LIKE '%safe_stop_status%'
        AND pg_get_constraintdef(oid) LIKE '%READY_FOR_CANCELLATION%'
        AND pg_get_constraintdef(oid) LIKE '%REQUESTED%'
        AND pg_get_constraintdef(oid) LIKE '%IN_PROGRESS%' AND pg_get_constraintdef(oid) LIKE '%FAILED%') THEN
        RAISE EXCEPTION 'Safe-stop enum check missing';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid='public.resource_reservation_mutex'::regclass
        AND attname='resource_id' AND attnotnull AND format_type(atttypid,atttypmod)='character varying(100)')
        OR NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='public.resource_reservation_mutex'::regclass AND contype='p') THEN
        RAISE EXCEPTION 'Resource reservation mutex missing/invalid';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='public.media_upload_failure_events'::regclass
        AND conname='uk_media_upload_failure_event' AND contype='u' AND convalidated
        AND pg_get_constraintdef(oid) LIKE '%event_key%') THEN
        RAISE EXCEPTION 'Upload failure event idempotency key missing';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid='public.media_upload_failure_events'::regclass
        AND attname='category' AND attnotnull
        AND format_type(atttypid,atttypmod)='character varying(40)') THEN
        RAISE EXCEPTION 'Upload failure event category must be NOT NULL varchar(40)';
    END IF;
    FOREACH actual IN ARRAY ARRAY['id','mission_id','media_id','attempt_id'] LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid='public.media_upload_failure_events'::regclass
            AND attname=actual AND NOT attisdropped AND attnotnull
            AND format_type(atttypid,atttypmod)='character varying(255)') THEN
            RAISE EXCEPTION 'Upload failure event % must be NOT NULL varchar(255)', actual;
        END IF;
    END LOOP;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='public.media_upload_failure_events'::regclass
        AND conname='ck_upload_failure_category' AND contype='c' AND convalidated
        AND pg_get_constraintdef(oid) LIKE '%TRANSFER_FAILED%'
        AND pg_get_constraintdef(oid) LIKE '%TRANSFER_ACK_LOST%') THEN
        RAISE EXCEPTION 'Upload failure category check missing';
    END IF;
    RAISE NOTICE 'Current Order/Mission schema verification PASS';
END $$;
SELECT extname,extversion FROM pg_extension WHERE extname IN ('postgis','vector','btree_gist') ORDER BY extname;
SELECT tablename,indexname,indexdef FROM pg_indexes WHERE schemaname='public'
    AND indexname IN ('uk_mission_creation_command','uk_mission_active_direct_replacement',
        'uk_mission_device_assignment_current_main','ex_resource_reservation_window','uk_evidence_active_pair')
    ORDER BY indexname;
COMMIT;

-- Read-only orphan cleanup audit.
SELECT id, media_status, storage_status, orphan_cleanup_requested_at, orphan_cleanup_claimed_at
FROM drone_media WHERE orphan_cleanup_requested_at IS NOT NULL;
