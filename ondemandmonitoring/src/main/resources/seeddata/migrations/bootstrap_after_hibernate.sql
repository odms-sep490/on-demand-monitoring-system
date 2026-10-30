-- =========================================================
-- LOCAL / TEST FRESH DATABASE BOOTSTRAP ONLY
-- =========================================================
-- Prerequisite: CURRENT Hibernate/JPA base schema created once.
-- Install postgis/vector BEFORE Hibernate: init-db/01-init-extensions.sql.
-- This consolidates post-Hibernate manual invariants, NOT migration history.
-- DO NOT run on an existing production database or migrate historical data.
-- Reference-data initializers remain responsible for seeds.
--
-- Usage (backend stopped while SQL runs; deployment role installs btree_gist):
-- 1. Empty local/test DB, required extensions; JPA_DDL_AUTO=create, start once.
-- 2. Stop backend.
-- 3. psql -h <local-host> -p <test-port> -U <role> -d <fresh-db>
--         -v ON_ERROR_STOP=1 -f bootstrap_after_hibernate.sql
--    Supply credentials securely, never embed passwords in this command.
-- 4. psql with ON_ERROR_STOP=1: verify_current_schema.sql (read-only gate).
-- 5. JPA_DDL_AUTO=validate; restart backend.
-- Rerunnable BEFORE runtime fixtures exist; drift fails instead of being hidden.
-- Historical backfills, temporary preservation snapshots, and intermediate
-- Order 1:1/global snapshot-execution uniqueness gates are intentionally absent.
-- =========================================================
BEGIN;
SET LOCAL search_path = public, pg_catalog;

-- Fail before any DDL. Do not manufacture an incomplete substitute JPA schema.
DO $$ DECLARE t text; occupied boolean; BEGIN
    FOREACH t IN ARRAY ARRAY['orders','missions','order_checklist_items',
        'mission_checklist_assignments','mission_checklist_executions',
        'mission_checklist_evidence','drone_media','mission_results',
        'resource_time_locks','mission_device_assignments','users','services','checklist_definitions'] LOOP
        IF to_regclass('public.' || t) IS NULL THEN
            RAISE EXCEPTION 'Hibernate prerequisite missing: public.%', t;
        END IF;
    END LOOP;
    FOREACH t IN ARRAY ARRAY['orders','missions','order_checklist_items',
        'mission_checklist_assignments','mission_checklist_executions',
        'mission_checklist_evidence','drone_media','mission_results',
        'resource_time_locks','mission_device_assignments'] LOOP
        EXECUTE format('SELECT EXISTS (SELECT 1 FROM public.%I)', t) INTO occupied;
        IF occupied THEN RAISE EXCEPTION 'Fresh bootstrap refused: public.% has runtime data', t; END IF;
    END LOOP;
END $$;
LOCK TABLE public.orders, public.missions, public.order_checklist_items,
    public.mission_checklist_assignments, public.mission_checklist_executions,
    public.mission_checklist_evidence, public.drone_media, public.mission_results,
    public.resource_time_locks, public.mission_device_assignments IN ACCESS EXCLUSIVE MODE;

-- These columns need no migration DDL in current JPA. Still reject incomplete
-- prerequisites: silently omitting safe-stop/release audit is not a bootstrap.
DO $$ DECLARE entry record; BEGIN
    FOR entry IN SELECT * FROM (VALUES
        ('missions','safe_stop_status','character varying(40)'),
        ('missions','safe_stop_requested_at','timestamp with time zone'),
        ('missions','safe_stop_requested_by','character varying(255)'),
        ('missions','safe_stop_reason','character varying(1000)'),
        ('missions','safe_stop_requires_landing','boolean'),
        ('missions','safe_stop_confirmed_at','timestamp with time zone'),
        ('missions','safe_stop_confirmed_by','character varying(255)'),
        ('missions','safe_stop_confirmation','character varying(1000)'),
        ('missions','safe_stop_cleaned_at','timestamp with time zone'),
        ('missions','safe_stop_failure_reason','character varying(1000)'),
        ('resource_time_locks','resource_type','character varying(20)'),
        ('resource_time_locks','released_at','timestamp with time zone'),
        ('resource_time_locks','release_reason','character varying(500)'),
        ('resource_time_locks','reserved_by','character varying(255)'),
        ('resource_time_locks','released_by','character varying(255)')
    ) AS columns(table_name,column_name,column_type) LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_attribute
            WHERE attrelid=to_regclass('public.' || entry.table_name)
                AND attname=entry.column_name AND NOT attisdropped
                AND format_type(atttypid,CASE WHEN entry.column_type='timestamp with time zone'
                    THEN NULL ELSE atttypmod END)=entry.column_type) THEN
            RAISE EXCEPTION 'Current JPA column missing/wrong type: %.%',entry.table_name,entry.column_name;
        END IF;
    END LOOP;
