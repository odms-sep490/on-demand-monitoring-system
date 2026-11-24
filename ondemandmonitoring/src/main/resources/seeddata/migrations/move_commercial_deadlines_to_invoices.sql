-- One-time migration: commercial payment deadlines belong to their invoice.
-- Stop the application before running this script. Back up the database first.
-- The transaction copies both deadlines, then removes their legacy Order columns.
BEGIN;
SET LOCAL search_path = public, pg_catalog;

LOCK TABLE public.orders, public.invoices IN ACCESS EXCLUSIVE MODE;

DO $$
BEGIN
    IF to_regclass('public.orders') IS NULL OR to_regclass('public.invoices') IS NULL THEN
        RAISE EXCEPTION 'Required tables public.orders and public.invoices must exist';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_attribute
        WHERE attrelid = 'public.orders'::regclass
          AND attname = 'deposit_expires_at' AND NOT attisdropped
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_attribute
        WHERE attrelid = 'public.orders'::regclass
          AND attname = 'final_payment_expires_at' AND NOT attisdropped
    ) THEN
        RAISE EXCEPTION 'Legacy deadline columns are missing from orders; migration already applied or schema is unexpected';
    END IF;
END $$;

ALTER TABLE public.invoices
    ADD COLUMN IF NOT EXISTS deposit_expires_at timestamptz,
    ADD COLUMN IF NOT EXISTS final_payment_expires_at timestamptz;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM public.orders o
        LEFT JOIN public.invoices i ON i.order_id = o.id
        WHERE (o.deposit_expires_at IS NOT NULL OR o.final_payment_expires_at IS NOT NULL)
          AND i.id IS NULL
    ) THEN
        RAISE EXCEPTION 'Cannot move payment deadlines: an Order with a deadline has no Invoice';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.orders o
        JOIN public.invoices i ON i.order_id = o.id
        WHERE (i.deposit_expires_at IS NOT NULL AND o.deposit_expires_at IS NOT NULL
                   AND i.deposit_expires_at <> o.deposit_expires_at)
           OR (i.final_payment_expires_at IS NOT NULL AND o.final_payment_expires_at IS NOT NULL
                   AND i.final_payment_expires_at <> o.final_payment_expires_at)
    ) THEN
        RAISE EXCEPTION 'Cannot move payment deadlines: Invoice and Order values conflict';
    END IF;
END $$;

UPDATE public.invoices i
SET deposit_expires_at = COALESCE(i.deposit_expires_at, o.deposit_expires_at),
    final_payment_expires_at = COALESCE(i.final_payment_expires_at, o.final_payment_expires_at)
FROM public.orders o
WHERE i.order_id = o.id
  AND (o.deposit_expires_at IS NOT NULL OR o.final_payment_expires_at IS NOT NULL);

ALTER TABLE public.orders
    DROP COLUMN deposit_expires_at,
    DROP COLUMN final_payment_expires_at;

CREATE INDEX IF NOT EXISTS ix_invoices_deposit_expiration
    ON public.invoices (deposit_expires_at, status);
CREATE INDEX IF NOT EXISTS ix_invoices_final_payment_expiration
    ON public.invoices (final_payment_expires_at, status);

COMMIT;
