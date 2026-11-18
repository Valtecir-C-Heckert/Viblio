BEGIN;
ALTER TABLE public.viblio_users ALTER COLUMN share_activity SET DEFAULT true;
ALTER TABLE public.viblio_users ALTER COLUMN activity_enabled_at SET DEFAULT now();
UPDATE public.viblio_users SET share_activity=true,activity_enabled_at=coalesce(activity_enabled_at,now());
COMMIT;
