-- Stores recommendation snapshots for analytics/search. Generating one never allocates or holds resources.
BEGIN;

CREATE TABLE IF NOT EXISTS public.mission_recommendations (
    id varchar(255) PRIMARY KEY,
    created_at timestamptz NOT NULL,
    updated_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    mission_id varchar(255) NOT NULL REFERENCES public.missions(id),
    execution_plan_revision bigint NOT NULL,
    schedule_revision bigint NOT NULL,
    search_from date NOT NULL,
    search_to date NOT NULL,
    recommendation_date date NOT NULL,
    scheduled_start_at timestamptz NOT NULL,
    scheduled_end_at timestamptz NOT NULL,
    available_device_count integer NOT NULL,
    capability_compatible_device_count integer NOT NULL,
    available_staff_count integer NOT NULL,
    recommended_device_count integer NOT NULL,
    device_shortage_count integer NOT NULL,
    staff_shortage_count integer NOT NULL,
    device_availability_ratio numeric(8,4) NOT NULL,
    staff_availability_ratio numeric(8,4) NOT NULL,
    effective_availability_ratio numeric(8,4) NOT NULL,
    risk_level varchar(20) NOT NULL,
    required_capabilities_json text NOT NULL,
    device_candidates_json text NOT NULL,
    warnings_json text NOT NULL,
    ai_model varchar(255) NOT NULL,
    ai_minimum_devices integer NOT NULL,
    ai_recommended_devices integer NOT NULL,
    ai_reason text,
    ai_assumptions_json text NOT NULL,
    ai_warnings_json text NOT NULL
);

CREATE INDEX IF NOT EXISTS ix_mission_recommendation_mission_created
    ON public.mission_recommendations (mission_id, created_at);
CREATE INDEX IF NOT EXISTS ix_mission_recommendation_mission_date
    ON public.mission_recommendations (mission_id, recommendation_date);

COMMIT;