END $$;

-- Additional prerequisites: bootstrap markers and Phase 5/6 execution/evidence.
-- JPA already owns base columns/tables and enum/reason/timing checks.
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid='public.order_checklist_items'::regclass
        AND attname='required_evidence_type' AND NOT attisdropped) THEN
        ALTER TABLE public.order_checklist_items ADD COLUMN required_evidence_type varchar(30);
        UPDATE public.order_checklist_items SET required_evidence_type='IMAGE_OR_VIDEO';
        ALTER TABLE public.order_checklist_items ALTER COLUMN required_evidence_type SET NOT NULL;
    END IF;
END $$;
ALTER TABLE public.services ALTER COLUMN checklist_defaults_initialized SET DEFAULT false;
ALTER TABLE public.order_checklist_items ALTER COLUMN evidence_policy_version SET DEFAULT 0;
ALTER TABLE public.order_checklist_items ALTER COLUMN minimum_evidence_count SET DEFAULT 0;
ALTER TABLE public.order_checklist_items ALTER COLUMN required_evidence_type SET DEFAULT 'IMAGE_OR_VIDEO';

-- SECTION 1: Current multi-Mission identity and planning invariants.
ALTER TABLE public.orders ALTER COLUMN execution_plan_status SET DEFAULT 'DRAFT';
ALTER TABLE public.orders ALTER COLUMN execution_plan_revision SET DEFAULT 0;

