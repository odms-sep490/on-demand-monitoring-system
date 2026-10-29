CREATE EXTENSION IF NOT EXISTS postgis;

ALTER TABLE IF EXISTS public.missions
    ADD COLUMN IF NOT EXISTS flight_permit_resource_hold_until TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS flight_permit_resources_released_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS last_reschedule_reason VARCHAR(50),
    ADD COLUMN IF NOT EXISTS last_reschedule_note VARCHAR(1000),
    ADD COLUMN IF NOT EXISTS last_rescheduled_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS schedule_revision BIGINT NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS ix_missions_permit_resource_hold
    ON public.missions (flight_permit_status, flight_permit_resource_hold_until);

ALTER TABLE IF EXISTS public.zones
    ADD COLUMN IF NOT EXISTS restricted BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE IF EXISTS public.drone_telemetries
    ADD COLUMN IF NOT EXISTS sim_x DOUBLE PRECISION;

ALTER TABLE IF EXISTS public.drone_telemetries
    ADD COLUMN IF NOT EXISTS sim_y DOUBLE PRECISION;

-- Execution history is separate from the legacy telemetry-snapshot
-- preflight_checks table, whose NOT NULL columns have a different contract.
-- JPA creates these tables in normal environments; keep the SQL here for
-- deployments that apply seed schema SQL.
CREATE TABLE IF NOT EXISTS public.preflight_runs (
    id VARCHAR(255) PRIMARY KEY,
    mission_id VARCHAR(255) NOT NULL REFERENCES public.missions(id),
    device_connection_id VARCHAR(255) REFERENCES public.device_connections(id),
    status VARCHAR(20) NOT NULL,
    total_checks INTEGER NOT NULL,
    passed_checks INTEGER NOT NULL DEFAULT 0,
    failed_checks INTEGER NOT NULL DEFAULT 0,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_preflight_runs_mission_created
    ON public.preflight_runs (mission_id, created_at);

ALTER TABLE IF EXISTS public.preflight_runs
    ADD COLUMN IF NOT EXISTS device_connection_id VARCHAR(255);

ALTER TABLE IF EXISTS public.preflight_runs
    DROP COLUMN IF EXISTS flight_connection_id;

CREATE TABLE IF NOT EXISTS public.preflight_run_items (
    id VARCHAR(255) PRIMARY KEY,
    preflight_run_id VARCHAR(255) NOT NULL REFERENCES public.preflight_runs(id) ON DELETE CASCADE,
    check_type VARCHAR(50) NOT NULL,
    check_name VARCHAR(120) NOT NULL,
    status VARCHAR(20) NOT NULL,
    check_level VARCHAR(20) NOT NULL,
    message VARCHAR(1000),
    checked_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_preflight_run_item_type UNIQUE (preflight_run_id, check_type)
);

CREATE INDEX IF NOT EXISTS idx_preflight_run_item_run
    ON public.preflight_run_items (preflight_run_id);



-- Reset seed order target về tọa độ simulation local XY
UPDATE orders
SET point = ST_SetSRID(ST_MakePoint(200.0, -280.0), 4326),
    updated_at = CURRENT_TIMESTAMP
WHERE id = 'ORD-78234';

-- Xóa plan/waypoints cũ để nhìn rõ accept tạo plan mới
DELETE FROM plan_waypoints
WHERE mission_plan_id IN (
    SELECT id FROM mission_plans WHERE mission_id = 'MSN-2024-0891'
);

DELETE FROM mission_plans
WHERE mission_id = 'MSN-2024-0891';

-- Reset mission về đúng trạng thái chờ operator accept
UPDATE missions
SET status = 'WAITING_OPERATOR_ACCEPTANCE',
    started_at = NULL,
    preflight_passed = NULL,
    preflight_checked_at = NULL,
    updated_at = CURRENT_TIMESTAMP
WHERE id = 'MSN-2024-0891';

-- Reset operator assignment về PENDING
UPDATE mission_operator_assignments
SET status = 'PENDING',
    is_current = true,
    responded_at = NULL,
    released_at = NULL,
    updated_at = CURRENT_TIMESTAMP
WHERE mission_id = 'MSN-2024-0891';

-- Đảm bảo drone assignment vẫn current
UPDATE mission_drone_assignments
SET status = 'ACTIVE',
    is_current = true,
    updated_at = CURRENT_TIMESTAMP
WHERE mission_id = 'MSN-2024-0891';



DELETE FROM plan_waypoints
WHERE mission_plan_id IN (
    SELECT id FROM mission_plans WHERE mission_id = 'MSN-2024-0891'
);

DELETE FROM mission_plans
WHERE mission_id = 'MSN-2024-0891';

UPDATE orders
SET point = ST_SetSRID(ST_MakePoint(200.0, -280.0), 4326),
    updated_at = CURRENT_TIMESTAMP
WHERE id = 'ORD-78234';

UPDATE missions
SET status = 'WAITING_OPERATOR_ACCEPTANCE',
    started_at = NULL,
    preflight_passed = NULL,
    preflight_checked_at = NULL,
    preflight_retry_count = 0,
    updated_at = CURRENT_TIMESTAMP
WHERE id = 'MSN-2024-0891';

UPDATE mission_operator_assignments
SET status = 'PENDING',
    is_current = true,
    responded_at = NULL,
    released_at = NULL,
    updated_at = CURRENT_TIMESTAMP
WHERE mission_id = 'MSN-2024-0891';

DELETE FROM plan_waypoints
WHERE mission_plan_id IN (
    SELECT id FROM mission_plans WHERE mission_id = 'MSN-2024-0891'
);

DELETE FROM mission_plans
WHERE mission_id = 'MSN-2024-0891';

UPDATE orders
SET point = ST_SetSRID(ST_MakePoint(200.0, -280.0), 4326),
    updated_at = CURRENT_TIMESTAMP
WHERE id = 'ORD-78234';

UPDATE missions
SET status = 'WAITING_OPERATOR_ACCEPTANCE',
    started_at = NULL,
    preflight_passed = NULL,
    preflight_checked_at = NULL,
    preflight_retry_count = 0,
    updated_at = CURRENT_TIMESTAMP
WHERE id = 'MSN-2024-0891';

UPDATE mission_operator_assignments
SET status = 'PENDING',
    is_current = true,
    responded_at = NULL,
    released_at = NULL,
    updated_at = CURRENT_TIMESTAMP
WHERE mission_id = 'MSN-2024-0891';
