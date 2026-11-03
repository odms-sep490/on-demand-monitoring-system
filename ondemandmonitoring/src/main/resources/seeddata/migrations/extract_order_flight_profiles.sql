-- One-time upgrade for databases that stored flight-permit and flight-area
-- review fields directly on orders. Stop the application before running this.
-- Run after the updated Hibernate schema has created both profile tables.
-- Existing order data is copied before the legacy columns are removed.
BEGIN;
SET LOCAL search_path = public, pg_catalog;

DO $$
BEGIN
    IF to_regclass('public.orders') IS NULL THEN
        RAISE EXCEPTION 'Required table public.orders is missing';
    END IF;
    IF to_regclass('public.order_flight_compliance') IS NULL
       OR to_regclass('public.order_flight_area_reviews') IS NULL
       OR to_regclass('public.order_execution_plans') IS NULL THEN
        RAISE EXCEPTION 'Run the updated application schema first; flight profile tables are missing';
    END IF;

    IF (
        SELECT count(*) = 15
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'orders'
          AND column_name IN (
              'permit_status', 'permit_number', 'permit_expires_on', 'permit_documents',
              'monitoring_relationship', 'monitoring_authorization_confirmed',
              'monitoring_authorization_documents', 'permit_required', 'permit_zone_name',
              'legal_review_status', 'flight_area_review_reason', 'flight_area_review_note',
              'requested_scope_feasible', 'flight_area_reviewed_by', 'flight_area_reviewed_at'
          )
    ) THEN
        EXECUTE $copy_compliance$
            INSERT INTO order_flight_compliance (
                id, created_at, updated_at, version, order_id, permit_status, permit_number,
                permit_expires_on, permit_documents, monitoring_relationship,
                monitoring_authorization_confirmed, monitoring_authorization_documents,
                permit_required, permit_zone_name
            )
            SELECT gen_random_uuid()::varchar, COALESCE(o.created_at, now()), o.updated_at, 0,
                   o.id, o.permit_status, o.permit_number, o.permit_expires_on,
                   o.permit_documents, o.monitoring_relationship,
                   COALESCE(o.monitoring_authorization_confirmed, false),
                   o.monitoring_authorization_documents, o.permit_required, o.permit_zone_name
            FROM orders o
            ON CONFLICT (order_id) DO NOTHING
        $copy_compliance$;

        EXECUTE $copy_review$
            INSERT INTO order_flight_area_reviews (
                id, created_at, updated_at, version, order_id, legal_review_status,
                flight_area_review_reason, flight_area_review_note, requested_scope_feasible,
                flight_area_reviewed_by, flight_area_reviewed_at
            )
            SELECT gen_random_uuid()::varchar, COALESCE(o.created_at, now()), o.updated_at, 0,
                   o.id, COALESCE(o.legal_review_status, 'NOT_REVIEWED'),
                   o.flight_area_review_reason, o.flight_area_review_note,
                   o.requested_scope_feasible, o.flight_area_reviewed_by, o.flight_area_reviewed_at
            FROM orders o
            ON CONFLICT (order_id) DO NOTHING
        $copy_review$;
    ELSE
        INSERT INTO order_flight_compliance (
            id, created_at, updated_at, version, order_id, monitoring_authorization_confirmed
        )
        SELECT gen_random_uuid()::varchar, COALESCE(o.created_at, now()), o.updated_at, 0, o.id, false
        FROM orders o
        ON CONFLICT (order_id) DO NOTHING;

        INSERT INTO order_flight_area_reviews (
            id, created_at, updated_at, version, order_id, legal_review_status
        )
        SELECT gen_random_uuid()::varchar, COALESCE(o.created_at, now()), o.updated_at, 0,
               o.id, 'NOT_REVIEWED'
        FROM orders o
        ON CONFLICT (order_id) DO NOTHING;
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'orders'
          AND column_name = 'execution_plan_status'
    ) THEN
        EXECUTE $copy_plan$
            INSERT INTO order_execution_plans (
                id, created_at, updated_at, version, order_id, status, revision,
                finalized_at, finalized_by
            )
            SELECT gen_random_uuid()::varchar, COALESCE(o.created_at, now()), o.updated_at, 0,
                   o.id, COALESCE(o.execution_plan_status, 'DRAFT'),
                   COALESCE(o.execution_plan_revision, 0), o.execution_plan_finalized_at,
                   o.execution_plan_finalized_by
            FROM orders o
            ON CONFLICT (order_id) DO NOTHING
        $copy_plan$;
    ELSE
        INSERT INTO order_execution_plans (
            id, created_at, updated_at, version, order_id, status, revision
        )
        SELECT gen_random_uuid()::varchar, COALESCE(o.created_at, now()), o.updated_at,
               0, o.id, 'DRAFT', 0
        FROM orders o
        ON CONFLICT (order_id) DO NOTHING;
    END IF;
END $$;

ALTER TABLE orders
    DROP COLUMN IF EXISTS permit_status,
    DROP COLUMN IF EXISTS permit_number,
    DROP COLUMN IF EXISTS permit_expires_on,
    DROP COLUMN IF EXISTS permit_documents,
    DROP COLUMN IF EXISTS monitoring_relationship,
    DROP COLUMN IF EXISTS monitoring_authorization_confirmed,
    DROP COLUMN IF EXISTS monitoring_authorization_documents,
    DROP COLUMN IF EXISTS permit_required,
    DROP COLUMN IF EXISTS permit_zone_name,
    DROP COLUMN IF EXISTS legal_review_status,
    DROP COLUMN IF EXISTS flight_area_review_reason,
    DROP COLUMN IF EXISTS flight_area_review_note,
    DROP COLUMN IF EXISTS requested_scope_feasible,
    DROP COLUMN IF EXISTS flight_area_reviewed_by,
    DROP COLUMN IF EXISTS flight_area_reviewed_at;

ALTER TABLE orders
    DROP COLUMN IF EXISTS execution_plan_status,
    DROP COLUMN IF EXISTS execution_plan_revision,
    DROP COLUMN IF EXISTS execution_plan_finalized_at,
    DROP COLUMN IF EXISTS execution_plan_finalized_by;

COMMIT;