-- Expected definitions are parsed by PostgreSQL itself: on rerun compare the
-- existing constraint with a short-lived duplicate, then discard the duplicate.
-- This also validates every constraint and refuses same-name incorrect schemas.
DO $$ DECLARE entry record; actual text; expected text; BEGIN
    FOR entry IN SELECT * FROM (VALUES
        ('services','ck_services_base_price_positive','CHECK (base_price > 0)'),
        ('orders','ck_order_execution_plan',
         'CHECK (execution_plan_status IN (''DRAFT'',''FINALIZED'') AND execution_plan_revision >= 0 AND (execution_plan_status <> ''FINALIZED'' OR (execution_plan_revision > 0 AND execution_plan_finalized_at IS NOT NULL)) AND (execution_plan_finalized_by IS NULL OR execution_plan_finalized_at IS NOT NULL) AND ((execution_plan_amended_at IS NULL AND execution_plan_amended_by IS NULL AND execution_plan_amendment_reason IS NULL) OR (execution_plan_amended_at IS NOT NULL AND execution_plan_amended_by IS NOT NULL AND execution_plan_amendment_reason IS NOT NULL AND length(trim(execution_plan_amendment_reason)) > 0)))'),
        ('missions','uk_mission_id_order','UNIQUE (id, order_id)'),
        ('order_checklist_items','uk_checklist_item_id_order','UNIQUE (id, order_id)'),
        ('missions','ck_mission_foundation',
         'CHECK ((replaces_mission_id IS NULL OR replaces_mission_id <> id) AND (cancelled_by IS NULL OR cancellation_at IS NOT NULL))'),
        ('missions','fk_mission_replaces_order',
         'FOREIGN KEY (replaces_mission_id, order_id) REFERENCES public.missions(id,order_id) ON DELETE RESTRICT'),
        ('mission_checklist_assignments','uk_assignment_identity',
         'UNIQUE (id,mission_id,order_id,order_checklist_item_id)'),
        ('mission_checklist_assignments','fk_assignment_mission_order',
         'FOREIGN KEY (mission_id,order_id) REFERENCES public.missions(id,order_id) ON DELETE RESTRICT'),
        ('mission_checklist_assignments','fk_assignment_item_order',
         'FOREIGN KEY (order_checklist_item_id,order_id) REFERENCES public.order_checklist_items(id,order_id) ON DELETE RESTRICT'),
        ('mission_checklist_assignments','ck_assignment_shape',
         'CHECK (display_order >= 0 AND version >= 0 AND ((removed_at IS NULL AND removed_by IS NULL AND removal_reason IS NULL) OR (removed_at IS NOT NULL AND removed_by IS NOT NULL AND length(trim(removed_by)) > 0 AND removed_at >= assigned_at)))'),
        ('mission_checklist_executions','uk_execution_assignment','UNIQUE (mission_checklist_assignment_id)'),
        ('mission_checklist_executions','fk_execution_assignment_identity',
         'FOREIGN KEY (mission_checklist_assignment_id,mission_id,order_id,order_checklist_item_id) REFERENCES public.mission_checklist_assignments(id,mission_id,order_id,order_checklist_item_id) ON DELETE RESTRICT')
,
        ('mission_checklist_executions','fk_execution_mission_order',
         'FOREIGN KEY (mission_id,order_id) REFERENCES public.missions(id,order_id)'),
        ('mission_checklist_executions','fk_execution_item_order',
         'FOREIGN KEY (order_checklist_item_id,order_id) REFERENCES public.order_checklist_items(id,order_id)'),
        ('order_checklist_items','ck_snapshot_evidence_policy',
         'CHECK ((evidence_policy_version = 0 AND minimum_evidence_count = 0 AND minimum_image_count = 0 AND minimum_video_count = 0) OR (evidence_policy_version = 1 AND minimum_evidence_count = 1 AND required_evidence_type = ''IMAGE_OR_VIDEO'' AND minimum_image_count = 0 AND minimum_video_count = 0) OR (evidence_policy_version = 2 AND minimum_evidence_count BETWEEN 1 AND 50 AND required_evidence_type IN (''IMAGE'',''VIDEO'',''IMAGE_OR_VIDEO'') AND minimum_image_count = 0 AND minimum_video_count = 0) OR (evidence_policy_version = 3 AND minimum_image_count >= 0 AND minimum_video_count >= 0 AND minimum_evidence_count = minimum_image_count + minimum_video_count AND minimum_evidence_count BETWEEN 1 AND 1000 AND required_evidence_type IN (''IMAGE'',''VIDEO'',''IMAGE_AND_VIDEO'',''IMAGE_OR_VIDEO'')))'),
        ('checklist_definitions','ck_checklist_pricing',
         'CHECK (base_price >= 0 AND included_image_count BETWEEN 0 AND 500 AND included_video_count BETWEEN 0 AND 500 AND (standard_video_duration_seconds IS NULL OR standard_video_duration_seconds BETWEEN 1 AND 7200))'),
        ('service_checklists','ck_service_checklist_overrides',
         'CHECK ((base_price_override IS NULL OR base_price_override >= 0) AND (included_image_count_override IS NULL OR included_image_count_override BETWEEN 0 AND 500) AND (included_video_count_override IS NULL OR included_video_count_override BETWEEN 0 AND 500))'),
        ('pricing_rules','ck_pricing_rule_media',
         'CHECK ((category = ''MEDIA'' AND pricing_profile IS NOT NULL AND media_component IS NOT NULL AND fixed_amount IS NOT NULL AND fixed_amount >= 0 AND (included_quantity IS NULL OR included_quantity BETWEEN 0 AND 500) AND (media_component NOT IN (''EXTRA_IMAGE'',''EXTRA_VIDEO'') OR (unit_size >= 1 AND COALESCE(min_value,0) >= 0 AND (max_value IS NULL OR max_value > COALESCE(min_value,0))))) OR (category <> ''MEDIA'' AND pricing_profile IS NULL AND media_component IS NULL AND included_quantity IS NULL AND rounding_mode IS NULL))'),
        ('services','ck_services_default_price',
         'CHECK (default_price IS NULL OR default_price >= 0)'),
        ('order_checklist_items','ck_order_checklist_manager_quote',
         'CHECK (manager_quoted_price IS NULL OR (manager_quote_required AND manager_quoted_price >= 0))'),
        ('order_checklist_items','ck_order_checklist_pricing_snapshot',
         'CHECK (pricing_snapshot_version IN (0, 1) AND base_price_snapshot >= 0 AND included_image_count_snapshot BETWEEN 0 AND 500 AND included_video_count_snapshot BETWEEN 0 AND 500 AND requested_image_count BETWEEN 0 AND 500 AND requested_video_count BETWEEN 0 AND 500 AND (pricing_snapshot_version = 1 OR (base_price_snapshot = 0 AND included_image_count_snapshot = 0 AND included_video_count_snapshot = 0 AND requested_image_count = 0 AND requested_video_count = 0)))'),
        ('mission_checklist_executions','uk_execution_id_mission','UNIQUE(id,mission_id)'),
        ('drone_media','uk_media_id_mission','UNIQUE(id,mission_id)'),
        ('mission_checklist_evidence','fk_evidence_execution_mission',
         'FOREIGN KEY(mission_checklist_execution_id,mission_id) REFERENCES public.mission_checklist_executions(id,mission_id) ON DELETE RESTRICT'),
        ('mission_checklist_evidence','fk_evidence_media_mission',
         'FOREIGN KEY(media_asset_id,mission_id) REFERENCES public.drone_media(id,mission_id) ON DELETE RESTRICT'),
        ('mission_checklist_evidence','fk_evidence_attached_user',
         'FOREIGN KEY(attached_by) REFERENCES public.users(id) ON DELETE RESTRICT'),
        ('mission_checklist_evidence','fk_evidence_detached_user',
         'FOREIGN KEY(detached_by) REFERENCES public.users(id) ON DELETE RESTRICT'),
        ('mission_checklist_evidence','ck_evidence_detach',
         'CHECK ((detached_at IS NULL AND detached_by IS NULL AND detach_reason IS NULL) OR (detached_at IS NOT NULL AND detached_by IS NOT NULL AND detached_at >= attached_at))')
    ) AS constraints(table_name,constraint_name,definition) LOOP
        SELECT pg_get_constraintdef(oid) INTO actual FROM pg_constraint
            WHERE conrelid=to_regclass('public.' || entry.table_name)
                AND conname=entry.constraint_name;
        IF actual IS NULL THEN
            EXECUTE format('ALTER TABLE public.%I ADD CONSTRAINT %I %s',
                entry.table_name,entry.constraint_name,entry.definition);
        ELSE
            EXECUTE format('ALTER TABLE public.%I ADD CONSTRAINT __bootstrap_expected %s',
                entry.table_name,entry.definition);
            SELECT pg_get_constraintdef(oid) INTO expected FROM pg_constraint
                WHERE conrelid=to_regclass('public.' || entry.table_name)
                    AND conname='__bootstrap_expected';
            IF actual IS DISTINCT FROM expected THEN
                RAISE EXCEPTION 'Constraint drift: %.%',entry.table_name,entry.constraint_name;
            END IF;
            EXECUTE format('ALTER TABLE public.%I DROP CONSTRAINT __bootstrap_expected',entry.table_name);
        END IF;
    END LOOP;
