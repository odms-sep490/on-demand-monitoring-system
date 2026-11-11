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
    FOREACH t IN ARRAY ARRAY['orders','order_execution_plans','order_flight_compliance','order_flight_area_reviews','missions','order_checklist_items',
        'mission_checklist_assignments','mission_checklist_executions',
        'mission_checklist_evidence','drone_media','mission_results',
        'resource_time_locks','mission_device_assignments','users','services','checklist_definitions'] LOOP
        IF to_regclass('public.' || t) IS NULL THEN
            RAISE EXCEPTION 'Hibernate prerequisite missing: public.%', t;
        END IF;
    END LOOP;
    FOREACH t IN ARRAY ARRAY['orders','order_execution_plans','order_flight_compliance','order_flight_area_reviews','missions','order_checklist_items',
        'mission_checklist_assignments','mission_checklist_executions',
        'mission_checklist_evidence','drone_media','mission_results',
        'resource_time_locks','mission_device_assignments'] LOOP
        EXECUTE format('SELECT EXISTS (SELECT 1 FROM public.%I)', t) INTO occupied;
        IF occupied THEN RAISE EXCEPTION 'Fresh bootstrap refused: public.% has runtime data', t; END IF;
    END LOOP;
END $$;
LOCK TABLE public.orders, public.order_execution_plans, public.order_flight_compliance, public.order_flight_area_reviews,
    public.missions, public.order_checklist_items,
    public.mission_checklist_assignments, public.mission_checklist_executions,
    public.mission_checklist_evidence, public.drone_media, public.mission_results,
    public.resource_time_locks, public.mission_device_assignments IN ACCESS EXCLUSIVE MODE;

