UPDATE public.missions
SET resource_prepare_status = 'CANCELLED'
WHERE status = 'CANCELLED'
  AND resource_prepare_status <> 'CANCELLED';