END $$;

-- Phase 5 migration deliberately replaces Hibernate approval-status enum checks.
DO $$ DECLARE n text; BEGIN
    FOR n IN SELECT conname FROM pg_constraint
        WHERE conrelid='public.mission_results'::regclass AND contype='c'
            AND pg_get_constraintdef(oid) LIKE '%approval_status%' LOOP
        EXECUTE format('ALTER TABLE public.mission_results DROP CONSTRAINT %I',n);
    END LOOP;
END $$;
ALTER TABLE public.mission_results ALTER COLUMN submitted_at DROP NOT NULL;
ALTER TABLE public.mission_results ADD CONSTRAINT ck_result_approval_status
    CHECK (approval_status IN ('DRAFT','PENDING_MANAGER_APPROVAL','APPROVED','REJECTED'));

-- SECTION 2: Order 1:N creation. Current JPA must not install legacy 1:1 gates.
-- SECTION 3: Assignment cutover. Current JPA supplies mandatory assignment link.
-- Refuse stale global uniqueness rather than silently repairing legacy schemas.
DO $$ DECLARE r record; key_number smallint; BEGIN
    FOR r IN SELECT * FROM (VALUES ('missions','order_id'),
        ('mission_checklist_executions','order_checklist_item_id'))
        AS gates(table_name,column_name) LOOP
        SELECT attnum INTO key_number FROM pg_attribute
            WHERE attrelid=to_regclass('public.' || r.table_name) AND attname=r.column_name
                AND attnotnull AND NOT attisdropped;
        IF key_number IS NULL THEN RAISE EXCEPTION 'Missing mandatory column: %.%',r.table_name,r.column_name; END IF;
        IF EXISTS (SELECT 1 FROM pg_index WHERE indrelid=to_regclass('public.' || r.table_name)
            AND indisunique AND indnkeyatts=1 AND indkey[0]=key_number AND indpred IS NULL) THEN
            RAISE EXCEPTION 'Legacy global uniqueness found on %.%',r.table_name,r.column_name;
        END IF;
    END LOOP;
