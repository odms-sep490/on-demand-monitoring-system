-- Preserve existing records while moving runtime ownership from DeviceConnection
-- to MissionDeviceAssignment. Application startup applies the same backfill for
-- PostgreSQL deployments; this script is provided for controlled/manual rollout.
ALTER TABLE IF EXISTS device_telemetry
    ADD COLUMN IF NOT EXISTS mission_device_assignment_id varchar(255);
ALTER TABLE IF EXISTS persisted_pre_device_check
    ADD COLUMN IF NOT EXISTS mission_device_assignment_id varchar(255);
ALTER TABLE IF EXISTS persisted_post_device_check
    ADD COLUMN IF NOT EXISTS mission_device_assignment_id varchar(255);
ALTER TABLE IF EXISTS control_handovers
    ADD COLUMN IF NOT EXISTS mission_device_assignment_id varchar(255);

DO $$
BEGIN
    IF to_regclass('public.device_connections') IS NOT NULL THEN
        IF to_regclass('public.device_telemetry') IS NOT NULL AND EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = 'device_telemetry'
              AND column_name = 'device_connection_id'
        ) THEN
            ALTER TABLE public.device_telemetry
                ALTER COLUMN device_connection_id DROP NOT NULL;
            UPDATE device_telemetry t
            SET mission_device_assignment_id = dc.mission_device_assignment_id
            FROM device_connections dc
            WHERE t.device_connection_id = dc.id
              AND t.mission_device_assignment_id IS NULL;
        END IF;
        IF to_regclass('public.persisted_pre_device_check') IS NOT NULL AND EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = 'persisted_pre_device_check'
              AND column_name = 'device_connection_id'
        ) THEN
            ALTER TABLE public.persisted_pre_device_check
                ALTER COLUMN device_connection_id DROP NOT NULL;
            UPDATE persisted_pre_device_check c
            SET mission_device_assignment_id = dc.mission_device_assignment_id
            FROM device_connections dc
            WHERE c.device_connection_id = dc.id
              AND c.mission_device_assignment_id IS NULL;
        END IF;
        IF to_regclass('public.persisted_post_device_check') IS NOT NULL AND EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = 'persisted_post_device_check'
              AND column_name = 'device_connection_id'
        ) THEN
            ALTER TABLE public.persisted_post_device_check
                ALTER COLUMN device_connection_id DROP NOT NULL;
            UPDATE persisted_post_device_check c
            SET mission_device_assignment_id = dc.mission_device_assignment_id
            FROM device_connections dc
            WHERE c.device_connection_id = dc.id
              AND c.mission_device_assignment_id IS NULL;
        END IF;
        IF to_regclass('public.control_handovers') IS NOT NULL AND EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = 'control_handovers'
              AND column_name = 'device_connection_id'
        ) THEN
            ALTER TABLE public.control_handovers
                ALTER COLUMN device_connection_id DROP NOT NULL;
            UPDATE control_handovers h
            SET mission_device_assignment_id = dc.mission_device_assignment_id
            FROM device_connections dc
            WHERE h.device_connection_id = dc.id
              AND h.mission_device_assignment_id IS NULL;
        END IF;
    END IF;
END $$;