-- These columns need no migration DDL in current JPA. Still reject incomplete
-- prerequisites: failure-request and safe-stop/release audit are operational state.
DO $$ DECLARE entry record; BEGIN
    FOR entry IN SELECT * FROM (VALUES
        ('missions','failure_requested_at','timestamp with time zone'),
        ('missions','failure_reason','character varying(1000)'),
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
ALTER TABLE public.order_execution_plans ALTER COLUMN status SET DEFAULT 'DRAFT';
ALTER TABLE public.order_execution_plans ALTER COLUMN revision SET DEFAULT 0;

-- Expected definitions are parsed by PostgreSQL itself: on rerun compare the
-- existing constraint with a short-lived duplicate, then discard the duplicate.
-- This also validates every constraint and refuses same-name incorrect schemas.
DO $$ DECLARE entry record; actual text; expected text; BEGIN
    FOR entry IN SELECT * FROM (VALUES
        ('services','ck_services_base_price_positive','CHECK (base_price > 0)'),
        ('order_execution_plans','ck_order_execution_plan',
         'CHECK (status IN (''DRAFT'',''FINALIZED'') AND revision >= 0 AND (status <> ''FINALIZED'' OR (revision > 0 AND finalized_at IS NOT NULL)) AND (finalized_by IS NULL OR finalized_at IS NOT NULL))'),
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

-- SECTION 6: Current order checklist revisions (fresh schema, no historical backfill).
-- Adds optimistic revision tracking and versioned snapshots for customer order checklist updates.
ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS checklist_revision BIGINT NOT NULL DEFAULT 0;


ALTER TABLE order_checklist_items
    ADD COLUMN IF NOT EXISTS checklist_revision BIGINT NOT NULL DEFAULT 1;


ALTER TABLE order_checklist_items
    DROP CONSTRAINT IF EXISTS uk_order_checklist_source;

ALTER TABLE order_checklist_items
    ADD CONSTRAINT uk_order_checklist_source UNIQUE (order_id, checklist_revision, source_checklist_id);

CREATE INDEX IF NOT EXISTS ix_order_checklist_order_rev
    ON order_checklist_items (order_id, checklist_revision, display_order, id);

CREATE TABLE IF NOT EXISTS order_checklist_revisions (
    id VARCHAR(255) PRIMARY KEY,
    order_id VARCHAR(255) NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    revision BIGINT NOT NULL,
    actor_id VARCHAR(255) REFERENCES users(id),
    change_summary VARCHAR(2000) NOT NULL,
    payload_fingerprint VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_order_checklist_revision UNIQUE (order_id, revision)
);

CREATE INDEX IF NOT EXISTS ix_order_checklist_revision_order
    ON order_checklist_revisions (order_id, revision);


ALTER TABLE quotes
    ADD COLUMN IF NOT EXISTS checklist_revision BIGINT NOT NULL DEFAULT 1;

-- SECTION 7: Customer-requested checklist guidance.
-- Additive, rerunnable foundation only. Historical catalog snapshots and quotes stay unchanged.
ALTER TABLE order_checklist_items
    ADD COLUMN IF NOT EXISTS custom_guidance JSONB,
    ADD COLUMN IF NOT EXISTS guidance_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS guidance_confirmed_version BIGINT,
    ADD COLUMN IF NOT EXISTS guidance_updated_by VARCHAR(255),
    ADD COLUMN IF NOT EXISTS guidance_updated_at TIMESTAMPTZ;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_order_checklist_guidance_updated_by'
          AND conrelid = 'order_checklist_items'::regclass
    ) THEN
        ALTER TABLE order_checklist_items
            ADD CONSTRAINT fk_order_checklist_guidance_updated_by
            FOREIGN KEY (guidance_updated_by) REFERENCES users(id);
    END IF;
END $$;

ALTER TABLE quotes
    ADD COLUMN IF NOT EXISTS checklist_guidance_snapshot JSONB;

-- SECTION 8: Customer result review persistence.
-- Additive persistence foundation for customer review rounds and structured revision requests.
ALTER TABLE order_deliveries
    ADD COLUMN IF NOT EXISTS current_review_round_id VARCHAR(255);

ALTER TABLE support_customer_tickets
    ADD COLUMN IF NOT EXISTS ticket_kind VARCHAR(40) NOT NULL DEFAULT 'GENERAL';

CREATE TABLE IF NOT EXISTS result_review_rounds (
    id VARCHAR(255) PRIMARY KEY,
    order_id VARCHAR(255) NOT NULL REFERENCES orders(id),
    delivery_id VARCHAR(255) NOT NULL REFERENCES order_deliveries(id),
    round_number INTEGER NOT NULL CHECK (round_number > 0),
    checklist_revision BIGINT NOT NULL,
    published_at TIMESTAMPTZ NOT NULL,
    published_by VARCHAR(255) REFERENCES users(id),
    schema_version INTEGER NOT NULL DEFAULT 1 CHECK (schema_version > 0),
    status VARCHAR(40) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','REVISION_REQUESTED','ACCEPTED','SUPERSEDED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_result_review_round_order_number UNIQUE (order_id, round_number)
);
CREATE INDEX IF NOT EXISTS ix_result_review_round_order_number ON result_review_rounds(order_id, round_number DESC);

-- The FK is installed after table creation to keep fresh and existing databases compatible.
DO $$ BEGIN
    ALTER TABLE order_deliveries ADD CONSTRAINT fk_order_delivery_current_review_round
        FOREIGN KEY (current_review_round_id) REFERENCES result_review_rounds(id);
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

CREATE TABLE IF NOT EXISTS result_review_round_items (
    id VARCHAR(255) PRIMARY KEY,
    round_id VARCHAR(255) NOT NULL REFERENCES result_review_rounds(id),
    order_checklist_item_id VARCHAR(255) NOT NULL REFERENCES order_checklist_items(id),
    content_snapshot VARCHAR(500) NOT NULL,
    evidence_policy_snapshot JSONB,
    guidance_snapshot JSONB,
    display_order INTEGER NOT NULL CHECK (display_order >= 0),
    execution_id VARCHAR(255),
    mission_id VARCHAR(255),
    mission_result_id VARCHAR(255),
    result_version VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ, version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_result_review_round_item_checklist UNIQUE (round_id, order_checklist_item_id)
);
CREATE INDEX IF NOT EXISTS ix_result_review_round_item_order ON result_review_round_items(round_id, display_order);

CREATE TABLE IF NOT EXISTS result_review_round_evidence (
    id VARCHAR(255) PRIMARY KEY,
    round_item_id VARCHAR(255) NOT NULL REFERENCES result_review_round_items(id),
    media_asset_id VARCHAR(255) NOT NULL REFERENCES drone_media(id),
    execution_id VARCHAR(255), evidence_id VARCHAR(255), file_name_snapshot VARCHAR(1000),
    media_type_snapshot VARCHAR(100), captured_at_snapshot TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ, version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_result_review_round_evidence_media UNIQUE (round_item_id, media_asset_id)
);
CREATE INDEX IF NOT EXISTS ix_result_review_round_evidence_item_media ON result_review_round_evidence(round_item_id, media_asset_id);

CREATE TABLE IF NOT EXISTS result_revision_requests (
    id VARCHAR(255) PRIMARY KEY,
    round_id VARCHAR(255) NOT NULL REFERENCES result_review_rounds(id),
    order_id VARCHAR(255) NOT NULL REFERENCES orders(id),
    customer_id VARCHAR(255) NOT NULL REFERENCES users(id),
    ticket_id VARCHAR(255) NOT NULL REFERENCES support_customer_tickets(id),
    client_request_id UUID NOT NULL,
    payload_fingerprint VARCHAR(128) NOT NULL,
    general_note VARCHAR(2000), submitted_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ, version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_result_revision_request_round UNIQUE (round_id),
    CONSTRAINT uk_result_revision_request_customer_key UNIQUE (customer_id, client_request_id)
);
CREATE INDEX IF NOT EXISTS ix_result_revision_request_order_submitted ON result_revision_requests(order_id, submitted_at DESC);

CREATE TABLE IF NOT EXISTS result_revision_request_items (
    id VARCHAR(255) PRIMARY KEY,
    request_id VARCHAR(255) NOT NULL REFERENCES result_revision_requests(id),
    round_item_id VARCHAR(255) NOT NULL REFERENCES result_review_round_items(id),
    reason_code VARCHAR(40) NOT NULL CHECK (reason_code IN ('MISSING_EVIDENCE','POOR_QUALITY','WRONG_LOCATION','REQUIREMENT_NOT_MET','OTHER')),
    note VARCHAR(2000) NOT NULL, display_order INTEGER NOT NULL CHECK (display_order >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ, version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_result_revision_request_item_round_item UNIQUE (request_id, round_item_id)
);
CREATE INDEX IF NOT EXISTS ix_result_revision_request_item_order ON result_revision_request_items(request_id, display_order);

CREATE TABLE IF NOT EXISTS result_revision_request_item_media (
    id VARCHAR(255) PRIMARY KEY,
    request_item_id VARCHAR(255) NOT NULL REFERENCES result_revision_request_items(id),
    round_evidence_id VARCHAR(255) NOT NULL REFERENCES result_review_round_evidence(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ, version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_result_revision_request_item_media_pair UNIQUE (request_item_id, round_evidence_id)
);
CREATE INDEX IF NOT EXISTS ix_result_revision_request_item_media_evidence ON result_revision_request_item_media(round_evidence_id);
CREATE INDEX IF NOT EXISTS ix_support_customer_ticket_kind_status_opened
    ON support_customer_tickets(ticket_kind, status, opened_at);
-- END CUSTOMER RESULT REVIEW

-- SECTION 9: Continuous result-revision case and auditable Manager decisions.
CREATE TABLE IF NOT EXISTS checklist_delivery_drafts (
    id VARCHAR(255) PRIMARY KEY,
    order_id VARCHAR(255) NOT NULL UNIQUE REFERENCES orders(id) ON DELETE CASCADE,
    case_id VARCHAR(255),
    base_review_round_id VARCHAR(255),
    expected_delivery_version BIGINT,
    notes VARCHAR(2000),
    checklist_operations JSONB NOT NULL DEFAULT '[]'::jsonb,
    updated_by VARCHAR(255) NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ, version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS ix_checklist_delivery_draft_case ON checklist_delivery_drafts(case_id);

ALTER TABLE order_deliveries ADD COLUMN IF NOT EXISTS last_checklist_publish_request_id UUID;
ALTER TABLE order_deliveries ADD COLUMN IF NOT EXISTS last_checklist_publish_fingerprint VARCHAR(128);
ALTER TABLE result_review_round_items ADD COLUMN IF NOT EXISTS changed_in_round BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE result_review_round_evidence ADD COLUMN IF NOT EXISTS source_round_evidence_id VARCHAR(255);
ALTER TABLE result_review_round_evidence ADD COLUMN IF NOT EXISTS supersedes_round_evidence_id VARCHAR(255);
ALTER TABLE result_review_round_evidence ADD COLUMN IF NOT EXISTS change_type VARCHAR(30) NOT NULL DEFAULT 'INITIAL'
    CHECK (change_type IN ('INITIAL','RETAINED','ADDED','REPLACEMENT'));

CREATE TABLE IF NOT EXISTS result_revision_cases (
    id VARCHAR(255) PRIMARY KEY,
    order_id VARCHAR(255) NOT NULL REFERENCES orders(id),
    ticket_id VARCHAR(255) NOT NULL UNIQUE REFERENCES support_customer_tickets(id),
    status VARCHAR(30) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','CLOSED')),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    opened_at TIMESTAMPTZ NOT NULL,
    closed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ, version BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_result_revision_case_active_order
    ON result_revision_cases(order_id) WHERE active;
CREATE INDEX IF NOT EXISTS ix_result_revision_case_order_active
    ON result_revision_cases(order_id, active);

CREATE TABLE IF NOT EXISTS result_revision_case_items (
    id VARCHAR(255) PRIMARY KEY,
    case_id VARCHAR(255) NOT NULL REFERENCES result_revision_cases(id),
    order_checklist_item_id VARCHAR(255) NOT NULL REFERENCES order_checklist_items(id),
    scope_decision VARCHAR(30) NOT NULL DEFAULT 'PENDING' CHECK (scope_decision IN ('PENDING','IN_SCOPE','OUT_OF_SCOPE')),
    strategy VARCHAR(30) NOT NULL DEFAULT 'UNDECIDED' CHECK (strategy IN ('UNDECIDED','RESERVE_MEDIA','REWORK')),
    status VARCHAR(40) NOT NULL DEFAULT 'NEEDS_TRIAGE' CHECK (status IN ('NEEDS_TRIAGE','IN_PROGRESS','WAITING_CUSTOMER','RESOLVED','OUT_OF_SCOPE_RESPONDED')),
    scope_reason VARCHAR(2000), manager_instructions VARCHAR(2000),
    scope_decided_by VARCHAR(255), scope_decided_at TIMESTAMPTZ,
    assigned_rework_mission_id VARCHAR(255), out_of_scope_notification_key UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ, version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_result_revision_case_checklist UNIQUE (case_id, order_checklist_item_id)
);
CREATE INDEX IF NOT EXISTS ix_result_revision_case_item_status ON result_revision_case_items(case_id, status);
ALTER TABLE result_revision_case_items ADD COLUMN IF NOT EXISTS source_mission_id VARCHAR(255);
ALTER TABLE result_revision_case_items ADD COLUMN IF NOT EXISTS out_of_scope_acknowledged_by VARCHAR(255);
ALTER TABLE result_revision_case_items ADD COLUMN IF NOT EXISTS out_of_scope_acknowledged_at TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS mission_rework_checklist_contexts (
    id VARCHAR(255) PRIMARY KEY,
    mission_id VARCHAR(255) NOT NULL REFERENCES missions(id) ON DELETE RESTRICT,
    case_item_id VARCHAR(255) NOT NULL REFERENCES result_revision_case_items(id) ON DELETE RESTRICT,
    order_checklist_item_id_snapshot VARCHAR(255) NOT NULL,
    checklist_title_snapshot VARCHAR(500) NOT NULL,
    manager_instructions_snapshot VARCHAR(2000),
    source_feedback_item_ids JSONB NOT NULL,
    guidance_snapshot JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ, version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_mission_rework_context_item UNIQUE (mission_id, case_item_id)
);
CREATE INDEX IF NOT EXISTS ix_mission_rework_context_mission
    ON mission_rework_checklist_contexts(mission_id, created_at);
ALTER TABLE result_revision_case_items DROP CONSTRAINT IF EXISTS result_revision_case_items_status_check;
ALTER TABLE result_revision_case_items DROP CONSTRAINT IF EXISTS ck_result_revision_case_item_status;
ALTER TABLE result_revision_case_items ADD CONSTRAINT ck_result_revision_case_item_status
    CHECK (status IN ('NEEDS_TRIAGE','IN_PROGRESS','WAITING_CUSTOMER','RESOLVED','OUT_OF_SCOPE_RESPONDED','OUT_OF_SCOPE_ACKNOWLEDGED'));

ALTER TABLE missions ADD COLUMN IF NOT EXISTS mission_purpose VARCHAR(20) NOT NULL DEFAULT 'ORIGINAL'
    CHECK (mission_purpose IN ('ORIGINAL','REWORK'));
ALTER TABLE missions ADD COLUMN IF NOT EXISTS result_revision_case_id VARCHAR(255);
ALTER TABLE missions ADD COLUMN IF NOT EXISTS rework_request_id UUID;
ALTER TABLE missions ADD COLUMN IF NOT EXISTS rework_request_fingerprint VARCHAR(64);
CREATE UNIQUE INDEX IF NOT EXISTS uq_mission_rework_request
    ON missions(rework_request_id) WHERE rework_request_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_mission_result_revision_case
    ON missions(result_revision_case_id, mission_purpose);
DO $$ BEGIN
    ALTER TABLE missions ADD CONSTRAINT fk_mission_result_revision_case
        FOREIGN KEY (result_revision_case_id) REFERENCES result_revision_cases(id) ON DELETE RESTRICT;
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;
DO $$ BEGIN
    ALTER TABLE result_revision_case_items ADD CONSTRAINT fk_case_item_source_mission
        FOREIGN KEY (source_mission_id) REFERENCES missions(id) ON DELETE RESTRICT;
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

ALTER TABLE result_revision_requests ADD COLUMN IF NOT EXISTS case_id VARCHAR(255);
DO $$ BEGIN
    ALTER TABLE result_revision_requests ADD CONSTRAINT fk_result_revision_request_case
        FOREIGN KEY (case_id) REFERENCES result_revision_cases(id);
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;
ALTER TABLE result_revision_requests DROP CONSTRAINT IF EXISTS uk_result_revision_request_ticket;
CREATE INDEX IF NOT EXISTS ix_result_revision_request_case_submitted
    ON result_revision_requests(case_id, submitted_at DESC);

ALTER TABLE result_revision_request_items ADD COLUMN IF NOT EXISTS case_item_id VARCHAR(255);
DO $$ BEGIN
    ALTER TABLE result_revision_request_items ADD CONSTRAINT fk_result_revision_request_case_item
        FOREIGN KEY (case_item_id) REFERENCES result_revision_case_items(id);
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;
CREATE INDEX IF NOT EXISTS ix_result_revision_request_case_item
    ON result_revision_request_items(case_item_id, created_at);

-- END RESULT REVISION CASE

-- Inspector acceptance and per-checklist customer bundle; reserve media remains unpublished.
ALTER TABLE mission_results ADD COLUMN IF NOT EXISTS inspector_confirmed BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE mission_checklist_executions
    ADD COLUMN IF NOT EXISTS assessment_note VARCHAR(2000),
    ADD COLUMN IF NOT EXISTS assessed_by VARCHAR(255),
    ADD COLUMN IF NOT EXISTS assessed_at TIMESTAMPTZ;
ALTER TABLE mission_checklist_evidence
    ADD COLUMN IF NOT EXISTS selected_for_customer BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS selected_by VARCHAR(255),
    ADD COLUMN IF NOT EXISTS selected_at TIMESTAMPTZ;

-- Review/rework cleanup, kept in this single post-Hibernate bootstrap.
-- The fresh-database guard above must pass before these obsolete objects are removed.
ALTER TABLE result_review_rounds DROP COLUMN IF EXISTS published_payload;
DROP TABLE IF EXISTS result_revision_case_decisions;
DROP TABLE IF EXISTS media_notification_outbox;
DROP TABLE IF EXISTS result_revision_media_holds;

-- Review/rework invariants. Never infer physical foreign keys solely from scalar Java IDs.
DO $$ DECLARE invariant record; BEGIN
    FOR invariant IN SELECT * FROM (VALUES
        ('result_revision_cases','ck_revision_case_lifecycle',
         'CHECK ((status = ''OPEN'' AND active AND closed_at IS NULL) OR (status = ''CLOSED'' AND NOT active AND closed_at IS NOT NULL))'),
        ('result_revision_case_items','ck_revision_case_scope_strategy',
         'CHECK ((scope_decision = ''PENDING'' AND strategy = ''UNDECIDED'') OR (scope_decision = ''OUT_OF_SCOPE'' AND strategy = ''UNDECIDED'') OR (scope_decision = ''IN_SCOPE'' AND strategy IN (''RESERVE_MEDIA'',''REWORK'')))'),
        ('result_revision_cases','fk_revision_case_ticket','FOREIGN KEY(ticket_id) REFERENCES support_customer_tickets(id) ON DELETE RESTRICT'),
        ('result_revision_case_items','fk_revision_case_assigned_mission','FOREIGN KEY(assigned_rework_mission_id) REFERENCES missions(id) ON DELETE RESTRICT'),
        ('result_revision_case_items','fk_revision_case_source_mission','FOREIGN KEY(source_mission_id) REFERENCES missions(id) ON DELETE RESTRICT'),
        ('result_review_rounds','fk_review_round_delivery','FOREIGN KEY(delivery_id) REFERENCES order_deliveries(id) ON DELETE RESTRICT'),
        ('order_deliveries','fk_delivery_review_round','FOREIGN KEY(current_review_round_id) REFERENCES result_review_rounds(id) ON DELETE RESTRICT'),
        ('result_revision_requests','fk_revision_request_customer','FOREIGN KEY(customer_id) REFERENCES users(id) ON DELETE RESTRICT'),
        ('result_revision_requests','fk_revision_request_ticket','FOREIGN KEY(ticket_id) REFERENCES support_customer_tickets(id) ON DELETE RESTRICT'),
        ('result_review_round_evidence','fk_review_evidence_source','FOREIGN KEY(source_round_evidence_id) REFERENCES result_review_round_evidence(id) ON DELETE RESTRICT'),
        ('result_review_round_evidence','fk_review_evidence_replaced','FOREIGN KEY(supersedes_round_evidence_id) REFERENCES result_review_round_evidence(id) ON DELETE RESTRICT'),
        ('checklist_delivery_drafts','fk_delivery_draft_case','FOREIGN KEY(case_id) REFERENCES result_revision_cases(id) ON DELETE RESTRICT'),
        ('checklist_delivery_drafts','fk_delivery_draft_round','FOREIGN KEY(base_review_round_id) REFERENCES result_review_rounds(id) ON DELETE RESTRICT'),
        ('checklist_delivery_drafts','fk_delivery_draft_actor','FOREIGN KEY(updated_by) REFERENCES users(id) ON DELETE RESTRICT')
    ) AS definitions(table_name,constraint_name,definition) LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_constraint
                WHERE conrelid = to_regclass('public.' || invariant.table_name)
                  AND (conname = invariant.constraint_name OR (
                    contype = 'f' AND confdeltype IN ('a','r')
                    AND invariant.definition LIKE 'FOREIGN KEY%'
                    AND replace(regexp_replace(pg_get_constraintdef(oid), '\s', '', 'g'), 'public.', '')
                        LIKE regexp_replace(split_part(invariant.definition, ' ON DELETE ', 1), '\s', '', 'g') || '%'
                  ))) THEN
            EXECUTE format('ALTER TABLE public.%I ADD CONSTRAINT %I %s',
                    invariant.table_name,invariant.constraint_name,invariant.definition);
        END IF;
    END LOOP;
END $$;
CREATE UNIQUE INDEX IF NOT EXISTS uq_result_revision_case_active_order
    ON result_revision_cases(order_id) WHERE active;
CREATE INDEX IF NOT EXISTS ix_revision_feedback_case_created
    ON result_revision_request_items(case_item_id,created_at,id);
CREATE INDEX IF NOT EXISTS ix_revision_case_assigned_mission
    ON result_revision_case_items(assigned_rework_mission_id) WHERE assigned_rework_mission_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_review_evidence_source
    ON result_review_round_evidence(source_round_evidence_id) WHERE source_round_evidence_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_review_evidence_replaced
    ON result_review_round_evidence(supersedes_round_evidence_id) WHERE supersedes_round_evidence_id IS NOT NULL;

ALTER TABLE public.drone_media
    ADD COLUMN IF NOT EXISTS derivative_status varchar(24) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN IF NOT EXISTS thumbnail_key varchar(1000),
    ADD COLUMN IF NOT EXISTS preview_key varchar(1000),
    ADD COLUMN IF NOT EXISTS thumbnail_size_bytes bigint,
    ADD COLUMN IF NOT EXISTS preview_size_bytes bigint,
    ADD COLUMN IF NOT EXISTS derivative_attempt_count integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS derivative_processed_at timestamptz,
    ADD COLUMN IF NOT EXISTS derivative_error_code varchar(80);

DROP INDEX IF EXISTS public.ix_drone_media_derivative_queue;
CREATE INDEX IF NOT EXISTS ix_drone_media_derivative_queue_v2
    ON public.drone_media (created_at, id)
    WHERE storage_provider = 'S3' AND storage_status = 'AVAILABLE'
      AND media_type IN ('IMAGE', 'VIDEO') AND derivative_status IN ('PENDING', 'FAILED', 'PROCESSING');

-- Independent delivery/review state per original Mission. NULL retains historical order-level deliveries.
ALTER TABLE order_deliveries ADD COLUMN IF NOT EXISTS mission_id varchar(255);
ALTER TABLE checklist_delivery_drafts ADD COLUMN IF NOT EXISTS mission_id varchar(255);
ALTER TABLE result_review_rounds ADD COLUMN IF NOT EXISTS mission_id varchar(255);

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['order_deliveries', 'checklist_delivery_drafts', 'result_review_rounds'] LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = table_name::regclass AND conname = 'fk_' || table_name || '_mission') THEN
            EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I FOREIGN KEY (mission_id) REFERENCES missions(id)',
                table_name, 'fk_' || table_name || '_mission');
        END IF;
    END LOOP;
END $$;

DO $$
DECLARE row record;
BEGIN
    FOR row IN
        SELECT c.conrelid::regclass AS table_name, c.conname
        FROM pg_constraint c
        JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attname = 'order_id'
        WHERE c.contype = 'u' AND c.conrelid IN ('order_deliveries'::regclass, 'checklist_delivery_drafts'::regclass)
          AND c.conkey = ARRAY[a.attnum]::smallint[]
    LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', row.table_name, row.conname);
    END LOOP;
END $$;
CREATE UNIQUE INDEX IF NOT EXISTS uq_delivery_legacy_order ON order_deliveries(order_id) WHERE mission_id IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_delivery_mission ON order_deliveries(mission_id) WHERE mission_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_delivery_draft_legacy_order ON checklist_delivery_drafts(order_id) WHERE mission_id IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_delivery_draft_mission ON checklist_delivery_drafts(mission_id) WHERE mission_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_review_round_mission_number ON result_review_rounds(order_id, mission_id, round_number DESC);

-- SECTION: Cross-domain Admin audit log (ADM-06).
CREATE TABLE IF NOT EXISTS admin_audit_logs (
    id VARCHAR(255) PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    actor_id VARCHAR(255) NOT NULL,
    actor_name VARCHAR(255) NOT NULL,
    action VARCHAR(30) NOT NULL CHECK (action IN ('CREATE','UPDATE','DELETE','APPROVE','STATUS_CHANGE')),
    entity_type VARCHAR(80) NOT NULL,
    entity_id VARCHAR(255) NOT NULL,
    ip VARCHAR(64),
    user_agent VARCHAR(512),
    before_json JSONB,
    after_json JSONB
);
CREATE INDEX IF NOT EXISTS ix_admin_audit_logs_created_at ON admin_audit_logs(created_at);
CREATE INDEX IF NOT EXISTS ix_admin_audit_logs_actor_id ON admin_audit_logs(actor_id);
CREATE INDEX IF NOT EXISTS ix_admin_audit_logs_entity_type ON admin_audit_logs(entity_type);

COMMIT;