END $$;
ALTER TABLE public.mission_checklist_executions ALTER COLUMN mission_checklist_assignment_id SET NOT NULL;

-- SECTION 4: Amendment/replacement uses ACTIVE direct replacement uniqueness,
-- not the obsolete global uk_mission_direct_replacement index.
-- SECTION 5: Resource concurrency and safe-stop (JPA owns safe-stop columns).
CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public;

-- SECTION 5a: Durable upload-failure event ledger.
-- Separate from media_storage_event_inbox on purpose: a client-reported failed transfer has
-- no object key, and asserting one would claim an object exists that never did.
CREATE TABLE IF NOT EXISTS public.media_upload_failure_events (
    id varchar(255) PRIMARY KEY,
    event_key varchar(64) NOT NULL,
    mission_id varchar(255) NOT NULL,
    media_id varchar(255) NOT NULL,
    attempt_id varchar(255) NOT NULL,
    attempt_number integer NOT NULL,
    category varchar(40) NOT NULL,
    occurred_at timestamptz NOT NULL,
    failure_code varchar(100),
    failure_message varchar(1000),
    processed_at timestamptz,
    processing_outcome varchar(40),
    created_at timestamptz NOT NULL,
    updated_at timestamptz,
    version bigint NOT NULL,
    CONSTRAINT uk_media_upload_failure_event UNIQUE (event_key),
    CONSTRAINT ck_upload_failure_category CHECK (category IN ('TRANSFER_FAILED','TRANSFER_ACK_LOST')),
    CONSTRAINT ck_upload_failure_outcome CHECK (processed_at IS NULL OR processing_outcome IS NOT NULL)
);
CREATE INDEX IF NOT EXISTS ix_upload_failure_pending
    ON public.media_upload_failure_events (occurred_at) WHERE processed_at IS NULL;
CREATE TABLE IF NOT EXISTS public.resource_reservation_mutex(resource_id varchar(100) PRIMARY KEY);
DO $$ BEGIN
    IF (SELECT count(*) FROM pg_attribute WHERE attrelid='public.resource_reservation_mutex'::regclass
        AND attnum>0 AND NOT attisdropped) <> 1
        OR NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid='public.resource_reservation_mutex'::regclass
            AND attname='resource_id' AND format_type(atttypid,atttypmod)='character varying(100)' AND attnotnull)
        OR NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='public.resource_reservation_mutex'::regclass
            AND contype='p' AND conkey=ARRAY[(SELECT attnum FROM pg_attribute
                WHERE attrelid='public.resource_reservation_mutex'::regclass AND attname='resource_id')])
    THEN RAISE EXCEPTION 'resource_reservation_mutex shape mismatch'; END IF;
    IF to_regclass('public.uk_resource_time_locks_resource_mission') IS NOT NULL
        OR to_regclass('public.uk_mission_direct_replacement') IS NOT NULL THEN
        RAISE EXCEPTION 'Obsolete reservation/replacement uniqueness found; not a fresh current schema';
    END IF;
END $$;

DO $$ DECLARE entry record; actual text; expected text; BEGIN
    FOR entry IN SELECT * FROM (VALUES
        ('resource_time_locks','ex_resource_reservation_window',
         'EXCLUDE USING gist(resource_id WITH =, tstzrange(start_time,end_time,''[)'') WITH &&) WHERE (released_at IS NULL)'),
        ('resource_time_locks','ck_resource_reservation_window_valid','CHECK(start_time<end_time)')
    ) AS constraints(table_name,constraint_name,definition) LOOP
        SELECT pg_get_constraintdef(oid) INTO actual FROM pg_constraint
            WHERE conrelid=to_regclass('public.' || entry.table_name) AND conname=entry.constraint_name;
        IF actual IS NULL THEN
            EXECUTE format('ALTER TABLE public.%I ADD CONSTRAINT %I %s',entry.table_name,entry.constraint_name,entry.definition);
        ELSE
            EXECUTE format('ALTER TABLE public.%I ADD CONSTRAINT __bootstrap_expected %s',entry.table_name,entry.definition);
            SELECT pg_get_constraintdef(oid) INTO expected FROM pg_constraint
                WHERE conrelid=to_regclass('public.' || entry.table_name) AND conname='__bootstrap_expected';
            IF actual IS DISTINCT FROM expected THEN RAISE EXCEPTION 'Constraint drift: %.%',entry.table_name,entry.constraint_name; END IF;
            EXECUTE format('ALTER TABLE public.%I DROP CONSTRAINT __bootstrap_expected',entry.table_name);
        END IF;
    END LOOP;
