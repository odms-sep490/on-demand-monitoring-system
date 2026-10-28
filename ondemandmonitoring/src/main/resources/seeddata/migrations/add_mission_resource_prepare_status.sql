ALTER TABLE IF EXISTS public.missions
    ADD COLUMN IF NOT EXISTS resource_prepare_status VARCHAR(30) NOT NULL DEFAULT 'PLANNING';

UPDATE public.missions
SET resource_prepare_status = CASE
        WHEN status = 'CANCELLED' THEN 'CANCELLED'
        WHEN status IN ('READY', 'WAITING_CREW_CONFIRMATION', 'WAITING_OPERATOR_ACCEPTANCE',
                        'SCHEDULED', 'CONNECTED', 'PREFLIGHT_CHECKING', 'READY_TO_FLY', 'IN_FLIGHT',
                        'IN_PROGRESS', 'RETURNING', 'POSTFLIGHT_CHECKING', 'PENDING_REVIEW', 'COMPLETED')
            THEN 'PENDING'
        ELSE 'PLANNING'
    END,
    status = CASE WHEN status = 'WAITING_CREW_CONFIRMATION' THEN 'READY' ELSE status END;

UPDATE public.mission_staff_assignments a
SET response_status = CASE WHEN m.status = 'PLANNING' THEN 'PLANNING' ELSE 'PENDING' END,
    is_current = (m.status <> 'PLANNING')
FROM public.missions m
WHERE a.mission_id = m.id AND a.released_at IS NULL;

UPDATE public.mission_device_assignments a
SET checkup_status = CASE WHEN m.status = 'PLANNING' THEN 'PLANNING' ELSE 'PENDING' END,
    is_current = (m.status <> 'PLANNING')
FROM public.missions m
WHERE a.mission_id = m.id AND a.released_at IS NULL;