END $$;

-- Install final indexes only. Compare canonical definitions on rerun, including
-- predicates/key order/operator classes; a wrong existing definition aborts.
DO $$ DECLARE entry record; actual text; expected text; BEGIN
    FOR entry IN SELECT * FROM (VALUES
        ('uk_checklist_seed_code','UNIQUE','checklist_definitions(seed_code)'),
        ('uk_assignment_active_pair','UNIQUE','mission_checklist_assignments(mission_id,order_checklist_item_id) WHERE removed_at IS NULL'),
        ('ix_assignment_mission_order','','mission_checklist_assignments(mission_id,display_order,id)'),
        ('ix_assignment_coverage','','mission_checklist_assignments(order_id,order_checklist_item_id)'),
        ('ix_mission_order_schedule','','missions(order_id,scheduled_start_at,id)'),
        ('ix_execution_mission','','mission_checklist_executions(mission_id)'),
        ('uk_evidence_active_pair','UNIQUE','mission_checklist_evidence(mission_checklist_execution_id,media_asset_id) WHERE detached_at IS NULL'),
        ('ix_evidence_mission_execution','','mission_checklist_evidence(mission_id,mission_checklist_execution_id)'),
        ('ix_evidence_media','','mission_checklist_evidence(media_asset_id)'),
        ('uk_mission_active_direct_replacement','UNIQUE','missions(replaces_mission_id) WHERE replaces_mission_id IS NOT NULL AND status <> ''CANCELLED'''),
        -- At most one current MAIN assignment per Mission.
        -- History and concurrent SUPPORT rows remain valid; duplicate MAIN fails.
        ('uk_mission_device_assignment_current_main','UNIQUE','mission_device_assignments(mission_id) WHERE is_current = true AND device_role = ''MAIN'''),
        ('uk_resource_reservation_active_pair','UNIQUE','resource_time_locks(resource_id,mission_id) WHERE released_at IS NULL'),
        ('ix_resource_reservation_active_window','','resource_time_locks(resource_id,start_time,end_time) WHERE released_at IS NULL')
    ) AS indexes(index_name,uniqueness,definition) LOOP
        IF to_regclass('public.' || entry.index_name) IS NULL THEN
            EXECUTE format('CREATE %s INDEX %I ON public.%s',entry.uniqueness,entry.index_name,entry.definition);
        ELSE
            EXECUTE format('CREATE %s INDEX __bootstrap_expected_index ON public.%s',entry.uniqueness,entry.definition);
            SELECT regexp_replace(pg_get_indexdef(to_regclass('public.' || entry.index_name)),
                'INDEX [^ ]+ ON','INDEX <name> ON') INTO actual;
            SELECT regexp_replace(pg_get_indexdef('public.__bootstrap_expected_index'::regclass),
                'INDEX [^ ]+ ON','INDEX <name> ON') INTO expected;
            IF actual IS DISTINCT FROM expected OR NOT EXISTS (SELECT 1 FROM pg_index
                WHERE indexrelid=to_regclass('public.' || entry.index_name) AND indisvalid AND indisready) THEN
                RAISE EXCEPTION 'Index drift: %',entry.index_name;
            END IF;
            DROP INDEX public.__bootstrap_expected_index;
        END IF;
    END LOOP;
END $$;
CREATE INDEX IF NOT EXISTS ix_media_orphan_cleanup ON public.drone_media(orphan_cleanup_requested_at, id)
    WHERE orphan_cleanup_requested_at IS NOT NULL AND storage_status <> 'DELETED';
COMMIT;
